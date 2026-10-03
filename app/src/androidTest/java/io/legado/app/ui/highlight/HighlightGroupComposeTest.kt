package io.legado.app.ui.highlight

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.HighlightGroupRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class HighlightGroupComposeTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun cleanup() {
        compose.runOnIdle { stores.forEach { it.clear() } }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        HighlightGroupViewModel(repo, saved).also {
            stores += ViewModelStore().apply { put("groups", it) }
        }

    @Test
    fun renameDraftRestoresAndBlankConfirmationCancelsWithoutMutation() {
        val repo = Fake()
        val saved = SavedStateHandle()
        var next by mutableStateOf<HighlightGroupViewModel?>(null)
        compose.runOnIdle { next = model(repo, saved) }
        compose.setContent {
            LegadoComposeTheme { key(next) { HighlightGroupRoute(next!!, { true }, {}, {}) } }
        }
        compose.waitUntil { !next!!.state.value.loading }
        compose.onNodeWithTag("highlight-group-edit-A").performClick()
        compose.onNodeWithTag("highlight-group-name").performTextReplacement(" Draft ")
        compose.runOnIdle { next = model(repo, copy(saved)) }
        compose.waitUntil { !next!!.state.value.loading }
        compose.onNodeWithTag("highlight-group-name").assertTextEquals(" Draft ")
        compose.onNodeWithTag("highlight-group-rename-confirm").performClick()
        compose.waitUntil { repo.calls.size == 1 }
        assertEquals("rename:A:Draft", repo.calls.single())
        compose.onNodeWithTag("highlight-group-edit-B").performClick()
        compose.onNodeWithTag("highlight-group-name").performTextReplacement("   ")
        compose.onNodeWithTag("highlight-group-rename-confirm").performClick()
        compose.onNodeWithTag("highlight-group-name").assertDoesNotExist()
        assertEquals(1, repo.calls.size)
    }

    @Test
    fun deleteCancelPreservesRulesWhileConfirmRequestsOneReaderRefresh() {
        val repo = Fake()
        lateinit var model: HighlightGroupViewModel
        var refreshes = 0
        compose.runOnIdle { model = model(repo) }
        compose.setContent {
            LegadoComposeTheme {
                HighlightGroupRoute(
                    model,
                    { true },
                    {
                        assertFalse(model.state.value.refresh)
                        refreshes++
                    },
                    {},
                )
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("highlight-group-delete-A").performClick()
        compose.onNodeWithTag("highlight-group-cancel").performClick()
        assertTrue(repo.calls.isEmpty())
        compose.onNodeWithTag("highlight-group-delete-A").performClick()
        compose.onNodeWithTag("highlight-group-delete-confirm").performClick()
        compose.waitUntil { refreshes == 1 }
        assertEquals(listOf("delete:A"), repo.calls)
        compose.onNodeWithTag("highlight-group-label-A").assertDoesNotExist()
    }

    @Test
    fun moveTargetsExcludeSourceAndDistinguishRealNoGroupLabelFromNull() {
        val repo = Fake()
        lateinit var model: HighlightGroupViewModel
        compose.runOnIdle { model = model(repo) }
        compose.setContent { LegadoComposeTheme { HighlightGroupRoute(model, { true }, {}, {}) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("highlight-group-delete-A").performClick()
        compose.onNodeWithTag("highlight-group-choose-move").performClick()
        compose.onNodeWithTag("highlight-group-move-A").assertDoesNotExist()
        compose.onNodeWithTag("highlight-group-move-No group").performClick()
        compose.waitUntil { repo.calls.size == 1 }
        assertEquals("move:A:No group", repo.calls.single())
        compose.onNodeWithTag("highlight-group-delete-B").performClick()
        compose.onNodeWithTag("highlight-group-choose-move").performClick()
        compose.onNodeWithTag("highlight-group-move-none").performScrollTo().performClick()
        compose.waitUntil { repo.calls.size == 2 }
        assertEquals("move:B:null", repo.calls.last())
    }

    @Test
    fun failureKeepsDeleteConfirmationForRetryAndReadFailureOffersReload() {
        val repo = Fake().apply { readFails = true }
        lateinit var model: HighlightGroupViewModel
        compose.runOnIdle { model = model(repo) }
        compose.setContent { LegadoComposeTheme { HighlightGroupRoute(model, { true }, {}, {}) } }
        compose.waitUntil { model.state.value.error != null }
        compose.runOnIdle { repo.readFails = false }
        compose.onNodeWithTag("highlight-group-retry").performClick()
        compose.waitUntil { !model.state.value.loading && model.state.value.error == null }
        compose.runOnIdle { repo.writeFails = true }
        compose.onNodeWithTag("highlight-group-delete-A").performClick()
        compose.onNodeWithTag("highlight-group-delete-confirm").performClick()
        compose.waitUntil { model.state.value.error == "write failed" }
        compose.onNodeWithTag("highlight-group-delete-confirm").assertIsEnabled()
        compose.runOnIdle { repo.writeFails = false }
        compose.onNodeWithTag("highlight-group-delete-confirm").performClick()
        compose.waitUntil { model.state.value.stage == HighlightGroupStage.None }
        assertEquals(listOf("delete:A"), repo.calls)
    }

    @Test
    fun observationPausesAndPendingRefreshRestoresThenConsumedRestoreCannotReplayHost() {
        val owner = Owner()
        val repo = Fake()
        val saved = SavedStateHandle()
        var next by mutableStateOf<HighlightGroupViewModel?>(null)
        var refreshes = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            next = model(repo, saved)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    key(next) {
                        HighlightGroupRoute(
                            next!!,
                            { true },
                            {
                                assertFalse(next!!.state.value.refresh)
                                refreshes++
                                owner.registry.currentState = Lifecycle.State.CREATED
                            },
                            {},
                        )
                    }
                }
            }
        }
        compose.waitUntil { repo.collectors == 1 && !next!!.state.value.loading }
        compose.onNodeWithTag("highlight-group-delete-A").performClick()
        compose.onNodeWithTag("highlight-group-delete-confirm").performClick()
        compose.waitUntil { next!!.state.value.refresh }
        assertEquals(0, refreshes)
        lateinit var restored: SavedStateHandle
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil { repo.collectors == 0 }
        compose.runOnIdle {
            restored = copy(saved)
            next = model(repo, restored)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { refreshes == 1 && repo.collectors == 0 }
        compose.runOnIdle {
            next = model(repo, copy(restored))
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { !next!!.state.value.loading }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : HighlightGroupRepository {
        val groups = MutableStateFlow(listOf("A", "B", "No group"))
        var readFails = false
        var writeFails = false
        var collectors = 0
        val calls = mutableListOf<String>()

        override fun groups(): Flow<List<String>> = flow {
            if (readFails) error("read failed")
            collectors++
            try {
                emitAll(groups)
            } finally {
                collectors--
            }
        }

        private fun record(value: String, source: String, target: String? = null) {
            if (writeFails) error("write failed")
            calls += value
            groups.value =
                (groups.value.filterNot { it == source } + listOfNotNull(target)).distinct()
        }

        override suspend fun rename(source: String, replacement: String) =
            record("rename:$source:$replacement", source, replacement)

        override suspend fun delete(source: String) = record("delete:$source", source)

        override suspend fun move(source: String, target: String?) =
            record("move:$source:$target", source, target)
    }
}
