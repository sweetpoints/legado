package io.legado.app.ui.code

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodeEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun saveAndDebugControlsKeepIndependentActions() {
        val actions = mutableListOf<CodeEditorAction>()
        screen(CodeEditorSession("raw", showDebugSource = true), actions::add)
        compose.onNodeWithTag("code-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("code-debug").performClick()
        assertEquals(listOf(CodeEditorAction.SAVE, CodeEditorAction.DEBUG), actions)
    }

    @Test
    fun readonlyDoesNotExposeReturnTextControls() {
        screen(CodeEditorSession("raw", writable = false, showDebugSource = true))
        compose.onNodeWithTag("code-save").assertDoesNotExist()
        compose.onNodeWithTag("code-debug").assertDoesNotExist()
    }

    @Test
    fun composeSearchAssistanceReplacesUtf16SelectionInsteadOfNativeText() {
        var search = CodeEditorSearch(visible = true, query = "😀abc")
        screen(CodeEditorSession("raw", search = search), onSearch = { search = it })
        compose.onNodeWithTag("code-query").performTextReplacement("😀abc")
        compose.onNodeWithTag("code-query").performTextInputSelection(TextRange(2, 4))
        compose.onNodeWithText("insert-key").performClick()
        assertEquals("😀@c", search.query)
        assertEquals(CodeEditorSelection(3), search.querySelection)
    }

    private fun screen(
        session: CodeEditorSession,
        onAction: (CodeEditorAction) -> Unit = {},
        onSearch: (CodeEditorSearch) -> Unit = {},
    ) {
        compose.setContent {
            LegadoComposeTheme {
                CodeEditorScreen(
                    state =
                        CodeEditorComposeState(
                            session = session,
                            busy = false,
                            engineOwner = "owner",
                        ),
                    status = CodeEditorEngineStatus(ready = true),
                    safe = false,
                    keyboardVisible = true,
                    keyboardRows = 2,
                    assists = listOf(CodeEditorAssist("insert-key", "@")),
                    onAction = onAction,
                    onSearch = onSearch,
                    onInsert = {},
                    onExit = {},
                    onRetry = {},
                    onRestart = {},
                    onKeepEditing = {},
                    onDiscard = {},
                    editorContent = { Box(it) },
                )
            }
        }
    }
}
