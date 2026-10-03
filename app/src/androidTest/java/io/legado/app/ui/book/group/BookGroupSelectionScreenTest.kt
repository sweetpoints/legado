package io.legado.app.ui.book.group

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.BookGroupSelectionRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookGroupSelectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        repo: Fake = Fake(),
        saved: SavedStateHandle = SavedStateHandle(),
        mask: Long = 0,
        code: Int = -1,
        add: () -> Unit = {},
        edit: (BookGroupEditorSnapshot) -> Unit = {},
        result: (BookGroupSelectionResult) -> Unit = {},
        close: () -> Unit = {},
    ): BookGroupSelectionViewModel {
        val model = BookGroupSelectionViewModel(repo, saved, mask, code)
        compose.setContent {
            LegadoComposeTheme { BookGroupSelectionRoute(model, add, edit, result, close) }
        }
        compose.waitUntil { !model.state.value.loading }
        return model
    }

    @Test
    fun confirmingCheckedRowsPreservesHiddenBitsAndOriginalRequestCode() {
        val hidden = Long.MIN_VALUE or (1L shl 40)
        val results = mutableListOf<BookGroupSelectionResult>()
        var closes = 0
        val repo = Fake()
        show(repo, mask = hidden or 1L, code = 7, result = results::add, close = { closes++ })
        compose.onNodeWithTag("book-group-selection-check-1").assertIsOn()
        compose
            .onNodeWithTag("book-group-selection-check-2")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose.onNodeWithTag("book-group-selection-confirm").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(listOf(BookGroupSelectionResult(7, hidden or 3L)), results)
            assertEquals(1, closes)
            assertTrue(repo.orders.isEmpty())
        }
    }

    @Test
    fun cancelAfterChangingCheckmarksClosesWithoutCallbackOrDatabaseWrite() {
        val repo = Fake()
        val results = mutableListOf<BookGroupSelectionResult>()
        var closes = 0
        show(repo, mask = 1, result = results::add, close = { closes++ })
        compose.onNodeWithTag("book-group-selection-name-2").performClick()
        compose.onNodeWithTag("book-group-selection-check-2").assertIsOn()
        compose.onNodeWithTag("book-group-selection-cancel").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertTrue(results.isEmpty())
            assertTrue(repo.orders.isEmpty())
            assertEquals(1, closes)
        }
    }

    @Test
    fun addAndEditDispatchSeparateHostEntriesWithCompleteSnapshot() {
        val repo = Fake()
        var adds = 0
        var edited: BookGroupEditorSnapshot? = null
        show(repo, add = { adds++ }, edit = { edited = it })
        compose.onNodeWithTag("book-group-selection-add").performClick()
        compose.onNodeWithTag("book-group-selection-edit-1").performClick()
        compose.runOnIdle {
            assertEquals(1, adds)
            assertEquals(repo.rows.value[1], edited)
            assertTrue(repo.orders.isEmpty())
        }
    }

    @Test
    fun realLongPressReleaseCommitsCurrentVisibleOrderWithoutChangingMask() {
        val repo = Fake()
        val model = show(repo, mask = 2)
        val start =
            compose.onNodeWithTag("book-group-selection-row-0").fetchSemanticsNode().boundsInRoot
        val end =
            compose.onNodeWithTag("book-group-selection-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("book-group-selection-name-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, end.center.y - start.center.y), 400)
            up()
        }
        compose.waitUntil { repo.orders.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L, 0L), repo.orders.single())
            assertEquals(2L, model.state.value.groupId)
        }
    }

    @Test
    fun syntheticTouchCancelRestoresRowsAndDoesNotCommitOrToggle() {
        val repo = Fake()
        val model = show(repo, mask = 2)
        val start =
            compose.onNodeWithTag("book-group-selection-row-0").fetchSemanticsNode().boundsInRoot
        val end =
            compose.onNodeWithTag("book-group-selection-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("book-group-selection-name-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, end.center.y - start.center.y), 400)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf(0L, 1L, 2L), model.state.value.groups.map { it.id })
            assertEquals(2L, model.state.value.groupId)
            model.finishReorder()
        }
        compose.runOnIdle { assertTrue(repo.orders.isEmpty()) }
    }

    @Test
    fun restoredPendingResultDeliversOnceWithFullMaskWithoutWriting() {
        val repo = Fake()
        val results = mutableListOf<BookGroupSelectionResult>()
        var closes = 0
        val saved =
            SavedStateHandle(
                mapOf(
                    "book.group.selection.mask" to (Long.MIN_VALUE or 2L),
                    "book.group.selection.code" to 123,
                    "book.group.selection.result" to true,
                    "book.group.selection.finished" to true,
                )
            )
        val model = show(repo, saved, result = results::add, close = { closes++ })
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(listOf(BookGroupSelectionResult(123, Long.MIN_VALUE or 2L)), results)
            assertNull(model.consumeResult())
            assertTrue(repo.orders.isEmpty())
            assertEquals(1, closes)
        }
    }

    @Test
    fun consumedFinishedRestoreOnlyClosesWithoutDuplicateCallback() {
        val results = mutableListOf<BookGroupSelectionResult>()
        var closes = 0
        show(
            saved =
                SavedStateHandle(
                    mapOf(
                        "book.group.selection.finished" to true,
                        "book.group.selection.result" to false,
                    )
                ),
            result = results::add,
            close = { closes++ },
        )
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertTrue(results.isEmpty())
            assertEquals(1, closes)
        }
    }

    @Test
    fun selectionDraftAndUnfinishedDragRestoreCheckedStateAndBaselineWithoutWrite() {
        val repo = Fake()
        val saved =
            SavedStateHandle(
                mapOf(
                    "book.group.selection.mask" to 2L,
                    "book.group.selection.baseline" to arrayListOf(0L, 1L, 2L),
                    "book.group.selection.order" to arrayListOf(1L, 2L, 0L),
                    "book.group.selection.committed" to false,
                )
            )
        val model = show(repo, saved)
        compose.onNodeWithTag("book-group-selection-check-2").assertIsOn()
        val first =
            compose
                .onNodeWithTag("book-group-selection-row-0")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val last =
            compose
                .onNodeWithTag("book-group-selection-row-2")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue(first < last)
        compose.runOnIdle {
            model.finishReorder()
            assertTrue(repo.orders.isEmpty())
        }
    }

    @Test
    fun callbackWaitsForResumedAndCannotRepeatAcrossPauseResume() {
        val owner = Owner()
        val repo = Fake()
        lateinit var model: BookGroupSelectionViewModel
        val results = mutableListOf<BookGroupSelectionResult>()
        var closes = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            model = BookGroupSelectionViewModel(repo, SavedStateHandle(), 2, 19)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookGroupSelectionRoute(model, {}, {}, results::add, { closes++ })
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("book-group-selection-confirm").performClick()
        compose.runOnIdle {
            assertTrue(results.isEmpty())
            assertEquals(0, closes)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(BookGroupSelectionResult(19, 2)), results)
            assertEquals(1, closes)
            assertTrue(repo.orders.isEmpty())
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : BookGroupSelectionRepository {
        val rows =
            MutableStateFlow(
                listOf(
                    BookGroupEditorSnapshot(0, "zero"),
                    BookGroupEditorSnapshot(1, "one", "cover", 42, false, true, 5, true),
                    BookGroupEditorSnapshot(2, "two", show = false),
                )
            )
        val orders = mutableListOf<List<Long>>()

        override fun observe() = rows

        override suspend fun reorder(ids: List<Long>) {
            orders += ids
        }
    }
}
