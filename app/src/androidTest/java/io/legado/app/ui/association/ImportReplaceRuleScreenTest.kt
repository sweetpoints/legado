package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.ReplaceRuleImportItem
import io.legado.app.data.repository.ReplaceRuleImportStatus
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportReplaceRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items =
        listOf(
            ReplaceRuleImportItem("new", "New rule", "{}", ReplaceRuleImportStatus.New),
            ReplaceRuleImportItem(
                "update",
                "Existing edited rule",
                "{}",
                ReplaceRuleImportStatus.Existing,
            ),
            ReplaceRuleImportItem(
                "existing",
                "Stored rule",
                "{}",
                ReplaceRuleImportStatus.Existing,
            ),
        )

    @Test
    fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by mutableStateOf(ImportReplaceRuleState(items, setOf("new"), loading = false))
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
        compose.onNodeWithTag("replace-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(2, state.selectCount) }
        compose.onNodeWithTag("replace-import-row-existing").performClick()
        compose.onNodeWithTag("replace-import-check-existing").assertIsOff()
        compose.onNodeWithText(context.getString(R.string.import_status_new)).assertExists()
        compose
            .onAllNodesWithText(context.getString(R.string.import_status_exist))
            .assertCountEquals(2)
    }

    @Test
    fun selectAllCountsAndCodeAreIndependentFromRowSelection() {
        var state by mutableStateOf(ImportReplaceRuleState(items, setOf("new"), loading = false))
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
            .onNodeWithTag("replace-import-select-all")
            .assertTextEquals(context.getString(R.string.select_all_count, 1, 3))
            .performClick()
            .assertTextEquals(context.getString(R.string.select_cancel_count, 3, 3))
        compose.onNodeWithTag("replace-import-code-existing").performClick()
        compose.runOnIdle {
            assertEquals(listOf("existing"), edits)
            assertEquals(3, state.selectCount)
        }
        compose.onNodeWithTag("replace-import-select-all").performClick()
        compose.runOnIdle { assertEquals(0, state.selectCount) }
    }

    @Test
    fun changedExistingRulesShowLocalizedUpdate() {
        compose.setContent {
            Content(
                ImportReplaceRuleState(
                    listOf(items.first().copy(status = ReplaceRuleImportStatus.Update)),
                    loading = false,
                )
            )
        }
        compose.onNodeWithText(context.getString(R.string.import_status_update)).assertExists()
    }

    @Test
    fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(ImportReplaceRuleState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("replace-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("replace-import-code-new").assertIsNotEnabled()
        compose.onNodeWithTag("replace-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("replace-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("replace-import-progress").assertExists()
    }

    @Test
    fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0
        var cancels = 0
        compose.setContent {
            Content(
                ImportReplaceRuleState(loading = false, error = "ImportError:format"),
                retry = { retries++ },
                cancel = { cancels++ },
            )
        }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("replace-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("replace-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many =
            List(30) { ReplaceRuleImportItem("$it", "Rule $it", "{}", ReplaceRuleImportStatus.New) }
        var confirms = 0
        compose.setContent {
            Content(
                ImportReplaceRuleState(many, loading = false),
                confirm = { confirms++ },
                height = 340,
            )
        }
        compose.onNodeWithTag("replace-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Rule 29").assertIsDisplayed()
        compose.onNodeWithTag("replace-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun groupFormSupportsSuggestionsModeConfirmationAndCancelAsSeparateActions() {
        var state by
            mutableStateOf(
                ImportReplaceRuleState(
                    items,
                    setOf("new"),
                    loading = false,
                    groupOpen = true,
                    groups = listOf("Alpha", "Beta"),
                )
            )
        var accepted = 0
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme {
                ImportReplaceRuleScreen(
                    state,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.height(500.dp),
                    onGroupDraft = { state = state.copy(groupDraft = it) },
                    onAddGroup = { state = state.copy(addGroupDraft = it) },
                    onAcceptGroup = { accepted++ },
                    onCloseGroup = { cancelled++ },
                )
            }
        }
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithTag("replace-import-group-name").assertTextContains("Alpha")
        compose.onNodeWithTag("replace-import-add-group").performClick().assertIsOn()
        compose.onNodeWithTag("replace-import-group-ok").performClick()
        compose.onNodeWithTag("replace-import-group-cancel").performClick()
        compose.runOnIdle {
            assertEquals("Alpha", state.groupDraft)
            assertEquals(1, accepted)
            assertEquals(1, cancelled)
        }
    }

    @Composable
    private fun Content(
        state: ImportReplaceRuleState,
        toggle: (String) -> Unit = {},
        all: () -> Unit = {},
        code: (String) -> Unit = {},
        confirm: () -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        height: Int = 500,
    ) {
        LegadoComposeTheme {
            ImportReplaceRuleScreen(
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
