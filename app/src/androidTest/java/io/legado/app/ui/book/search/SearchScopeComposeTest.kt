package io.legado.app.ui.book.search

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchScopeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun cleanup() {
        compose.runOnIdle { stores.forEach { it.clear() } }
    }

    private fun newModel(
        saved: SavedStateHandle = SavedStateHandle(),
        repo: SearchScopeRepository = Fake(),
    ): SearchScopeViewModel {
        val model = SearchScopeViewModel(repo, saved)
        stores += ViewModelStore().apply { put("scope", model) }
        return model
    }

    @Test
    fun groupSelectionClickOrderSurvivesTabSwitchAndConfirmsExactlyOnce() {
        lateinit var model: SearchScopeViewModel
        val events = mutableListOf<SearchScopeResult>()
        compose.runOnIdle { model = newModel() }
        compose.setContent {
            LegadoComposeTheme { SearchScopeRoute(model, { true }, { events += it }) }
        }
        compose.waitUntil { !model.state.value.groupsLoading }
        compose.onNodeWithTag("search-scope-group-B").performClick()
        compose.onNodeWithTag("search-scope-group-A").performClick()
        compose.onNodeWithTag("search-scope-tab-Sources").performClick()
        compose.onNodeWithTag("search-scope-tab-Groups").performClick()
        compose.onNodeWithTag("search-scope-group-A").assertIsOn()
        compose.onNodeWithTag("search-scope-group-B").assertIsOn()
        compose.onNodeWithTag("search-scope-confirm").performClick()
        compose.waitUntil { events.size == 1 }
        assertEquals(SearchScopeResult(true, "B,A"), events.single())
        compose.onNodeWithTag("search-scope-confirm").assertIsNotEnabled()
        compose.runOnIdle { model.confirm() }
        assertEquals(1, events.size)
    }

    @Test
    fun selectingSourceThenFilteringToNoRowsStillConfirmsPreviouslySelectedSource() {
        lateinit var model: SearchScopeViewModel
        val events = mutableListOf<SearchScopeResult>()
        compose.runOnIdle { model = newModel() }
        compose.setContent {
            LegadoComposeTheme { SearchScopeRoute(model, { true }, { events += it }) }
        }
        compose.onNodeWithTag("search-scope-filter").assertDoesNotExist()
        compose.onNodeWithTag("search-scope-tab-Sources").performClick()
        compose.waitUntil { model.state.value.sources.size == 2 }
        compose.onNodeWithTag("search-scope-source-one").performClick()
        compose.onNodeWithTag("search-scope-source-one").assertIsSelected()
        compose.onNodeWithTag("search-scope-source-two").assertIsNotSelected()
        compose.onNodeWithTag("search-scope-filter").performClick()
        compose.onNodeWithTag("search-scope-query").performTextReplacement("missing")
        compose.waitUntil { model.state.value.sources.isEmpty() }
        compose.onNodeWithTag("search-scope-source-one").assertDoesNotExist()
        compose.onNodeWithTag("search-scope-confirm").performClick()
        compose.waitUntil { events.size == 1 }
        assertEquals("AB::one", events.single().scope)
    }

    @Test
    fun cancelHasNoScopeCallbackAndAllSourcesOverridesExistingGroups() {
        lateinit var model: SearchScopeViewModel
        val events = mutableListOf<SearchScopeResult>()
        var next by mutableStateOf<SearchScopeViewModel?>(null)
        compose.runOnIdle {
            model = newModel()
            next = model
        }
        compose.setContent {
            LegadoComposeTheme {
                key(next) { SearchScopeRoute(next!!, { true }, { events += it }) }
            }
        }
        compose.waitUntil { !model.state.value.groupsLoading }
        compose.onNodeWithTag("search-scope-cancel").performClick()
        compose.waitUntil { events.size == 1 }
        assertFalse(events.single().confirm)
        compose.runOnIdle {
            model = newModel()
            next = model
        }
        compose.waitUntil { !model.state.value.groupsLoading }
        compose.onNodeWithTag("search-scope-group-A").performClick()
        compose.onNodeWithTag("search-scope-all").performClick()
        compose.waitUntil { events.size == 2 }
        assertEquals(SearchScopeResult(true, ""), events.last())
    }

    @Test
    fun realErrorRetryReloadsGroupsAndAllowsSelection() {
        val repo = Fake().apply { failed = true }
        lateinit var model: SearchScopeViewModel
        compose.runOnIdle { model = newModel(repo = repo) }
        compose.setContent { LegadoComposeTheme { SearchScopeRoute(model, { true }, {}) } }
        compose.waitUntil { model.state.value.groupsError != null }
        compose.onNodeWithText("failed").assertExists()
        compose.runOnIdle { repo.failed = false }
        compose.onNodeWithTag("search-scope-retry").performClick()
        compose.waitUntil {
            !model.state.value.groupsLoading && model.state.value.groupsError == null
        }
        compose.onNodeWithTag("search-scope-group-A").performClick()
        compose.onNodeWithTag("search-scope-group-A").assertIsOn()
    }

    @Test
    fun routePausesSourceSubscriptionAndConsumeBeforeHostPreventsResumeOrRecompositionReplay() {
        val owner = Owner()
        val repo = Fake()
        lateinit var model: SearchScopeViewModel
        var calls = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = newModel(repo = repo)
            model.tab(SearchScopeTab.Sources)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    SearchScopeRoute(
                        model,
                        { true },
                        {
                            assertNull(model.state.value.result)
                            calls++
                            owner.registry.currentState = Lifecycle.State.CREATED
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, repo.collectors)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil { repo.collectors == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil { repo.collectors == 0 }
        compose.runOnIdle { model.confirm() }
        compose.waitForIdle()
        assertEquals(0, calls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun pendingEventSurvivesViewRecreationThenConsumedProcessRestoreDoesNotReplay() {
        val owner = Owner()
        var next by mutableStateOf<SearchScopeViewModel?>(null)
        var calls = 0
        val saved = SavedStateHandle()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            next = newModel(saved)
            next!!.cancel()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    key(next) {
                        SearchScopeRoute(
                            next!!,
                            { true },
                            {
                                assertFalse(it.confirm)
                                assertNull(next!!.state.value.result)
                                calls++
                            },
                        )
                    }
                }
            }
        }
        lateinit var restoredSaved: SavedStateHandle
        compose.runOnIdle {
            restoredSaved = copy(saved)
            next = newModel(restoredSaved)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle { next = newModel(copy(restoredSaved)) }
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : SearchScopeRepository {
        var failed = false
        var collectors = 0

        override suspend fun groups(): List<String> {
            if (failed) error("failed")
            return listOf("A", "B")
        }

        override fun sources(query: String): Flow<List<SearchScopeSource>> = flow {
            collectors++
            try {
                emit(
                    if (query == "missing") emptyList()
                    else listOf(SearchScopeSource("one", "A:B"), SearchScopeSource("two", "Second"))
                )
                kotlinx.coroutines.awaitCancellation()
            } finally {
                collectors--
            }
        }
    }
}
