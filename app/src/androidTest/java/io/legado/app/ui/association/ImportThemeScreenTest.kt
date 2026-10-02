package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.ThemeImportItem
import io.legado.app.data.repository.ThemeImportStatus
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportThemeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf(ThemeImportItem("new", "New theme", "{}", ThemeImportStatus.New),
        ThemeImportItem("update", "Updated theme", "{}", ThemeImportStatus.Update),
        ThemeImportItem("existing", "Stored theme", "{}", ThemeImportStatus.Existing))
    @Test fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by mutableStateOf(ImportThemeState(items, setOf("new", "update"), loading = false))
        compose.setContent { Content(state, toggle = { key -> state = state.copy(selected =
            if (key in state.selected) state.selected - key else state.selected + key) }) }
        compose.onNodeWithTag("theme-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(3, state.selectCount) }
        compose.onNodeWithTag("theme-import-row-existing").performClick()
        compose.onNodeWithTag("theme-import-check-existing").assertIsOff()
        listOf(R.string.import_status_new, R.string.import_status_update, R.string.import_status_exist).forEach {
            compose.onNodeWithText(context.getString(it)).assertExists()
        }
    }
    @Test fun selectAllCountsAndCodeAreIndependentFromRowSelection() {
        var state by mutableStateOf(ImportThemeState(items, setOf("new", "update"), loading = false))
        val edits = mutableListOf<String>()
        compose.setContent { Content(state, all = { state = state.copy(selected =
            if (state.isSelectAll) emptySet() else items.mapTo(mutableSetOf()) { it.key }) }, code = { edits += it }) }
        compose.onNodeWithTag("theme-import-select-all").assertTextEquals(context.getString(R.string.select_all_count, 2, 3))
            .performClick().assertTextEquals(context.getString(R.string.select_cancel_count, 3, 3))
        compose.onNodeWithTag("theme-import-code-existing").performClick()
        compose.runOnIdle { assertEquals(listOf("existing"), edits); assertEquals(3, state.selectCount) }
        compose.onNodeWithTag("theme-import-select-all").performClick()
        compose.runOnIdle { assertEquals(0, state.selectCount) }
    }
    @Test fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(ImportThemeState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("theme-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("theme-import-code-new").assertIsNotEnabled()
        compose.onNodeWithTag("theme-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("theme-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("theme-import-progress").assertExists()
    }
    @Test fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0; var cancels = 0
        compose.setContent { Content(ImportThemeState(loading = false, error = "ImportError:format"),
            retry = { retries++ }, cancel = { cancels++ }) }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("theme-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("theme-import-cancel").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, cancels) }
    }
    @Test fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many = List(30) { ThemeImportItem("$it", "Theme $it", "{}", ThemeImportStatus.New) }
        var confirms = 0
        compose.setContent { Content(ImportThemeState(many, loading = false), confirm = { confirms++ }, height = 280) }
        compose.onNodeWithTag("theme-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Theme 29").assertIsDisplayed()
        compose.onNodeWithTag("theme-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
    @Composable private fun Content(state: ImportThemeState, toggle: (String) -> Unit = {},
        all: () -> Unit = {}, code: (String) -> Unit = {}, confirm: () -> Unit = {},
        cancel: () -> Unit = {}, retry: () -> Unit = {}, height: Int = 500) {
        LegadoComposeTheme { ImportThemeScreen(state, toggle, all, code, confirm, cancel, retry, Modifier.height(height.dp)) }
    }
}
