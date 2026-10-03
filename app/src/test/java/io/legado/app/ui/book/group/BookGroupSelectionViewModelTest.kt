package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.BookGroupSelectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookGroupSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun close() {
        Dispatchers.resetMain()
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        mask: Long = 0,
        code: Int = -1,
    ) = BookGroupSelectionViewModel(repo, saved, mask, code)

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun checkboxUpdatesAreIdempotentAndKeepUnknownAndSignBits() =
        runTest(dispatcher) {
            val repo = Fake()
            val hidden = Long.MIN_VALUE or (1L shl 40)
            val model = model(repo, mask = hidden or 1L)
            runCurrent()
            assertTrue(model.state.value.checked(1))
            assertFalse(model.state.value.checked(2))
            assertFalse(model.state.value.checked(0))
            model.setChecked(2, true)
            model.setChecked(2, true)
            assertEquals(hidden or 3L, model.state.value.groupId)
            model.setChecked(1, false)
            model.setChecked(1, false)
            assertEquals(hidden or 2L, model.state.value.groupId)
            model.setChecked(0, true)
            model.setChecked(99, true)
            assertEquals(hidden or 2L, model.state.value.groupId)
        }

    @Test
    fun flowDeletionDoesNotSilentlyClearPreviouslySelectedBits() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo, mask = 1L or 2L)
            runCurrent()
            repo.rows.value = listOf(BookGroupEditorSnapshot(0), BookGroupEditorSnapshot(2))
            runCurrent()
            assertEquals(3L, model.state.value.groupId)
            model.setChecked(2, false)
            model.confirm()
            assertEquals(BookGroupSelectionResult(-1, 1), model.consumeResult())
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun selectionAndRequestCodeRestoreInsteadOfNewConstructorArguments() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved, 1L shl 40, 123)
            runCurrent()
            model.setChecked(2, true)
            val restored = model(repo, snapshot(saved), 0, -1)
            runCurrent()
            assertEquals((1L shl 40) or 2L, restored.state.value.groupId)
            assertEquals(123, restored.state.value.requestCode)
        }

    @Test
    fun confirmProducesOneResultAndFinishedSnapshotCannotRedeliverAfterConsumption() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved, 3, 7)
            runCurrent()
            model.confirm()
            model.confirm()
            val restoredSaved = snapshot(saved)
            val restored = model(repo, restoredSaved)
            runCurrent()
            assertEquals(BookGroupSelectionResult(7, 3), restored.consumeResult())
            assertNull(restored.consumeResult())
            assertTrue(restored.state.value.finished)
            val consumed = model(repo, snapshot(restoredSaved))
            runCurrent()
            consumed.confirm()
            assertTrue(consumed.state.value.finished)
            assertNull(consumed.consumeResult())
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun confirmBeforeRowsArriveKeepsInitialBitsWithoutAnyDatabaseWrite() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo, mask = Long.MIN_VALUE or 4L, code = 8)
            model.confirm()
            runCurrent()
            assertEquals(BookGroupSelectionResult(8, Long.MIN_VALUE or 4L), model.consumeResult())
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun cancelAndRestoredCancelNeverDeliverSelection() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved, 1, 9)
            runCurrent()
            model.setChecked(2, true)
            model.close()
            val restored = model(repo, snapshot(saved))
            runCurrent()
            restored.confirm()
            assertNull(restored.consumeResult())
            assertTrue(restored.state.value.finished)
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun normalDragReleaseCommitsOnceWithoutChangingSelection() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo, mask = 2)
            runCurrent()
            model.move(0, 2)
            model.finishReorder()
            model.finishReorder()
            runCurrent()
            assertEquals(listOf(listOf(1L, 2L, 0L)), repo.orders)
            assertEquals(2L, model.state.value.groupId)
        }

    @Test
    fun cancelAndProcessRestoreOfUnfinishedDragRestoreBaselineWithoutWrite() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved, 2)
            runCurrent()
            model.move(0, 2)
            val restored = model(repo, snapshot(saved))
            runCurrent()
            restored.finishReorder()
            runCurrent()
            assertEquals(listOf(0L, 1L, 2L), restored.state.value.groups.map { it.id })
            assertEquals(2L, restored.state.value.groupId)
            assertTrue(repo.orders.isEmpty())
            model.cancelReorder()
            model.finishReorder()
            runCurrent()
            assertEquals(listOf(0L, 1L, 2L), model.state.value.groups.map { it.id })
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun failedCommittedOrderKeepsPreviewAndCanRetryWithoutTouchingMask() =
        runTest(dispatcher) {
            val repo = Fake().apply { fail = true }
            val model = model(repo, mask = 2)
            runCurrent()
            model.move(0, 2)
            model.finishReorder()
            runCurrent()
            assertEquals("order failed", model.state.value.error)
            assertEquals(listOf(1L, 2L, 0L), model.state.value.groups.map { it.id })
            repo.fail = false
            model.retry()
            runCurrent()
            assertEquals(listOf(listOf(1L, 2L, 0L)), repo.orders)
            assertEquals(2L, model.state.value.groupId)
        }

    @Test
    fun newlyAddedRowsAndDeletedRowsAreSafelyMergedDuringPreview() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo, mask = 1)
            runCurrent()
            model.move(0, 2)
            repo.rows.value =
                listOf(
                    BookGroupEditorSnapshot(0),
                    BookGroupEditorSnapshot(2),
                    BookGroupEditorSnapshot(4),
                )
            runCurrent()
            assertEquals(listOf(2L, 0L, 4L), model.state.value.groups.map { it.id })
            assertEquals(1L, model.state.value.groupId)
            model.cancelReorder()
            assertEquals(listOf(0L, 2L, 4L), model.state.value.groups.map { it.id })
            assertTrue(repo.orders.isEmpty())
        }

    private class Fake : BookGroupSelectionRepository {
        val rows =
            MutableStateFlow(
                listOf(
                    BookGroupEditorSnapshot(0, "zero"),
                    BookGroupEditorSnapshot(1, "one"),
                    BookGroupEditorSnapshot(2, "two", show = false),
                )
            )
        val orders = mutableListOf<List<Long>>()
        var fail = false

        override fun observe() = rows

        override suspend fun reorder(ids: List<Long>) {
            if (fail) error("order failed")
            orders += ids
        }
    }
}
