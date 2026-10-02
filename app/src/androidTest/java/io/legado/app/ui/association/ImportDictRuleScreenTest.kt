package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.DictRuleImportItem
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportDictRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf(DictRuleImportItem("new", "New rule", "{}", false),
        DictRuleImportItem("update", "Existing edited rule", "{}", true),
        DictRuleImportItem("existing", "Stored rule", "{}", true))
    @Test fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by mutableStateOf(ImportDictRuleState(items, setOf("new"), loading = false))
        compose.setContent { Content(state, toggle = { key -> state = state.copy(selected =
            if (key in state.selected) state.selected - key else state.selected + key) }) }
        compose.onNodeWithTag("dict-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(2, state.selectCount) }
        compose.onNodeWithTag("dict-import-row-existing").performClick()
        compose.onNodeWithTag("dict-import-check-existing").assertIsOff()
        compose.onNodeWithText(context.getString(R.string.import_status_new)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.import_status_exist)).assertCountEquals(2)
    }
    @Test fun selectAllCountsAndCodeAreIndependentFromRowSelection() {
        var state by mutableStateOf(ImportDictRuleState(items, setOf("new"), loading = false))
        val edits = mutableListOf<String>()
        compose.setContent { Content(state, all = { state = state.copy(selected =
            if (state.isSelectAll) emptySet() else items.mapTo(mutableSetOf()) { it.key }) }, code = { edits += it }) }
        compose.onNodeWithTag("dict-import-select-all").assertTextEquals(context.getString(R.string.select_all_count, 1, 3))
            .performClick().assertTextEquals(context.getString(R.string.select_cancel_count, 3, 3))
        compose.onNodeWithTag("dict-import-code-existing").performClick()
        compose.runOnIdle { assertEquals(listOf("existing"), edits); assertEquals(3, state.selectCount) }
        compose.onNodeWithTag("dict-import-select-all").performClick()
        compose.runOnIdle { assertEquals(0, state.selectCount) }
    }
    @Test fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(ImportDictRuleState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("dict-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("dict-import-code-new").assertIsNotEnabled()
        compose.onNodeWithTag("dict-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("dict-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("dict-import-progress").assertExists()
    }
    @Test fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0; var cancels = 0
        compose.setContent { Content(ImportDictRuleState(loading = false, error = "ImportError:format"),
            retry = { retries++ }, cancel = { cancels++ }) }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("dict-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("dict-import-cancel").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, cancels) }
    }
    @Test fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many = List(30) { DictRuleImportItem("$it", "Rule $it", "{}", false) }
        var confirms = 0
        compose.setContent { Content(ImportDictRuleState(many, loading = false), confirm = { confirms++ }, height = 280) }
        compose.onNodeWithTag("dict-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Rule 29").assertIsDisplayed()
        compose.onNodeWithTag("dict-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
    @Composable private fun Content(state: ImportDictRuleState, toggle: (String) -> Unit = {},
        all: () -> Unit = {}, code: (String) -> Unit = {}, confirm: () -> Unit = {},
        cancel: () -> Unit = {}, retry: () -> Unit = {}, height: Int = 500) {
        LegadoComposeTheme { ImportDictRuleScreen(state, toggle, all, code, confirm, cancel, retry, Modifier.height(height.dp)) }
    }
}
