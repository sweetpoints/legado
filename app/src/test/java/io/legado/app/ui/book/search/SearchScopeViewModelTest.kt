package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScopeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun groupSelectionsKeepClickOrderAcrossTabsAndDoNotIncludeUncheckedOrUnknownGroups() = runTest(dispatcher) {
        val model = SearchScopeViewModel(Fake(), SavedStateHandle()); val store = owned(model)
        try { runCurrent(); model.group("B"); model.group("A"); model.group("unknown"); model.group("B"); model.group("B")
            model.tab(SearchScopeTab.Sources); model.tab(SearchScopeTab.Groups); model.confirm()
            assertEquals(SearchScopeResult(true, "A,B"), model.state.value.result)
        } finally { store.clear() }
    }
    @Test fun selectedSourceSurvivesFilteringAndUsesCapturedNameWithColonStripped() = runTest(dispatcher) {
        val repo = Fake(); val model = SearchScopeViewModel(repo, SavedStateHandle()); val store = owned(model)
        try { model.setActive(true); model.tab(SearchScopeTab.Sources); runCurrent(); model.source("one")
            model.query("missing"); runCurrent(); assertTrue(model.state.value.sources.isEmpty())
            model.confirm(); assertEquals("AB::one", model.state.value.result!!.scope)
        } finally { store.clear() }
    }
    @Test fun groupAndSourceEmptySelectionAndAllSourcesReturnAllWithoutAccidentalDefaultSelection() = runTest(dispatcher) {
        for (mode in 0..2) {
            val model = SearchScopeViewModel(Fake(), SavedStateHandle()); val store = owned(model)
            try { runCurrent(); if (mode == 1) model.tab(SearchScopeTab.Sources)
                if (mode == 2) { model.group("A"); model.confirm(true) } else model.confirm()
                assertEquals(SearchScopeResult(true, ""), model.state.value.result)
            } finally { store.clear() }
        }
    }
    @Test fun restoreSmallIdentifiersQueryExpandedStateAndBothSelectionsWithoutCachingSourceLists() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val repo = Fake(); val original = SearchScopeViewModel(repo, saved); val oldStore = owned(original)
        original.setActive(true); original.tab(SearchScopeTab.Sources); runCurrent(); original.source("one")
        original.tab(SearchScopeTab.Groups); original.group("B"); original.tab(SearchScopeTab.Sources); original.query("missing"); original.expandSearch()
        val restoredHandle = copy(saved); oldStore.clear()
        val restored = SearchScopeViewModel(repo, restoredHandle); val store = owned(restored)
        try { assertEquals(SearchScopeTab.Sources, restored.state.value.tab); assertEquals("missing", restored.state.value.query)
            assertTrue(restored.state.value.searchExpanded); assertEquals(listOf("B"), restored.state.value.selectedGroups)
            assertEquals(SearchScopeSource("one", "A:B"), restored.state.value.selectedSource)
            assertTrue(restored.state.value.sources.isEmpty()); restored.confirm(); assertEquals("AB::one", restored.state.value.result!!.scope)
        } finally { store.clear() }
    }
    @Test fun sourceObserverOnlyRunsWhenSourceTabAndLifecycleActiveAndCancelsOnPauseOrClear() = runTest(dispatcher) {
        val repo = Fake(); val model = SearchScopeViewModel(repo, SavedStateHandle()); val store = owned(model)
        try { runCurrent(); assertEquals(0, repo.collectors); model.setActive(true); runCurrent(); assertEquals(0, repo.collectors)
            model.tab(SearchScopeTab.Sources); runCurrent(); assertEquals(1, repo.collectors)
            model.setActive(false); runCurrent(); assertEquals(0, repo.collectors)
            model.setActive(true); runCurrent(); assertEquals(1, repo.collectors)
            model.tab(SearchScopeTab.Groups); runCurrent(); assertEquals(0, repo.collectors)
            model.tab(SearchScopeTab.Sources); runCurrent(); store.clear(); runCurrent(); assertEquals(0, repo.collectors)
        } finally { store.clear() }
    }
    @Test fun latestQueryCancelsOldObserverAndDatabaseUpdatesDoNotResetSelection() = runTest(dispatcher) {
        val repo = Fake(); val model = SearchScopeViewModel(repo, SavedStateHandle()); val store = owned(model)
        try { model.setActive(true); model.tab(SearchScopeTab.Sources); runCurrent(); model.source("one")
            model.query("missing"); model.query(""); runCurrent(); assertEquals(1, repo.collectors)
            repo.rows.value = listOf(SearchScopeSource("one", "Renamed")); runCurrent()
            assertEquals("Renamed", model.state.value.sources.single().name); model.confirm()
            assertEquals("AB::one", model.state.value.result!!.scope)
        } finally { store.clear() }
    }
    @Test fun groupAndSourceReadFailuresAllowRetryWithoutClearingDraft() = runTest(dispatcher) {
        val repo = Fake().apply { groupFailure = true }; val model = SearchScopeViewModel(repo, SavedStateHandle()); val store = owned(model)
        try { runCurrent(); assertEquals("groups failed", model.state.value.groupsError); assertFalse(model.state.value.groupsLoading)
            repo.groupFailure = false; model.retry(); runCurrent(); model.group("A")
            repo.sourceFailure = true; model.setActive(true); model.tab(SearchScopeTab.Sources); runCurrent()
            assertEquals("sources failed", model.state.value.sourcesError); assertFalse(model.state.value.sourcesLoading)
            repo.sourceFailure = false; model.retry(); runCurrent(); assertNull(model.state.value.sourcesError)
            assertEquals(listOf("A"), model.state.value.selectedGroups); assertEquals(2, model.state.value.sources.size)
        } finally { store.clear() }
    }
    @Test fun cancelIsTerminalNoConfirmationAndRestoredPendingResultIsConsumedOnce() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val model = SearchScopeViewModel(Fake(), saved); val store = owned(model)
        model.cancel(); model.confirm(true); assertEquals(SearchScopeResult(false, ""), model.state.value.result)
        val restored = SearchScopeViewModel(Fake(), copy(saved)); val other = owned(restored); store.clear()
        try { val event = restored.state.value.result!!; restored.consume(event); restored.confirm(); restored.cancel()
            assertTrue(restored.state.value.finished); assertNull(restored.state.value.result)
        } finally { other.clear() }
    }
    @Test fun consumedConfirmationDoesNotReplayAfterProcessRestorationOrLateLoad() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val repo = Fake().apply { gate = CompletableDeferred() }
        val model = SearchScopeViewModel(repo, saved); val store = owned(model)
        try { runCurrent(); model.confirm(); model.consume(model.state.value.result!!); repo.gate!!.complete(Unit); runCurrent()
            assertTrue(model.state.value.groups.isEmpty()); assertNull(model.state.value.result)
            val restored = SearchScopeViewModel(repo, copy(saved)); val other = owned(restored)
            try { assertTrue(restored.state.value.finished); assertNull(restored.state.value.result) } finally { other.clear() }
        } finally { store.clear() }
    }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun owned(model: SearchScopeViewModel) = ViewModelStore().apply { put("search", model) }
    private class Fake : SearchScopeRepository {
        var groupFailure = false; var sourceFailure = false; var collectors = 0; var gate: CompletableDeferred<Unit>? = null
        val rows = MutableStateFlow(listOf(SearchScopeSource("one", "A:B"), SearchScopeSource("two", "Second")))
        override suspend fun groups(): List<String> { gate?.await(); if (groupFailure) error("groups failed"); return listOf("A", "B") }
        override fun sources(query: String): Flow<List<SearchScopeSource>> = flow {
            if (sourceFailure) error("sources failed")
            collectors++
            try { emitAll(rows.map { if (query == "missing") emptyList() else it }) } finally { collectors-- }
        }
    }
}
