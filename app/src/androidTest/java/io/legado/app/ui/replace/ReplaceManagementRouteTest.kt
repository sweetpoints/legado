package io.legado.app.ui.replace

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class ReplaceManagementRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ReplaceManagementViewModel>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Repo : ReplaceManagementRepository {
        override fun rows(filter: ReplaceManagementFilter) =
            MutableStateFlow(listOf(ReplaceManagementRow(1L, "Rule", "Rule", null, true, 0)))

        override fun groups() = MutableStateFlow(emptyList<String>())

        override suspend fun enabled(ids: List<Long>, value: Boolean) {}

        override suspend fun group(ids: List<Long>, value: String, add: Boolean) {}

        override suspend fun edge(ids: List<Long>, top: Boolean) {}

        override suspend fun move(id: Long, target: Long, after: Boolean) {}

        override suspend fun delete(ids: List<Long>) {}

        override suspend fun export(ids: List<Long>) = ReplaceManagementExport("owned")

        override suspend fun releaseExport(path: String) {}

        override suspend fun importHistory() = emptyList<String>()

        override suspend fun rememberImport(value: String) {}

        override suspend fun forgetImport(value: String) {}

        override suspend fun manual() = false

        override suspend fun manual(value: Boolean) {}

        override suspend fun refreshPipeline() {}
    }

    private class Sessions : ReplaceManagementSessionRepository {
        var value: ReplaceManagementCheckpoint? = null

        override suspend fun read(session: String) = value

        override suspend fun write(session: String, value: ReplaceManagementCheckpoint) {
            this.value = value
        }

        override suspend fun release(session: String) {}
    }

    private class Importer : ReplaceRulePreparedImportRepository {
        var gate: CompletableDeferred<Unit>? = null
        var count = 0
        var failed = false
        val released = CompletableDeferred<String>()

        override suspend fun prepare(source: String): String {
            count++
            withContext(NonCancellable) { gate?.await() }
            if (failed) error("Parse failed")
            return "prepared"
        }

        override suspend fun release(session: String) {
            released.complete(session)
        }
    }

    @After
    fun after() {
        compose.runOnIdle { models.forEach { it.stop() } }
    }

    private fun make() =
        ReplaceManagementViewModel(Repo(), Sessions(), SavedStateHandle()).also {
            models += it
            it.bind(ReplaceManagementLabels("Enabled", "Disabled", "No group"))
        }

    @Test
    fun inactiveOwnerHoldsPendingAndResumedDeliversExactlyOnceWithoutReplay() {
        val owner = Owner()
        lateinit var model: ReplaceManagementViewModel
        var deliveries = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            model = make()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ReplaceManagementRoute(
                        model,
                        { true },
                        {},
                        { _, _ -> deliveries++ },
                        Importer(),
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.loaded }
        compose.runOnIdle { model.effect(ReplaceManagementAction.Help) }
        compose.waitForIdle()
        assertEquals(0, deliveries)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 10000) { deliveries == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, deliveries)
    }

    @Test
    fun nonCooperativePreparedImportAfterPauseIsReleasedAndOriginalReceiptRemains() {
        val owner = Owner()
        val importer = Importer().apply { gate = CompletableDeferred() }
        lateinit var model: ReplaceManagementViewModel
        var deliveries = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = make()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ReplaceManagementRoute(model, { true }, {}, { _, _ -> deliveries++ }, importer)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.loaded }
        compose.runOnIdle { model.effect(ReplaceManagementAction.ImportInput, input = "Exact") }
        compose.waitUntil(timeoutMillis = 10000) { importer.count == 1 }
        val nonce = model.state.value.pending!!.nonce
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            importer.gate!!.complete(Unit)
        }
        runBlocking { withTimeout(10000) { importer.released.await() } }
        assertEquals(0, deliveries)
        assertEquals(nonce, model.state.value.pending!!.nonce)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 10000) { deliveries == 1 }
        assertNull(model.state.value.pending)
    }

    @Test
    fun preparationFailureCanRetrySameReceiptAndHostThrowDoesNotReplay() {
        val importer = Importer().apply { failed = true }
        lateinit var model: ReplaceManagementViewModel
        var deliveries = 0
        compose.runOnIdle { model = make() }
        compose.setContent {
            LegadoComposeTheme {
                ReplaceManagementRoute(
                    model,
                    { true },
                    {},
                    { _, _ ->
                        deliveries++
                        error("Native host failed")
                    },
                    importer,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.loaded }
        compose.runOnIdle { model.effect(ReplaceManagementAction.ImportInput, input = "Exact") }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.error != null }
        val nonce = model.state.value.pending!!.nonce
        assertEquals(0, deliveries)
        compose.runOnIdle {
            importer.failed = false
            model.retry()
        }
        compose.waitUntil(timeoutMillis = 10000) { deliveries == 1 }
        assertNull(model.state.value.pending)
        assertEquals("prepared", runBlocking { importer.released.await() })
        assertNotNull(nonce)
        compose.runOnIdle { model.retry() }
        compose.waitForIdle()
        assertEquals(1, deliveries)
    }
}
