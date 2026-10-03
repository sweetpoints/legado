package io.legado.app.ui.association.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationNativeReceipt
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssociationImportRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pausedNonCooperativePreparationNeverDispatchesAfterResume() {
        val owner = Owner()
        val sessions = Sessions()
        val firstPreparation = CompletableDeferred<Unit>()
        val preparations = AtomicInteger()
        val deliveries = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        lateinit var model: AssociationImportViewModel
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = model(sessions)
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        AssociationImportRoute(
                            model = model,
                            configuredDirectory = null,
                            privateDirectory = "/private/books",
                            canDeliver = { true },
                            prepare = {
                                if (preparations.incrementAndGet() == 1) {
                                    withContext(NonCancellable) { firstPreparation.await() }
                                }
                                PreparedAssociationDelivery({ deliveries.incrementAndGet() })
                            },
                            onDeliveryError = errors::add,
                            onChoosePrivateDirectory = {},
                            onClose = {},
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) { preparations.get() == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.runOnIdle { firstPreparation.complete(Unit) }
            compose.waitUntil(timeoutMillis = 5_000) { deliveries.get() == 1 }
            compose.waitUntil(timeoutMillis = 5_000) {
                sessions.current.effects.isEmpty() && sessions.current.claimedEffects.isEmpty()
            }
            compose.runOnIdle {
                assertEquals(2, preparations.get())
                assertEquals(1, deliveries.get())
                assertTrue(errors.isEmpty())
            }
        } finally {
            firstPreparation.complete(Unit)
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.DESTROYED
                ViewModelStore().apply {
                    put("model", model)
                    clear()
                }
            }
        }
    }

    private fun model(sessions: Sessions) =
        AssociationImportViewModel(
            SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
            sessions,
            object : AssociationFileRepository {
                override suspend fun inspect(
                    ticket: String,
                    input: AssociationInput,
                ): AssociationFileInspection = error("Restored preview must not inspect again")
            },
            object : AssociationOnlineRepository {
                override suspend fun determine(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun readConfig(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun text(url: String): String = error("Unused")
            },
        )

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Sessions : AssociationSessionRepository {
        @Volatile
        var current =
            AssociationSession(
                AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedText),
                phase = AssociationPhase.Preview,
                effects =
                    listOf(
                        AssociationNativeReceipt(
                            "launch",
                            0,
                            AssociationNativeKind.ImportDialog,
                            "bookSource",
                            "private source",
                        )
                    ),
            )

        override suspend fun create(input: AssociationInput): String = error("Restored session")

        override suspend fun read(ticket: String): AssociationSession = current

        override suspend fun write(ticket: String, value: AssociationSession): Boolean {
            if (value.revision <= current.revision) return false
            current = value
            return true
        }

        override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray): Unit =
            error("Unused")

        override suspend fun readBytes(ticket: String, name: String): ByteArray = error("Unused")

        override suspend fun release(ticket: String) = Unit
    }
}
