package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.HttpTtsEditorDraft
import io.legado.app.data.repository.HttpTtsEditorField
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HttpTtsEditScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun textAndCookieActionsEditTheActualFields() {
        var state by mutableStateOf(HttpTtsEditUiState(HttpTtsEditorDraft(7)))
        compose.setContent { Content(state, onEdit = { field, text, start, end -> state = state.copy(
            draft = state.draft.edit(field, text), selections = state.selections + (field to HttpTtsEditorSelection(start, end))) },
            onCookie = { state = state.copy(draft = state.draft.copy(cookie = it)) }) }
        compose.onNodeWithTag("http-tts-field:Name").performTextInput("Name")
        compose.onNodeWithTag("http-tts-field:JsLib").performScrollTo().performTextInput("function sign() {}")
        compose.onNodeWithTag("http-tts-cookie").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Name", state.draft.name); assertEquals("function sign() {}", state.draft.jsLib); assertTrue(state.draft.cookie) }
    }
    @Test fun restoredFieldSelectionAndFocusRemainAvailableForCodeEditing() {
        val state = HttpTtsEditUiState(HttpTtsEditorDraft(7, url = "abcdef"),
            selections = mapOf(HttpTtsEditorField.Url to HttpTtsEditorSelection(2, 4)), focus = HttpTtsEditorField.Url)
        compose.setContent { Content(state) }
        compose.onNodeWithTag("http-tts-field:Url").performScrollTo().assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 4)))
    }
    @Test fun unsavedExitYesKeepsEditingAndNoDiscards() {
        var keeps = 0; var discards = 0
        compose.setContent { Content(HttpTtsEditUiState(HttpTtsEditorDraft(7), exit = true), onKeep = { keeps++ }, onDiscard = { discards++ }) }
        compose.onNodeWithText(context.getString(R.string.yes)).performClick()
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        compose.runOnIdle { assertEquals(1, keeps); assertEquals(1, discards) }
    }
    @Test fun emptyLoginHeaderShowsExplicitEmptyContent() {
        compose.setContent { Content(HttpTtsEditUiState(HttpTtsEditorDraft(7), header = "")) }
        compose.onNodeWithText(context.getString(R.string.login_header)).assertExists()
        compose.onNodeWithText(context.getString(R.string.empty)).assertExists()
    }
    @Test fun busyStateDisablesEditingAndSave() {
        compose.setContent { Content(HttpTtsEditUiState(HttpTtsEditorDraft(7), busy = true)) }
        compose.onNodeWithTag("http-tts-field:Name").assertIsNotEnabled()
        compose.onNodeWithTag("http-tts-save").assertIsNotEnabled()
    }
    @Test fun loadFailureDisablesSaveAndExposesRetry() {
        var retries = 0
        compose.setContent { LegadoComposeTheme { HttpTtsEditScreen(
            HttpTtsEditUiState(HttpTtsEditorDraft(7), loadFailed = true, error = "read failed"),
            { _, _, _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.height(600.dp), { retries++ }) } }
        compose.onNodeWithTag("http-tts-save").assertIsNotEnabled()
        compose.onNodeWithTag("http-tts-field:Name").assertIsNotEnabled()
        compose.onNodeWithTag("http-tts-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun allEditorMenusKeepDistinctActions() {
        val actions = mutableListOf<HttpTtsEditorAction>(); var headers = 0; var deletes = 0
        compose.setContent { Content(onAction = { actions += it }, onHeader = { headers++ }, onDeleteHeader = { deletes++ }) }
        listOf(R.string.login, R.string.show_login_header, R.string.del_login_header, R.string.copy_source,
            R.string.paste_source, R.string.log, R.string.help).forEach { id ->
            compose.onNodeWithTag("http-tts-menu").performClick()
            compose.onNodeWithText(context.getString(id)).performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(HttpTtsEditorAction.Login, HttpTtsEditorAction.Copy, HttpTtsEditorAction.Paste,
                HttpTtsEditorAction.Log, HttpTtsEditorAction.Help), actions)
            assertEquals(1, headers); assertEquals(1, deletes)
        }
    }
    @Composable private fun Content(state: HttpTtsEditUiState = HttpTtsEditUiState(HttpTtsEditorDraft(7)),
        onEdit: (HttpTtsEditorField, String, Int, Int) -> Unit = { _, _, _, _ -> }, onCookie: (Boolean) -> Unit = {},
        onAction: (HttpTtsEditorAction) -> Unit = {}, onHeader: () -> Unit = {}, onDeleteHeader: () -> Unit = {},
        onKeep: () -> Unit = {}, onDiscard: () -> Unit = {}) {
        LegadoComposeTheme { HttpTtsEditScreen(state, onEdit, {}, onCookie, onAction, onHeader, onDeleteHeader,
            {}, {}, onKeep, onDiscard, Modifier.height(600.dp)) }
    }
}
