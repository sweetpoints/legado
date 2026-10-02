package io.legado.app.ui.code

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurlConversionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun textEditingAndSelectionUseActualFieldStateAndRestoreExternalSelectionWithoutRevertingTyping() {
        var state by mutableStateOf(CurlConversionState(CurlConversionDraft("command"), loading = false))
        compose.setContent { LegadoComposeTheme { CurlConversionScreen(state, false, { text, start, end ->
            state = state.copy(draft = state.draft.copy(input = text, selectionStart = start, selectionEnd = end))
        }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("curl-converter-input").performTextReplacement("curl https://example.com")
        compose.onNodeWithTag("curl-converter-input").assertTextContains("curl https://example.com")
        compose.onNodeWithTag("curl-converter-input").performTextInputSelection(TextRange(5, 10))
        compose.runOnIdle { assertEquals(5, state.draft.selectionStart); assertEquals(10, state.draft.selectionEnd) }
        compose.runOnIdle { state = state.copy(draft = state.draft.copy(selectionStart = 2, selectionEnd = 4)) }
        compose.onNodeWithTag("curl-converter-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, TextRange(2, 4)))
    }
    @Test fun outputRemainsReadableByScrollingInShortWindowAndToolbarActionsStayReachable() {
        val state = CurlConversionState(CurlConversionDraft("curl command", "output"), loading = false)
        var copies = 0; var closes = 0
        compose.setContent { LegadoComposeTheme { CurlConversionScreen(state, true, { _, _, _ -> }, {}, {}, { copies++ }, {}, { closes++ }, {}, Modifier.height(260.dp)) } }
        compose.onNodeWithTag("curl-converter-copy").assertIsDisplayed().performClick()
        compose.onNodeWithTag("curl-converter-output").performScrollTo().assertIsDisplayed().assertTextContains("output")
        compose.onNodeWithTag("curl-converter-close").assertIsDisplayed().performClick()
        assertEquals(1, copies); assertEquals(1, closes)
    }
    @Test fun initialLoadFailureDisablesMutationAndOffersRetryWithoutHidingOriginalError() {
        var retries = 0
        compose.setContent { LegadoComposeTheme { CurlConversionScreen(CurlConversionState(loading = false, loadFailed = true), true,
            { _, _, _ -> error("input blocked") }, {}, {}, {}, {}, {}, { retries++ }) } }
        compose.onNodeWithTag("curl-converter-input").assertIsNotEnabled()
        compose.onNodeWithTag("curl-converter-convert").assertIsNotEnabled()
        compose.onNodeWithTag("curl-converter-copy").assertIsNotEnabled()
        compose.onNodeWithTag("curl-converter-insert").assertIsNotEnabled()
        compose.onNodeWithTag("curl-converter-retry").performClick(); assertEquals(1, retries)
    }
}
