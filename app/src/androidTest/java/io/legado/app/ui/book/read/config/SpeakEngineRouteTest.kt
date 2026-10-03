package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeakEngineRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseDuringExportPreparationResumesWithoutDuplicatePickerDelivery() {
        val repository = Fake()
        val owner = Owner()
        lateinit var model: SpeakEngineViewModel
        val delivered = mutableListOf<SpeakEngineEffect>()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = SpeakEngineViewModel(repository, SavedStateHandle())
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    SpeakEngineRoute(
                        model,
                        { true },
                        { effect ->
                            assertTrue(model.state.value.pending.none { it.id == effect.id })
                            delivered += effect
                        },
                        {},
                        {},
                        Modifier.height(500.dp),
                    )
                }
            }
        }
        compose.runOnIdle { model.export(true) }
        compose.waitUntil { repository.exports == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { repository.exportGate.complete(Unit) }
        compose.waitForIdle()
        assertTrue(delivered.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { delivered.size == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, delivered.size)
        assertNotNull(delivered.single().export)
    }

    @Test
    fun compositionRecreationDoesNotRepeatDeliveredPlatformEffect() {
        val repository = Fake()
        lateinit var model: SpeakEngineViewModel
        var visible by mutableStateOf(true)
        var deliveries = 0
        compose.runOnIdle { model = SpeakEngineViewModel(repository, SavedStateHandle()) }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    SpeakEngineRoute(
                        model,
                        { true },
                        {
                            assertTrue(model.state.value.pending.isEmpty())
                            deliveries++
                        },
                        {},
                        {},
                        Modifier.height(500.dp),
                    )
                }
        }
        compose.runOnIdle { model.local() }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, deliveries)
    }

    @Test
    fun cacheWorkSurvivesRouteRecreationWithoutRepeatingServiceReset() {
        val repository = Fake()
        lateinit var model: SpeakEngineViewModel
        var visible by mutableStateOf(true)
        var resets = 0
        var toasts = 0
        compose.runOnIdle { model = SpeakEngineViewModel(repository, SavedStateHandle()) }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    SpeakEngineRoute(
                        model,
                        { true },
                        { event ->
                            when (event.action) {
                                SpeakEngineAction.ClearCache -> {
                                    resets++
                                    model.clearCacheData()
                                }
                                SpeakEngineAction.CacheCleared -> toasts++
                                else -> error("Unexpected effect")
                            }
                        },
                        {},
                        {},
                        Modifier.height(500.dp),
                    )
                }
        }
        compose.runOnIdle { model.clearCache() }
        compose.waitUntil { repository.clears == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            repository.clearGate.complete(Unit)
            visible = true
        }
        compose.waitUntil { toasts == 1 }
        assertEquals(1, resets)
        assertEquals(1, repository.clears)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : SpeakEngineRepository {
        override val engines = MutableStateFlow(emptyList<SpeakHttpEngine>())
        val exportGate = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()
        var exports = 0
        var clears = 0

        override fun initialSelection(): String? = null

        override suspend fun systemEngines() = emptyList<SpeakSystemEngine>()

        override suspend fun apply(selection: String?, general: Boolean) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun importDefault() = Unit

        override suspend fun clearCache() {
            clears++
            clearGate.await()
        }

        override suspend fun histories() = emptyList<String>()

        override suspend fun saveHistories(values: List<String>) = Unit

        override suspend fun export(id: Long?): SpeakEngineExport {
            exports++
            exportGate.await()
            return SpeakEngineExport("all.json", byteArrayOf(1))
        }

        override suspend fun share(url: String) = SpeakEngineShare(url)

        override suspend fun passphrase(url: String) = "phrase"
    }
}
