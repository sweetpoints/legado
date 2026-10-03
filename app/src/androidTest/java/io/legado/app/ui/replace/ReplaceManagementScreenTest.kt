package io.legado.app.ui.replace

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.ReplaceManagementRow
import io.legado.app.data.repository.ReplaceManagementShareFeedback
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class ReplaceManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows =
        (1L..100L).map {
            ReplaceManagementRow(it, "Rule $it", "Rule $it(A)", "A", true, it.toInt())
        }

    private fun actions(
        selected: (Long, Boolean) -> Unit = { _, _ -> },
        invert: () -> Unit = {},
        edge: (List<Long>, Boolean) -> Unit = { _, _ -> },
        dialog: (ReplaceManagementDialog, List<Long>) -> Unit = { _, _ -> },
        effect: (ReplaceManagementAction, Long?) -> Unit = { _, _ -> },
        copy: () -> Unit = {},
        manual: () -> Unit = {},
        passphrase: () -> Unit = {},
        range: (Long, Long) -> Unit = { _, _ -> },
        finishSelection: () -> Unit = {},
        cancelGesture: () -> Unit = {},
        scroll: (Int, Int) -> Unit = { _, _ -> },
    ) =
        ReplaceManagementActions(
            {},
            { _, _, _ -> },
            selected,
            {},
            invert,
            {},
            { _, _ -> },
            edge,
            dialog,
            { _, _, _ -> },
            {},
            {},
            manual,
            effect,
            {},
            passphrase,
            copy,
            {},
            { true },
            range,
            finishSelection,
            { true },
            { _, _ -> },
            {},
            cancelGesture,
            scroll,
        )

    private fun show(state: ReplaceManagementState, actions: ReplaceManagementActions = actions()) {
        compose.setContent { LegadoComposeTheme { ReplaceManagementScreen(state, actions) } }
    }

    @Test
    fun selectionCountIgnoresHiddenSelectionAndAllSelectedUsesOriginalReverse() {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        var inverse = 0
        show(
            ReplaceManagementState(
                loaded = true,
                rows = rows.take(2),
                selected = setOf(1L, 2L, 999L),
            ),
            actions(selected = { id, checked -> calls += id to checked }, invert = { inverse++ }),
        )
        compose.onNodeWithText("Rule 1(A)").performClick()
        compose.onNodeWithTag("replace-rule-all").performClick()
        assertEquals(listOf(1L to false), calls)
        assertEquals(1, inverse)
        compose.onNodeWithTag("replace-rule-count").assertTextEquals("2/2")
    }

    @Test
    fun rowPopupOrderAndTargetsAreTopBottomDeleteAndLongNamesDoNotHideControls() {
        val edges = mutableListOf<Pair<List<Long>, Boolean>>()
        val dialogs = mutableListOf<Pair<ReplaceManagementDialog, List<Long>>>()
        show(
            ReplaceManagementState(loaded = true, rows = rows.take(1)),
            actions(
                edge = { ids, top -> edges += ids to top },
                dialog = { kind, ids -> dialogs += kind to ids },
            ),
        )
        fun open() = compose.onNodeWithTag("replace-rule-menu-1").performClick()
        open()
        val top = compose.onNodeWithTag("replace-rule-top-1").fetchSemanticsNode().boundsInRoot.top
        val bottom =
            compose.onNodeWithTag("replace-rule-bottom-1").fetchSemanticsNode().boundsInRoot.top
        val delete =
            compose.onNodeWithTag("replace-rule-delete-1").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < bottom && bottom < delete)
        compose.onNodeWithTag("replace-rule-top-1").performClick()
        open()
        compose.onNodeWithTag("replace-rule-bottom-1").performClick()
        open()
        compose.onNodeWithTag("replace-rule-delete-1").performClick()
        assertEquals(listOf(listOf(1L) to true, listOf(1L) to false), edges)
        assertEquals(listOf(ReplaceManagementDialog.Delete to listOf(1L)), dialogs)
    }

    @Test
    fun overflowOrderKeepsManualBeforeHelpAndItsCheckedStateAndLocalizedText() {
        var manual = 0
        show(ReplaceManagementState(loaded = true, manual = true), actions(manual = { manual++ }))
        compose.onNodeWithTag("replace-rule-more").performClick()
        val tags =
            listOf(
                "replace-rule-add",
                "replace-rule-import-local",
                "replace-rule-import-url",
                "replace-rule-import-qr",
                "replace-rule-manual",
            )
        val y = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(y.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithTag("replace-rule-manual-state").assertIsOn()
        compose.onNodeWithText(context.getString(R.string.manual_replace_rule)).assertExists()
        compose.onNodeWithText(context.getString(R.string.help)).performScrollTo().assertExists()
        compose.onNodeWithTag("replace-rule-manual").performClick()
        assertEquals(1, manual)
    }

    @Test
    fun selectionMenuKeepsAllEightOriginalOperationsInOrder() {
        show(ReplaceManagementState(loaded = true, rows = rows.take(1), selected = setOf(1L)))
        compose.onNodeWithTag("replace-rule-selection-menu").performClick()
        val labels =
            listOf(
                R.string.enable_selection,
                R.string.disable_selection,
                R.string.add_group,
                R.string.remove_group,
                R.string.selection_to_top,
                R.string.selection_to_bottom,
                R.string.export_selection,
                R.string.share_selected_source,
            )
        labels.forEach {
            compose.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed()
        }
        compose
            .onNodeWithText(context.getString(R.string.check_selected_interval))
            .assertDoesNotExist()
    }

    @Test
    fun savedScrollWaitsForRowsAndRestoresToRequestedItemWithoutPublishingZero() {
        var state by mutableStateOf(ReplaceManagementState(scrollIndex = 40, scrollOffset = 9))
        val observed = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                ReplaceManagementScreen(state, actions(scroll = { index, _ -> observed += index }))
            }
        }
        compose.waitForIdle()
        assertTrue(observed.isEmpty())
        compose.runOnIdle { state = state.copy(loaded = true, rows = rows) }
        compose.onNodeWithTag("replace-rule-row-41").assertIsDisplayed()
        assertFalse(observed.contains(0))
    }

    @Test
    fun exportFeedbackKeepsCopyAndPassphraseActionsAndLoadingDisablesEditing() {
        var copies = 0
        var phrases = 0
        show(
            ReplaceManagementState(
                loaded = true,
                dialog = ReplaceManagementDialog.ExportResult,
                feedback = ReplaceManagementShareFeedback("https://exact", "Expiry metadata", true),
                draft = "https://exact",
            ),
            actions(copy = { copies++ }, passphrase = { phrases++ }),
        )
        compose.onNodeWithText("Expiry metadata").assertIsDisplayed()
        compose.onNodeWithTag("replace-rule-passphrase").performClick()
        compose.onNodeWithTag("replace-rule-dialog-confirm").performClick()
        assertEquals(1, copies)
        assertEquals(1, phrases)
    }

    @Test
    fun slideSelectionCompletesInsideOriginalAreaAndConsumesUpWithoutCheckboxToggle() {
        var finishes = 0
        var toggles = 0
        val ranges = mutableListOf<Pair<Long, Long>>()
        show(
            ReplaceManagementState(loaded = true, rows = rows.take(3)),
            actions(
                selected = { _, _ -> toggles++ },
                range = { first, last -> ranges += first to last },
                finishSelection = { finishes++ },
            ),
        )
        val list = compose.onNodeWithTag("replace-rule-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("replace-rule-row-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("replace-rule-row-2").fetchSemanticsNode().boundsInRoot
        val x = 32f * context.resources.displayMetrics.density
        compose.onNodeWithTag("replace-rule-list").performTouchInput {
            down(Offset(x, first.center.y - list.top))
            moveTo(Offset(x, second.center.y - list.top), 200)
            up()
        }
        assertTrue(ranges.contains(1L to 2L))
        assertEquals(1, finishes)
        assertEquals(0, toggles)
    }

    @Test
    fun canceledSlideNeverFinishesSelectionOrClicksCheckbox() {
        var finishes = 0
        var cancels = 0
        var toggles = 0
        show(
            ReplaceManagementState(loaded = true, rows = rows.take(3)),
            actions(
                selected = { _, _ -> toggles++ },
                finishSelection = { finishes++ },
                cancelGesture = { cancels++ },
            ),
        )
        val list = compose.onNodeWithTag("replace-rule-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("replace-rule-row-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("replace-rule-row-2").fetchSemanticsNode().boundsInRoot
        val x = 32f * context.resources.displayMetrics.density
        compose.onNodeWithTag("replace-rule-list").performTouchInput {
            down(Offset(x, first.center.y - list.top))
            moveTo(Offset(x, second.center.y - list.top), 200)
            cancel()
        }
        assertTrue(cancels > 0)
        assertEquals(0, finishes)
        assertEquals(0, toggles)
    }
}
