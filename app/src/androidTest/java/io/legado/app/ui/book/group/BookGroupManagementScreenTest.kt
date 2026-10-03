package io.legado.app.ui.book.group

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.R
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.BookGroupManagementRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookGroupManagementScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        repo: Fake = Fake(),
        saved: SavedStateHandle = SavedStateHandle(),
        add: () -> Unit = {},
        edit: (BookGroupEditorSnapshot) -> Unit = {},
        close: () -> Unit = {},
    ): BookGroupManagementViewModel {
        val model = BookGroupManagementViewModel(repo, saved)
        compose.setContent {
            LegadoComposeTheme { BookGroupManagementRoute(model, add, edit, close) }
        }
        compose.waitUntil { !model.state.value.loading }
        return model
    }

    @Test
    fun systemDisplayNamesAndHiddenGroupsRemainEditableAndSwitchUpdatesIdentity() {
        val repo = Fake()
        var edited: BookGroupEditorSnapshot? = null
        show(repo, edit = { edited = it })
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose
            .onNodeWithTag("book-group-management-name--1")
            .assertTextEquals("all(" + context.getString(R.string.all) + ")")
        compose.onNodeWithTag("book-group-management-shown-2").assertIsOff().performClick()
        compose.waitUntil { repo.shown.isNotEmpty() }
        compose.onNodeWithTag("book-group-management-edit-1").performClick()
        compose.runOnIdle {
            assertEquals(repo.rows.value[1], edited)
            assertEquals(listOf(2L to true), repo.shown)
        }
    }

    @Test
    fun toolbarAddDispatchesOnceAndCloseDoesNotWrite() {
        val repo = Fake()
        var adds = 0
        var closes = 0
        show(repo, add = { adds++ }, close = { closes++ })
        compose.onNodeWithTag("book-group-management-add").performClick()
        compose.waitUntil { adds > 0 }
        compose.onNodeWithTag("book-group-management-close").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(1, adds)
            assertEquals(1, closes)
            assertTrue(repo.orders.isEmpty())
            assertTrue(repo.shown.isEmpty())
        }
    }

    @Test
    fun reaching63GroupsShowsLimitAndDoesNotOpenEditor() {
        val repo = Fake()
        repo.rows.value = (0..62).map { BookGroupEditorSnapshot(1L shl it, "group $it") }
        var adds = 0
        show(repo, add = { adds++ })
        compose.onNodeWithTag("book-group-management-add").performClick()
        compose.onNodeWithTag("book-group-management-error").assertTextEquals("分组已达上限(63个)")
        compose.runOnIdle { assertEquals(0, adds) }
    }

    @Test
    fun realLongPressReleasePersistsActualOrderIncludingSystemGroup() {
        val repo = Fake()
        show(repo)
        val start =
            compose.onNodeWithTag("book-group-management-row--1").fetchSemanticsNode().boundsInRoot
        val end =
            compose.onNodeWithTag("book-group-management-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("book-group-management-name--1").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, end.center.y - start.center.y), 400)
            up()
        }
        compose.waitUntil { repo.orders.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(1L, 2L, -1L), repo.orders.single()) }
    }

    @Test
    fun syntheticTouchCancelRestoresBaselineAndNeverCommitsOrder() {
        val repo = Fake()
        val model = show(repo)
        val start =
            compose.onNodeWithTag("book-group-management-row--1").fetchSemanticsNode().boundsInRoot
        val end =
            compose.onNodeWithTag("book-group-management-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("book-group-management-name--1").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, end.center.y - start.center.y), 400)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf(-1L, 1L, 2L), model.state.value.groups.map { it.id })
            model.finishReorder()
        }
        compose.runOnIdle { assertTrue(repo.orders.isEmpty()) }
    }

    @Test
    fun processRestoredMidGestureShowsOriginalOrderWithoutWriting() {
        val repo = Fake()
        val saved =
            SavedStateHandle(
                mapOf(
                    "book.group.management.baseline" to arrayListOf(-1L, 1L, 2L),
                    "book.group.management.order" to arrayListOf(1L, 2L, -1L),
                    "book.group.management.committed" to false,
                )
            )
        val model = show(repo, saved)
        val first =
            compose
                .onNodeWithTag("book-group-management-row--1")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val last =
            compose
                .onNodeWithTag("book-group-management-row-2")
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
    fun accessibilityMoveCommitsExplicitOrder() {
        val repo = Fake()
        show(repo)
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val actions =
            compose
                .onNodeWithTag("book-group-management-name-1")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(
                actions
                    .first { it.label == context.getString(R.string.book_group_move_up) }
                    .action()
            )
        }
        compose.waitUntil { repo.orders.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(1L, -1L, 2L), repo.orders.single()) }
    }

    @Test
    fun pendingAddRestoresOnceWhileFinishedRestoreClosesWithoutAdd() {
        val repo = Fake()
        var adds = 0
        val model =
            show(
                repo,
                SavedStateHandle(mapOf("book.group.management.add" to true)),
                add = { adds++ },
            )
        compose.waitUntil { adds > 0 }
        compose.onNodeWithTag("book-group-management-shown-2").performClick()
        compose.waitUntil { repo.shown.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, adds)
            assertFalse(model.consumeAdd())
        }
    }

    @Test
    fun finishedRestoreOnlyClosesAndDoesNotEmitStaleAddOrWrite() {
        val repo = Fake()
        var adds = 0
        var closes = 0
        show(
            repo,
            SavedStateHandle(
                mapOf("book.group.management.finished" to true, "book.group.management.add" to true)
            ),
            add = { adds++ },
            close = { closes++ },
        )
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(0, adds)
            assertEquals(1, closes)
            assertTrue(repo.orders.isEmpty())
            assertTrue(repo.shown.isEmpty())
        }
    }

    private class Fake : BookGroupManagementRepository {
        val rows =
            MutableStateFlow(
                listOf(
                    BookGroupEditorSnapshot(-1, "all"),
                    BookGroupEditorSnapshot(1, "one", "cover", 42, false, true, 5, true),
                    BookGroupEditorSnapshot(2, "two", show = false),
                )
            )
        val shown = mutableListOf<Pair<Long, Boolean>>()
        val orders = mutableListOf<List<Long>>()

        override fun observe() = rows

        override suspend fun setShown(id: Long, shown: Boolean) {
            this.shown += id to shown
        }

        override suspend fun reorder(ids: List<Long>) {
            orders += ids
        }
    }
}
