package io.legado.app.ui.rss.source.manage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class RssSourceManagementRouteTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Repo : RssSourceManagementRepository {
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        var entity =
            RssSource(
                sourceUrl = "https://route.invalid",
                sourceName = "Latest",
                variableComment = "metadata",
            )

        override fun rows(filter: RssSourceManagementFilter) =
            MutableStateFlow(listOf(RssSourceManagementRow("id", "Name", "Name", null, true, 0)))

        override fun groups() = MutableStateFlow(emptyList<String>())

        override suspend fun source(id: String): RssSource {
            entered.complete(Unit)
            withContext(NonCancellable) { gate?.await() }
            return entity.copy()
        }

        override suspend fun enabled(ids: List<String>, enabled: Boolean) {}

        override suspend fun group(ids: List<String>, value: String, add: Boolean) {}

        override suspend fun edge(ids: List<String>, top: Boolean) {}

        override suspend fun move(id: String, target: String, after: Boolean) {}

        override suspend fun delete(ids: List<String>) {}

        override suspend fun export(ids: List<String>) =
            RssSourceManagementExport("owned", "sources.json")

        override suspend fun releaseExport(path: String) {}

        override suspend fun importDefault() {}

        override suspend fun importHistory() = emptyList<String>()

        override suspend fun rememberImport(value: String) {}

        override suspend fun forgetImport(value: String) {}
    }

    private class Sessions : RssSourceManagementSessionRepository {
        var value: RssSourceManagementCheckpoint? = null

        override suspend fun read(session: String) = value

        override suspend fun write(session: String, value: RssSourceManagementCheckpoint) {
            this.value = value
        }

        override suspend fun release(session: String) {}
    }

    private val models = mutableListOf<RssSourceManagementViewModel>()

    @After
    fun cleanup() {
        compose.runOnIdle { models.forEach { it.stop() } }
    }

    private fun model(repo: Repo = Repo()) =
        RssSourceManagementViewModel(repo, Sessions(), SavedStateHandle()).also { models += it }

    private fun bind(model: RssSourceManagementViewModel) {
        compose.runOnIdle {
            model.bind(RssSourceManagementLabels("Enabled", "Disabled", "Login", "None"))
        }
        compose.waitUntil { model.state.value.loaded }
    }

    @Test
    fun pendingNavigationWaitsUntilVisibleOwnerResumesAndConsumesExactlyOnce() {
        val owner = Owner()
        val model = model()
        val calls = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RssSourceManagementRoute(
                        model,
                        { true },
                        {},
                        { request, _ -> calls += request.effect.nonce },
                    )
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        bind(model)
        compose.runOnIdle { model.effect(RssSourceManagementAction.Help) }
        compose.waitUntil { model.state.value.pending != null }
        assertTrue(calls.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls.size == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, calls.size)
        assertNull(model.state.value.pending)
    }

    @Test
    fun pausedNonCooperativeLookupKeepsReceiptAndResumeReReadsLatestMetadata() {
        val owner = Owner()
        val repo = Repo().apply { gate = CompletableDeferred() }
        val model = model(repo)
        val received = mutableListOf<RssSource>()
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RssSourceManagementRoute(
                        model,
                        { true },
                        {},
                        { _, source -> received += checkNotNull(source) },
                    )
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        bind(model)
        compose.runOnIdle { model.effect(RssSourceManagementAction.Edit, "id") }
        compose.waitUntil { repo.entered.isCompleted }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        assertNotNull(model.state.value.pending)
        compose.runOnIdle {
            repo.entity = repo.entity.copy(sourceName = "Edited while paused")
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { received.size == 1 }
        assertEquals("Edited while paused", received.single().sourceName)
        assertEquals("metadata", received.single().variableComment)
    }

    @Test
    fun nativeHostFailureCannotReplayConsumedNavigation() {
        val model = model()
        var calls = 0
        compose.setContent {
            LegadoComposeTheme {
                RssSourceManagementRoute(
                    model,
                    { true },
                    {},
                    { _, _ ->
                        calls++
                        error("Native unavailable")
                    },
                )
            }
        }
        bind(model)
        compose.runOnIdle { model.effect(RssSourceManagementAction.Help) }
        compose.waitUntil { model.state.value.error != null }
        assertEquals(1, calls)
        compose.runOnIdle { model.retry() }
        compose.waitForIdle()
        assertEquals(1, calls)
    }
}
