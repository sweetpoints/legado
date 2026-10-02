package io.legado.app.ui.rss.source.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssSourceEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun state(tab: Int = 0) = RssSourceEditorState(draft = RssSourceEditorDraft.from(RssSource("url", "Name")), loading = false, loaded = true, tab = tab)
    @Test fun compactOptionsStartCollapsedWhileTypeSelectorsRemainReachableAndToggleAllFourFlags() {
        var state by mutableStateOf(state()); val actions = RssSourceEditorActions(expanded = { state = state.copy(expanded = it) }, options = { state = state.copy(draft = it(state.draft)) })
        compose.setContent { LegadoComposeTheme { RssSourceEditorScreen(state, actions) } }
        compose.onNodeWithText(context.getString(R.string.book_type) + ": " + context.resources.getStringArray(R.array.rss_type)[0]).assertExists()
        compose.onNodeWithText(context.getString(R.string.layout_type) + ": " + context.resources.getStringArray(R.array.layout_type)[0]).assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithTag("rss-editor-options").performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        compose.onAllNodes(isToggleable())[0].performClick(); compose.onAllNodes(isToggleable())[1].performClick()
        compose.onAllNodes(isToggleable())[2].performClick(); compose.onAllNodes(isToggleable())[3].performClick()
        compose.runOnIdle { assertFalse(state.draft.enabled); assertTrue(state.draft.singleUrl); assertFalse(state.draft.cookieJar); assertTrue(state.draft.preload) }
    }
    @Test fun fieldNavigationScrollsToCorrectLabelAndKeepsMultilineSelectionWithoutReplacingRawRule() {
        var state by mutableStateOf(state()); var full = false
        val actions = RssSourceEditorActions(field = { field, value -> state = state.copy(draft = state.draft.with(field, value)) }, focus = { state = state.copy(focus = it) }, editor = { full = true })
        compose.setContent { LegadoComposeTheme { RssSourceEditorScreen(state, actions, maxLines = 4) } }
        compose.onNodeWithTag("rss-editor-navigation").performClick(); compose.onNodeWithTag("rss-editor-navigate-LoginUrl").performScrollTo().performClick()
        compose.onNodeWithTag("rss-editor-LoginUrl").performTextReplacement("@js:line1\nline2")
        compose.onNodeWithTag("rss-editor-LoginUrl").performTextInputSelection(TextRange(4, 8))
        compose.onNodeWithTag("rss-editor-fullscreen").performClick()
        compose.runOnIdle { assertTrue(full); assertEquals(RssSourceEditorText("@js:line1\nline2", 4, 8), state.draft[RssSourceEditorField.LoginUrl]); assertEquals(RssSourceEditorField.LoginUrl, state.focus) }
    }
    @Test fun webTabExposesFourFlagsAndNextContentNavigationBeforeCssAndScripts() {
        var state by mutableStateOf(state(3)); var selected: RssSourceEditorField? = null
        val actions = RssSourceEditorActions(options = { state = state.copy(draft = it(state.draft)) }, focus = { selected = it })
        compose.setContent { LegadoComposeTheme { RssSourceEditorScreen(state, actions) } }
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        compose.onAllNodes(isToggleable())[0].performClick(); compose.onAllNodes(isToggleable())[3].performClick()
        compose.onNodeWithTag("rss-editor-navigation").performClick()
        compose.onNodeWithTag("rss-editor-navigate-NextContentUrl").assertExists().performClick()
        compose.onNodeWithTag("rss-editor-NextContentUrl").assertExists()
        compose.runOnIdle { assertEquals(RssSourceEditorField.NextContentUrl, selected); assertFalse(state.draft.enableJs); assertTrue(state.draft.cacheFirst) }
    }
    @Test fun longAndCombiningHeavyRowsOpenNativeEditorWithoutPuttingPlaceholderIntoDraft() {
        val raw = "a".repeat(13000); val combining = "a" + "\u0301".repeat(40)
        val draft = state(1).draft.with(RssSourceEditorField.StartHtml, RssSourceEditorText(raw)).with(RssSourceEditorField.StartStyle, RssSourceEditorText(combining))
        var focus: RssSourceEditorField? = null; var edits = 0
        compose.setContent { LegadoComposeTheme { RssSourceEditorScreen(state(1).copy(draft = draft), RssSourceEditorActions(focus = { focus = it }, editor = { edits++ })) } }
        compose.onNodeWithTag("rss-editor-StartHtml").performClick()
        compose.onNodeWithTag("rss-editor-StartStyle").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(2, edits); assertEquals(RssSourceEditorField.StartStyle, focus); assertEquals(raw, draft[RssSourceEditorField.StartHtml].text); assertEquals(combining, draft[RssSourceEditorField.StartStyle].text) }
    }
    @Test fun darkSmallViewportAllowsNavigatingLastFieldAndUsesRealExitKeepDiscardControls() {
        var state by mutableStateOf(state().copy(exit = true)); var discarded = 0
        val actions = RssSourceEditorActions(keep = { state = state.copy(exit = false) }, discard = { discarded++ })
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(320.dp, 420.dp)) { RssSourceEditorScreen(state, actions) } } }
        compose.onNodeWithTag("rss-editor-keep").performClick(); compose.onNodeWithText(context.getString(R.string.exit_no_save)).assertDoesNotExist()
        compose.onNodeWithTag("rss-editor-navigation").performClick(); compose.onNodeWithTag("rss-editor-navigate-JsLib").performScrollTo().performClick()
        compose.onNodeWithTag("rss-editor-JsLib").assertExists()
        compose.runOnIdle { state = state.copy(exit = true) }; compose.onNodeWithTag("rss-editor-discard").performClick(); assertEquals(1, discarded)
    }
    @Test fun switchingTabsRestoresIndependentScrollAndConfigurationKeepsCurrentLazyListPosition() {
        var state by mutableStateOf(state()); val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        val actions = RssSourceEditorActions(tab = { state = state.copy(tab = it) })
        restoration.setContent { LegadoComposeTheme { RssSourceEditorScreen(state, actions) } }
        compose.onNodeWithTag("rss-editor-fields").performScrollToNode(hasTestTag("rss-editor-JsLib")); compose.onNodeWithTag("rss-editor-JsLib").assertIsDisplayed()
        compose.onNodeWithTag("rss-editor-tab-1").performClick(); compose.onNodeWithTag("rss-editor-StartHtml").assertIsDisplayed()
        compose.onNodeWithTag("rss-editor-tab-0").performClick(); compose.onNodeWithTag("rss-editor-JsLib").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore(); compose.onNodeWithTag("rss-editor-JsLib").assertIsDisplayed()
    }
    @Test fun saveBeforeDebugLoginAndVariableAndCopyPasteShareMenuDispatchExactPublicActions() {
        val actions = mutableListOf<RssSourceEditorSaveAction>(); val effects = mutableListOf<RssSourceEditorEffectKind>(); var pastes = 0
        compose.setContent { LegadoComposeTheme { RssSourceEditorScreen(state(), RssSourceEditorActions(save = { actions += it }, action = { effects += it }, paste = { pastes++ })) } }
        compose.onNodeWithTag("rss-editor-save").performClick()
        listOf(R.string.debug_source, R.string.login, R.string.set_source_variable).forEach { label -> compose.onNodeWithTag("rss-editor-menu").performClick(); compose.onNodeWithText(context.getString(label)).performClick() }
        listOf(R.string.copy_source, R.string.str_share, R.string.qr_share).forEach { label -> compose.onNodeWithTag("rss-editor-menu").performClick(); compose.onNodeWithText(context.getString(label)).performScrollTo().performClick() }
        compose.onNodeWithTag("rss-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.paste_source)).performScrollTo().performClick()
        assertEquals(listOf(RssSourceEditorSaveAction.Close, RssSourceEditorSaveAction.Debug, RssSourceEditorSaveAction.Login, RssSourceEditorSaveAction.Variable), actions)
        assertEquals(listOf(RssSourceEditorEffectKind.Clipboard, RssSourceEditorEffectKind.ShareText, RssSourceEditorEffectKind.ShareQr), effects); assertEquals(1, pastes)
    }
}
