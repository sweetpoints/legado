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
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RssImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun item(key: String, status: RssImportStatus = RssImportStatus.New) = RssImportEntry(key,
        "{}", "{}", null, null, emptyList(), null, "Source $key", "https://$key", null,
        (1..45).joinToString("\n") { "Comment line $it" }, true, null, status, true, status != RssImportStatus.Existing)
    @Test fun checkboxRowAndCodeHaveIndependentStableKeys() {
        var state by mutableStateOf(RssImportUiState(listOf(item("a"), item("b", RssImportStatus.Existing)), setOf("a"), loading = false))
        val codes = mutableListOf<String>()
        compose.setContent { Content(state, toggle = { state = state.copy(selected = if (it in state.selected) state.selected - it else state.selected + it) }, code = { codes += it }) }
        compose.onNodeWithTag("rss-import-check-b").performClick().assertIsOn()
        compose.onNodeWithTag("rss-import-row-b").performClick()
        compose.onNodeWithTag("rss-import-check-b").assertIsOff()
        compose.onNodeWithTag("rss-import-code-b").performClick()
        compose.runOnIdle { assertEquals(listOf("b"), codes); assertEquals(setOf("a"), state.selected) }
        compose.onNodeWithText(context.getString(R.string.import_status_exist)).assertExists()
    }
    @Test fun commentClickExpandsTo39LinesWithoutChangingSelection() {
        var state by mutableStateOf(RssImportUiState(listOf(item("a")), setOf("a"), loading = false,
            preferences = RssImportPreferences(showComment = true)))
        compose.setContent { Content(state, expand = { state = state.copy(expanded = if (it in state.expanded) state.expanded - it else state.expanded + it) }) }
        fun lines(): Int {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("rss-import-comment-a").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().lineCount
        }
        assertEquals(3, lines()); compose.onNodeWithTag("rss-import-comment-a").performClick()
        assertEquals(39, lines()); compose.onNodeWithTag("rss-import-check-a").assertIsOn()
        compose.onNodeWithTag("rss-import-comment-a").performClick(); assertEquals(3, lines())
    }
    @Test fun pendingRefreshBlocksAllMutationsAndCancelButKeepsSearch() {
        compose.setContent { Content(RssImportUiState(listOf(item("a")), loading = false, pendingRefresh = true)) }
        listOf("check-a", "code-a", "menu", "group", "confirm", "cancel", "select-visible").forEach {
            compose.onNodeWithTag("rss-import-$it").assertIsNotEnabled()
        }
        compose.onNodeWithTag("rss-import-progress").assertExists()
        compose.onNodeWithTag("rss-import-search").assertIsEnabled()
    }
    @Test fun automaticModeDisablesManualMenuAndPreferenceRowsPreserveOrder() {
        val menus = mutableListOf<RssImportMenu>()
        compose.setContent { Content(RssImportUiState(listOf(item("a")), loading = false, automatic = true), menu = { menus += it }) }
        compose.onNodeWithTag("rss-import-menu").performClick()
        compose.onNodeWithTag("rss-import-menu-Manual").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rss-import-menu-RememberGroup").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(RssImportMenu.RememberGroup), menus) }
        assertEquals(RssImportMenu.ShowComment.ordinal + 1, RssImportMenu.RememberGroup.ordinal)
    }
    @Test fun groupDraftCancelAndAcceptUseFormCallbacks() {
        var state by mutableStateOf(RssImportUiState(listOf(item("a")), loading = false, groupOpen = true, groupDraft = "before"))
        var accepts = 0; var closes = 0
        compose.setContent { Content(state, draft = { state = state.copy(groupDraft = it) }, add = { state = state.copy(addGroupDraft = it) },
            accept = { accepts++ }, closeGroup = { closes++ }) }
        compose.onNodeWithTag("rss-import-group-name").performTextReplacement("after")
        compose.onNodeWithTag("rss-import-add-group").performClick().assertIsOn()
        compose.onNodeWithTag("rss-import-group-ok").performClick()
        compose.onNodeWithTag("rss-import-group-cancel").performClick()
        compose.runOnIdle { assertEquals("after", state.groupDraft); assertNull(state.group); assertEquals(1, accepts); assertEquals(1, closes) }
    }
    @Test fun shortWindowRetainsReachableFooterAndScrollableRows() {
        val state = RssImportUiState((0..29).map { item("$it") }, loading = false)
        var confirms = 0
        compose.setContent { Content(state, confirm = { confirms++ }, height = 320) }
        compose.onNodeWithTag("rss-import-list").performScrollToIndex(29)
        compose.onNodeWithTag("rss-import-code-29").assertExists()
        compose.onNodeWithTag("rss-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
    @Composable private fun Content(state: RssImportUiState, toggle: (String) -> Unit = {}, code: (String) -> Unit = {},
        expand: (String) -> Unit = {}, menu: (RssImportMenu) -> Unit = {}, draft: (String) -> Unit = {}, add: (Boolean) -> Unit = {},
        accept: () -> Unit = {}, closeGroup: () -> Unit = {}, confirm: () -> Unit = {}, height: Int = 550) {
        LegadoComposeTheme { RssImportScreen(state, {}, toggle, {}, code, expand, menu, {}, draft, add, accept, closeGroup,
            confirm, {}, {}, Modifier.height(height.dp)) }
    }
}
