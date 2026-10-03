package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.TxtTocRuleImportItem
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportTxtTocRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items =
        listOf(
            TxtTocRuleImportItem("new", "New rule", "{}", false),
            TxtTocRuleImportItem("update", "Existing edited rule", "{}", true),
            TxtTocRuleImportItem("existing", "Stored rule", "{}", true),
        )

    @Test
    fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by mutableStateOf(ImportTxtTocRuleState(items, setOf("new"), loading = false))
        compose.setContent {
            Content(
                state,
                toggle = { key ->
                    state =
                        state.copy(
                            selected =
                                if (key in state.selected) state.selected - key
                                else state.selected + key
                        )
                },
            )
        }
        compose.onNodeWithTag("toc-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(2, state.selectCount) }
        compose.onNodeWithTag("toc-import-row-existing").performClick()
        compose.onNodeWithTag("toc-import-check-existing").assertIsOff()
        compose.onNodeWithText(context.getString(R.string.import_status_new)).assertExists()
        compose
            .onAllNodesWithText(context.getString(R.string.import_status_exist))
            .assertCountEquals(2)
    }

    @Test
    fun selectAllCountsAndCodeAreIndependentFromRowSelection() {
        var state by mutableStateOf(ImportTxtTocRuleState(items, setOf("new"), loading = false))
        val edits = mutableListOf<String>()
        compose.setContent {
            Content(
                state,
                all = {
                    state =
                        state.copy(
                            selected =
                                if (state.isSelectAll) emptySet()
                                else items.mapTo(mutableSetOf()) { it.key }
                        )
                },
                code = { edits += it },
            )
        }
        compose
            .onNodeWithTag("toc-import-select-all")
            .assertTextEquals(context.getString(R.string.select_all_count, 1, 3))
            .performClick()
            .assertTextEquals(context.getString(R.string.select_cancel_count, 3, 3))
        compose.onNodeWithTag("toc-import-code-existing").performClick()
        compose.runOnIdle {
            assertEquals(listOf("existing"), edits)
            assertEquals(3, state.selectCount)
        }
        compose.onNodeWithTag("toc-import-select-all").performClick()
        compose.runOnIdle { assertEquals(0, state.selectCount) }
    }

    @Test
    fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(ImportTxtTocRuleState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("toc-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("toc-import-code-new").assertIsNotEnabled()
        compose.onNodeWithTag("toc-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("toc-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("toc-import-progress").assertExists()
    }

    @Test
    fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0
        var cancels = 0
        compose.setContent {
            Content(
                ImportTxtTocRuleState(loading = false, error = "ImportError:format"),
                retry = { retries++ },
                cancel = { cancels++ },
            )
        }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("toc-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("toc-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many = List(30) { TxtTocRuleImportItem("$it", "Rule $it", "{}", false) }
        var confirms = 0
        compose.setContent {
            Content(
                ImportTxtTocRuleState(many, loading = false),
                confirm = { confirms++ },
                height = 280,
            )
        }
        compose.onNodeWithTag("toc-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Rule 29").assertIsDisplayed()
        compose.onNodeWithTag("toc-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun actualExampleClickExpandsFrom3To39LinesWithoutSelectingTheRowAndCanCollapse() {
        val example = (1..45).joinToString("\n") { "Line $it" }
        var state by
            mutableStateOf(
                ImportTxtTocRuleState(
                    listOf(items.first().copy(example = example)),
                    loading = false,
                )
            )
        compose.setContent {
            LegadoComposeTheme {
                ImportTxtTocRuleScreen(
                    state,
                    { state = state.copy(selected = state.selected + it) },
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.height(500.dp),
                    onExample = { key ->
                        state =
                            state.copy(
                                expanded =
                                    if (key in state.expanded) state.expanded - key
                                    else state.expanded + key
                            )
                    },
                )
            }
        }
        assertEquals(3, exampleLineCount())
        compose.onNodeWithTag("toc-import-example-new").performClick()
        compose.runOnIdle {
            assertTrue(state.selected.isEmpty())
            assertEquals(setOf("new"), state.expanded)
        }
        assertEquals(39, exampleLineCount())
        // The expanded text is taller than the viewport; its accessibility click remains available.
        compose.onNodeWithTag("toc-import-example-new").performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        assertEquals(3, exampleLineCount())
        compose.runOnIdle { assertTrue(state.selected.isEmpty()) }
    }

    @Test
    fun blankExampleIsAbsentAndCollapsedLongExampleReturnsWhenReboundToAnotherPayload() {
        var item by mutableStateOf(items.first().copy(example = "\n  "))
        compose.setContent { Content(ImportTxtTocRuleState(listOf(item), loading = false)) }
        compose.onNodeWithTag("toc-import-example-new").assertDoesNotExist()
        compose.runOnIdle {
            item = item.copy(example = (1..45).joinToString("\n") { "Changed $it" })
        }
        assertEquals(3, exampleLineCount())
    }

    private fun exampleLineCount(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("toc-import-example-new").performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        return layouts.single().lineCount
    }

    @Composable
    private fun Content(
        state: ImportTxtTocRuleState,
        toggle: (String) -> Unit = {},
        all: () -> Unit = {},
        code: (String) -> Unit = {},
        confirm: () -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        height: Int = 500,
    ) {
        LegadoComposeTheme {
            ImportTxtTocRuleScreen(
                state,
                toggle,
                all,
                code,
                confirm,
                cancel,
                retry,
                Modifier.height(height.dp),
            )
        }
    }
}
