package io.legado.app.ui.book.manage

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.model.bookshelf.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BookshelfManagementComposeTest {
    @get:Rule val compose = createComposeRule()
    private fun initial(openTitle: Boolean = true) = BookshelfManagementState(loading = false,
        snapshot = ManagedShelfSnapshot(listOf(ManagedShelfBook("a", "Alpha", "Author", "Origin", false, 4, "Fiction", 0, true)),
            listOf(ManagedShelfGroup(4, "Fiction", 0)), 4, "Fiction", 3, openTitle), draft = BookshelfManagementDraft(selected = listOf("a")))
    private fun actions() = BookshelfManagementActions(back = {}, query = {}, group = {}, toggle = {}, all = {}, inverse = {}, interval = {},
        action = {}, rowDelete = {}, rowGroup = { _, _ -> }, open = {}, groups = {}, openTitle = {}, retry = {}, retryOperation = {},
        dismiss = {}, confirm = {}, original = {}, cron = { _, _, _ -> }, cancelOperation = {}, beginSelection = { false },
        selectionRange = { _, _ -> }, finishSelection = {}, beginDrag = { false }, dragTo = {}, finishDrag = {}, cancelGesture = {}, closeExport = {}, copyExport = {})
    private fun render(state: BookshelfManagementState = initial(), callbacks: BookshelfManagementActions = actions()) {
        compose.setContent { LegadoComposeTheme { BookshelfManagementScreen(state, callbacks) } }
    }
    @Test fun rowUsesStableIdAndIndependentSelectionTitleGroupAndDeleteTargets() {
        val toggled = mutableListOf<String>(); val opened = mutableListOf<String>(); val deleted = mutableListOf<String>(); val groups = mutableListOf<Pair<String, Long>>()
        render(callbacks = actions().copy(toggle = { toggled += it }, open = { opened += it }, rowDelete = { deleted += it }, rowGroup = { id, group -> groups += id to group }))
        compose.onNodeWithTag("shelf-manage-selected-a").assertIsOn().performClick()
        compose.onNodeWithTag("shelf-manage-title-a").performClick()
        compose.onNodeWithTag("shelf-manage-row-group-a").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("shelf-manage-row-delete-a").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("a"), toggled); assertEquals(listOf("a"), opened); assertEquals(listOf("a"), deleted); assertEquals(listOf("a" to 4L), groups)
        compose.onNodeWithText("Author").assertExists(); compose.onNodeWithText("Origin").assertExists()
    }
    @Test fun titleWithoutOpenPreferenceTogglesItsParentSelection() {
        val toggled = mutableListOf<String>(); var opened = false
        render(initial(false), actions().copy(toggle = { toggled += it }, open = { opened = true }))
        compose.onNodeWithTag("shelf-manage-title-a").performClick(); assertEquals(listOf("a"), toggled); assertFalse(opened)
    }
    @Test fun groupAndMainMenusDispatchStableGroupExportAndPreference() {
        var group = 0L; val selected = mutableListOf<ShelfManagementAction>(); var enabled = true
        render(callbacks = actions().copy(group = { group = it }, action = { selected += it }, openTitle = { enabled = it }))
        compose.onNodeWithTag("shelf-manage-groups").performClick(); compose.onNodeWithTag("shelf-manage-group-4").performClick(); assertEquals(4L, group)
        compose.onNodeWithTag("shelf-manage-more").performClick(); compose.onNodeWithTag("shelf-manage-export").performClick(); assertEquals(listOf(ShelfManagementAction.ExportSources), selected)
        compose.onNodeWithTag("shelf-manage-more").performClick(); compose.onNodeWithTag("shelf-manage-open-title").performClick(); assertFalse(enabled)
    }
    @Test fun allInverseAndIntervalRemainIndependentOfBatchAction() {
        var all: Boolean? = null; var inverse = 0; var interval = 0
        render(callbacks = actions().copy(all = { all = it }, inverse = { inverse++ }, interval = { interval++ }))
        compose.onNodeWithTag("shelf-manage-all").performClick(); assertEquals(false, all)
        compose.onNodeWithTag("shelf-manage-selection-menu").performClick(); compose.onNodeWithTag("shelf-manage-inverse").performClick(); assertEquals(1, inverse)
        compose.onNodeWithTag("shelf-manage-selection-menu").performClick(); compose.onNodeWithTag("shelf-manage-interval").performScrollTo().performClick(); assertEquals(1, interval)
    }
    @Test fun everyBatchActionDispatchesItsTypedContractInOriginalOrder() {
        val received = mutableListOf<ShelfManagementAction>()
        render(callbacks = actions().copy(action = { received += it }))
        shelfSelectionActions.forEach { action ->
            compose.onNodeWithTag("shelf-manage-selection-menu").performClick()
            compose.onNodeWithTag("shelf-manage-action-${action.name}").performScrollTo().performClick()
        }
        assertEquals(listOf(ShelfManagementAction.Delete, ShelfManagementAction.EnableUpdate, ShelfManagementAction.DisableUpdate,
            ShelfManagementAction.GroupAdd, ShelfManagementAction.GroupRemove, ShelfManagementAction.ChangeSource, ShelfManagementAction.ClearCache,
            ShelfManagementAction.PersistCovers, ShelfManagementAction.RestoreNetworkCovers, ShelfManagementAction.RestoreSourceCovers,
            ShelfManagementAction.UpdateToc, ShelfManagementAction.CreateTasks), received)
    }
    @Test fun remoteSingleDeleteHidesOriginalFileButBatchConfirmationRetainsPolicy() {
        var state by mutableStateOf(initial().copy(draft = BookshelfManagementDraft(confirmation = ShelfManagementConfirmation(ShelfManagementAction.Delete, listOf("a"), showOriginal = false))))
        var original: Boolean? = null; var confirms = 0
        compose.setContent { LegadoComposeTheme { BookshelfManagementScreen(state, actions().copy(original = { original = it }, confirm = { confirms++ })) } }
        compose.onNodeWithTag("shelf-manage-delete-original").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(draft = state.draft!!.copy(confirmation = state.draft!!.confirmation!!.copy(showOriginal = true))) }
        compose.onNodeWithTag("shelf-manage-delete-original").performClick(); assertEquals(true, original)
        compose.onNodeWithTag("shelf-manage-confirm").performClick(); assertEquals(1, confirms)
    }
    @Test fun privateCronEditorAndExportResultDispatchExactTextWithoutResubmittingOperation() {
        var state by mutableStateOf(initial().copy(draft = BookshelfManagementDraft(confirmation = ShelfManagementConfirmation(ShelfManagementAction.CreateTasks, listOf("a")))))
        var cron = ""; var copied = ""; var closed = 0
        compose.setContent { LegadoComposeTheme { BookshelfManagementScreen(state, actions().copy(cron = { text, _, _ -> cron = text }, copyExport = { copied = it }, closeExport = { closed++ })) } }
        compose.onNodeWithTag("shelf-manage-cron").performTextReplacement("0 * * * *"); assertEquals("0 * * * *", cron)
        compose.runOnIdle { state = state.copy(draft = BookshelfManagementDraft(exportResult = "https://example.invalid/file", exportSummary = "summary")) }
        compose.onNodeWithText("summary").assertExists(); compose.onNodeWithTag("shelf-manage-export-copy").performClick()
        assertEquals("https://example.invalid/file", copied); assertEquals(1, closed)
    }
    @Test fun acceptedCommitBlocksControlsAndOnlyCancelableWorkOffersCancel() {
        var state by mutableStateOf(initial().copy(busy = true, draft = BookshelfManagementDraft(operation = ShelfManagementOperation("op", ShelfManagementAction.PersistCovers, listOf("a")))))
        var canceled = 0
        compose.setContent { LegadoComposeTheme { BookshelfManagementScreen(state, actions().copy(cancelOperation = { canceled++ })) } }
        compose.onNodeWithTag("shelf-manage-cancel-operation").performClick(); assertEquals(1, canceled)
        compose.runOnIdle { state = state.copy(busy = false, pendingCommit = true) }
        compose.onNodeWithTag("shelf-manage-cancel-operation").assertDoesNotExist()
    }
    @Test fun realLongPressDragConsumesFinalUpWithoutClickingRowOrLosingOrderCallback() {
        val initial = initial(); val snapshot = initial.snapshot!!; val books = snapshot.books + snapshot.books.single().copy(id = "b", name = "Beta", order = 1)
        var started = 0; var finished = 0; var toggled = 0; val targets = mutableListOf<String>()
        render(initial.copy(snapshot = snapshot.copy(books = books)), actions().copy(beginDrag = { started++; true },
            dragTo = { targets += it }, finishDrag = { finished++ }, toggle = { toggled++ }))
        compose.onNodeWithTag("shelf-manage-row-a").performTouchInput {
            down(Offset(width * .7f, height * .35f)); advanceEventTime(700)
            moveBy(Offset(0f, height.toFloat())); advanceEventTime(50); up()
        }
        compose.waitForIdle(); assertEquals(1, started); assertEquals(1, finished); assertEquals(0, toggled); assertTrue(targets.contains("b"))
    }

}
