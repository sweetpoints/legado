package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.BookGroupManagementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookGroupManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = BookGroupManagementViewModel(repo, saved)
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun allSystemAndHiddenGroupsRemainVisibleAndShowUpdatesOnlyOneId() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent()
        assertEquals(listOf(-1L, 1L, 2L), model.state.value.groups.map { it.id })
        assertFalse(model.state.value.groups.last().show)
        model.setShown(2, true); runCurrent(); assertEquals(listOf(2L to true), repo.shown)
    }
    @Test fun addAtLimitShowsErrorWithoutEmittingAndCountsOnlyPositiveIds() = runTest(dispatcher) {
        val repo = Fake(); repo.rows.value = (0..62).map { BookGroupEditorSnapshot(1L shl it, "group") } + BookGroupEditorSnapshot(-1) + BookGroupEditorSnapshot(Long.MIN_VALUE)
        val model = model(repo); runCurrent(); model.requestAdd()
        assertFalse(model.consumeAdd()); assertEquals("分组已达上限(63个)", model.state.value.error)
        repo.rows.value = repo.rows.value.filterNot { it.id == 4L }; runCurrent(); model.requestAdd(); assertTrue(model.consumeAdd())
    }
    @Test fun pendingAddRestoresAndIsConsumedExactlyOnce() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.requestAdd(); model.requestAdd()
        val restored = model(repo, snapshot(saved)); runCurrent(); assertTrue(restored.consumeAdd()); assertFalse(restored.consumeAdd())
    }
    @Test fun realReleasePersistsActualOrderOnceIncludingSystemGroups() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent(); model.move(-1, 2)
        assertEquals(listOf(1L, 2L, -1L), model.state.value.groups.map { it.id }); assertTrue(repo.orders.isEmpty())
        model.finishReorder(); model.finishReorder(); runCurrent(); assertEquals(listOf(listOf(1L, 2L, -1L)), repo.orders)
    }
    @Test fun canceledGestureReturnsBaselineAndNeverWrites() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent(); model.move(-1, 2); model.cancelReorder(); model.finishReorder(); runCurrent()
        assertEquals(listOf(-1L, 1L, 2L), model.state.value.groups.map { it.id }); assertTrue(repo.orders.isEmpty())
    }
    @Test fun restoringUnfinishedGestureRestoresBaselineAndNeverCommitsIt() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.move(-1, 2)
        val restored = model(repo, snapshot(saved)); runCurrent(); restored.finishReorder(); runCurrent()
        assertEquals(listOf(-1L, 1L, 2L), restored.state.value.groups.map { it.id }); assertTrue(repo.orders.isEmpty())
    }
    @Test fun pendingCommittedOrderRetriesFailureAndKeepsPreviewWithoutMetadataMutation() = runTest(dispatcher) {
        val repo = Fake().apply { orderFailure = true }
        val saved = SavedStateHandle(mapOf("book.group.management.order" to arrayListOf(2L, -1L, 1L), "book.group.management.committed" to true))
        val model = model(repo, saved); runCurrent(); assertEquals(listOf(2L, -1L, 1L), model.state.value.groups.map { it.id }); assertEquals("order failed", model.state.value.error)
        repo.orderFailure = false; model.retry(); runCurrent(); assertEquals(listOf(listOf(2L, -1L, 1L)), repo.orders)
    }
    @Test fun flowUpdatesDuringDragRetainNewRowsAndDoNotRecreateDeletedRows() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent(); model.move(-1, 2)
        repo.rows.value = listOf(BookGroupEditorSnapshot(-1, "renamed"), BookGroupEditorSnapshot(2, "updated", bookSort = 5), BookGroupEditorSnapshot(4, "new")); runCurrent()
        assertEquals(listOf(2L, -1L, 4L), model.state.value.groups.map { it.id }); assertEquals(5, model.state.value.groups.first().bookSort)
        model.cancelReorder(); assertEquals(listOf(-1L, 2L, 4L), model.state.value.groups.map { it.id }); assertTrue(repo.orders.isEmpty())
    }
    @Test fun closingCancelsUnfinishedOrderAndFinishedRestoreCannotAddOrWrite() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.move(-1, 2); model.close()
        val restored = model(repo, snapshot(saved)); runCurrent(); restored.requestAdd(); restored.setShown(2, true); restored.finishReorder(); runCurrent()
        assertTrue(restored.state.value.finished); assertFalse(restored.consumeAdd()); assertTrue(repo.orders.isEmpty()); assertTrue(repo.shown.isEmpty())
    }
    private class Fake : BookGroupManagementRepository {
        val rows = MutableStateFlow(listOf(BookGroupEditorSnapshot(-1, "all"), BookGroupEditorSnapshot(1, "one"), BookGroupEditorSnapshot(2, "two", show = false)))
        val shown = mutableListOf<Pair<Long, Boolean>>(); val orders = mutableListOf<List<Long>>(); var orderFailure = false
        override fun observe() = rows
        override suspend fun setShown(id: Long, shown: Boolean) { this.shown += id to shown }
        override suspend fun reorder(ids: List<Long>) { if (orderFailure) error("order failed"); orders += ids }
    }
}
