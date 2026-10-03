package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.HighlightImportItem
import io.legado.app.data.repository.HighlightImportStatus
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportHighlightRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items =
        listOf(
            HighlightImportItem("new", "New rule", "{}", HighlightImportStatus.NEW),
            HighlightImportItem("update", "Updated rule", "{}", HighlightImportStatus.UPDATE),
            HighlightImportItem("existing", "Stored rule", "{}", HighlightImportStatus.EXISTING),
        )

    @Test
    fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by
            mutableStateOf(HighlightImportState(items, setOf("new", "update"), loading = false))
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
        compose.onNodeWithTag("highlight-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(3, state.selectCount) }
        compose.onNodeWithTag("highlight-import-row-existing").performClick()
        compose.onNodeWithTag("highlight-import-check-existing").assertIsOff()
        listOf(
                R.string.import_status_new,
                R.string.import_status_update,
                R.string.import_status_exist,
            )
            .forEach {
                compose.onNodeWithText(context.getString(it)).assertExists()
            }
    }

    @Test
    fun emptySelectionDisablesConfirmationUntilAllOrOneRuleIsSelected() {
        var state by mutableStateOf(HighlightImportState(items, emptySet(), loading = false))
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
            )
        }
        compose.onNodeWithTag("highlight-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-import-select-all").performClick()
        compose.onNodeWithTag("highlight-import-confirm").assertIsEnabled()
        compose.onNodeWithTag("highlight-import-select-all").performClick()
        compose.onNodeWithTag("highlight-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-import-code-existing").assertDoesNotExist()
    }

    @Test
    fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(HighlightImportState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("highlight-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-import-progress").assertExists()
    }

    @Test
    fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0
        var cancels = 0
        compose.setContent {
            Content(
                HighlightImportState(loading = false, error = "ImportError:format"),
                retry = { retries++ },
                cancel = { cancels++ },
            )
        }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("highlight-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("highlight-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many =
            List(30) { HighlightImportItem("$it", "Rule $it", "{}", HighlightImportStatus.NEW) }
        var confirms = 0
        compose.setContent {
            Content(
                HighlightImportState(many, setOf("29"), loading = false),
                confirm = { confirms++ },
                height = 280,
            )
        }
        compose.onNodeWithTag("highlight-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Rule 29").assertIsDisplayed()
        compose.onNodeWithTag("highlight-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Composable
    private fun Content(
        state: HighlightImportState,
        toggle: (String) -> Unit = {},
        all: () -> Unit = {},
        confirm: () -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        height: Int = 500,
    ) {
        LegadoComposeTheme {
            ImportHighlightRuleScreen(
                state,
                toggle,
                all,
                confirm,
                cancel,
                retry,
                Modifier.height(height.dp),
            )
        }
    }
}
