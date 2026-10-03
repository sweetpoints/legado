package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repo : BookshelfManagementRepository {
        val books = MutableStateFlow(listOf("a", "b", "c", "d").mapIndexed { index, id -> ManagedShelfBook(id, id, "", "", false, 1, "Group", index, true) })
        val requests = mutableListOf<Pair<Long, String>>()
        override fun observe(groupId: Long, query: String): Flow<ManagedShelfSnapshot> {
            requests += groupId to query
            return books.map { rows -> ManagedShelfSnapshot(rows.filter { query.isEmpty() || it.name.contains(query) }, listOf(ManagedShelfGroup(1, "Group", 1)), groupId, "Group", 3, false) }
        }
        override suspend fun group(ids: List<String>, group: Long, mode: ShelfGroupMutation) = Unit
        override suspend fun canUpdate(ids: List<String>, enabled: Boolean) = Unit
        override suspend fun order(assignments: List<ShelfOrderAssignment>, resetAll: Boolean) = Unit
        override suspend fun openTitle(value: Boolean) = Unit
    }
    private class Drafts : BookshelfManagementDraftRepository {
        var current = BookshelfManagementDraft(); var failOpen = false; var failWrite = false
        override suspend fun open(session: String): BookshelfManagementDraft { if (failOpen) error("open failure"); return current }
        override suspend fun write(session: String, draft: BookshelfManagementDraft) { if (failWrite) error("write failure"); if (draft.revision >= current.revision) current = draft }
        override suspend fun release(session: String) = Unit
    }
    private inner class Fixture(val repo: Repo = Repo(), val drafts: Drafts = Drafts(), val saved: SavedStateHandle = SavedStateHandle(), group: Long = 8) {
        val vm = BookshelfManagementViewModel(repo, drafts, saved, group)
        val owner = ViewModelStore().apply { put("vm", vm) }
        fun close() { owner.clear() }
    }
    @Test fun selectedIntervalAndInverseKeepHiddenSelectionWhileVisiblePayloadStaysInRowOrder() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("a"); f.vm.toggle("c"); f.vm.selectInterval(); runCurrent(); assertEquals(listOf("a", "b", "c"), f.vm.state.value.visibleSelection)
            f.vm.query("d"); runCurrent(); assertTrue(f.vm.state.value.visibleSelection.isEmpty()); f.vm.inverse(); runCurrent(); assertEquals(listOf("d"), f.vm.state.value.visibleSelection)
            f.vm.query(""); runCurrent(); assertEquals(listOf("a", "b", "c", "d"), f.vm.state.value.visibleSelection)
            f.vm.selectAll(false); runCurrent(); assertTrue(f.drafts.current.selected.isEmpty())
        } finally { f.close() }
    }
    @Test fun slidingRangeReversesAgainstInitialSelectionAndOnlyGestureEndPersists() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("b"); runCurrent(); val original = f.drafts.current
            assertTrue(f.vm.beginSelectionGesture()); f.vm.selectionRange(0, 2); runCurrent()
            assertEquals(listOf("a", "c"), f.vm.state.value.visibleSelection); assertEquals(original, f.drafts.current)
            f.vm.selectionRange(0, 0); runCurrent(); assertEquals(listOf("a", "b"), f.vm.state.value.visibleSelection)
            f.vm.endSelectionGesture(); runCurrent(); assertEquals(listOf("b", "a"), f.drafts.current.selected)
            assertFalse(f.vm.state.value.selecting)
        } finally { f.close() }
    }
    @Test fun processRestoreDuringSelectionGestureRestoresOriginalSelectionAndFlushCannotPersistPartialRange() = runTest(dispatcher) {
        val drafts = Drafts(); val f = Fixture(drafts = drafts)
        try { runCurrent(); f.vm.toggle("b"); runCurrent(); f.vm.beginSelectionGesture(); f.vm.selectionRange(0, 3); f.vm.flush()
            assertEquals(listOf("b"), drafts.current.selected)
            val restored = Fixture(drafts = drafts, saved = SavedStateHandle(mapOf("shelfManageSession" to f.vm.session)))
            try { runCurrent(); assertEquals(listOf("b"), restored.vm.state.value.visibleSelection); assertFalse(restored.vm.state.value.selecting) } finally { restored.close() }
            f.vm.endSelectionGesture(cancel = true); runCurrent(); assertEquals(listOf("b"), f.vm.state.value.visibleSelection)
        } finally { f.close() }
    }
    @Test fun rowReplacementCancelsGestureAndMissingRowCannotBeToggled() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("b"); runCurrent(); f.vm.beginSelectionGesture(); f.vm.selectionRange(0, 2)
            f.repo.books.value = f.repo.books.value.filter { it.id != "a" }; runCurrent(); assertFalse(f.vm.state.value.selecting)
            assertEquals(listOf("b"), f.vm.state.value.visibleSelection); f.vm.toggle("a"); runCurrent(); assertEquals(listOf("b"), f.drafts.current.selected)
        } finally { f.close() }
    }
    @Test fun restoredLargeQueryAndUrlsNeverEnterSavedStateAndRevisionAdvancesBeyondDiskAfterReboot() = runTest(dispatcher) {
        val large = "query".repeat(100000); val drafts = Drafts().apply { current = BookshelfManagementDraft(System.nanoTime() + 1_000_000_000_000, 4, large, listOf("https://source/" + large)) }
        val previous = drafts.current.revision; val f = Fixture(drafts = drafts)
        try { runCurrent(); assertEquals(4L, f.repo.requests.last().first); assertEquals(large, f.vm.state.value.draft!!.query)
            f.saved.keys().forEach { assertFalse(f.saved.get<Any>(it).toString().contains(large)) }
            f.vm.query("new"); runCurrent(); assertTrue(drafts.current.revision > previous); assertEquals("new", drafts.current.query)
        } finally { f.close() }
    }
    @Test fun initializationAndWriteFailuresDisableChangesUntilSuccessfulExplicitRetry() = runTest(dispatcher) {
        val drafts = Drafts().apply { failOpen = true }; val f = Fixture(drafts = drafts)
        try { runCurrent(); assertTrue(f.vm.state.value.failed); f.vm.toggle("a"); assertTrue(drafts.current.selected.isEmpty())
            drafts.failOpen = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.failed); assertEquals(8L, drafts.current.groupId)
            drafts.failWrite = true; f.vm.toggle("a"); runCurrent(); assertTrue(f.vm.state.value.writeFailed); f.vm.toggle("b"); assertEquals(listOf("a"), f.vm.state.value.draft!!.selected)
            drafts.failWrite = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.writeFailed); assertEquals(listOf("a"), drafts.current.selected)
        } finally { f.close() }
    }
}
