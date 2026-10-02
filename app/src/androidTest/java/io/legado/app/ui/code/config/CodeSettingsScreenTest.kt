package io.legado.app.ui.code.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.data.preferences.CodeSettingsPreferences
import io.legado.app.data.preferences.CodeSettingsSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CodeSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun autoCompleteRowChangesState() {
        val state = mutableStateOf(CodeSettingsUiState(loading = false))
        compose.setContent { LegadoComposeTheme { CodeSettingsScreen(state.value, {}, { state.value = state.value.copy(autoComplete = it) }, {}, {}) } }
        compose.onNodeWithTag("code-settings-auto").assertIsOn().performClick().assertIsOff()
    }
    @Test fun allSixFlagsRemainAccessibleInSmallWindow() {
        val flags = listOf(CodeEditor.FLAG_DRAW_WHITESPACE_LEADING, CodeEditor.FLAG_DRAW_WHITESPACE_INNER,
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING, CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE,
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR, CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION)
        val state = mutableStateOf(CodeSettingsUiState(loading = false))
        compose.setContent { LegadoComposeTheme { CodeSettingsScreen(state.value, {}, {},
            { state.value = state.value.copy(nonPrintable = state.value.nonPrintable xor it) }, {}, Modifier.heightIn(max = 240.dp)) } }
        flags.forEach { compose.onNodeWithTag("code-settings-flag-$it").performScrollTo().performClick().assertIsOn() }
        compose.runOnIdle { assertEquals(flags.fold(0) { value, flag -> value or flag }, state.value.nonPrintable) }
    }
    @Test fun loadingDoesNotAllowPreferenceChanges() {
        compose.setContent { LegadoComposeTheme { CodeSettingsScreen(CodeSettingsUiState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("code-settings-font").assertIsNotEnabled()
        compose.onNodeWithTag("code-settings-auto").assertIsNotEnabled()
    }
    @Test fun fontPickerConfirmationUpdatesLivePreviewAndClosesPicker() {
        val previews = mutableListOf<Pair<Int, Boolean>>()
        val model = CodeSettingsViewModel(Fake(), SavedStateHandle())
        compose.setContent { LegadoComposeTheme { CodeSettingsRoute(model, { font, auto -> previews += font to auto }) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("code-settings-font").performClick()
        compose.onNodeWithTag("number-input").performTextReplacement("24")
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.waitUntil { !model.state.value.fontPicker && previews.lastOrNull()?.first == 24 }
        compose.runOnIdle { assertEquals(24, model.state.value.font) }
    }
    @Test fun neutralFontPickerButtonRestoresSixteen() {
        val model = CodeSettingsViewModel(Fake(CodeSettingsSnapshot(font = 29)), SavedStateHandle())
        compose.setContent { LegadoComposeTheme { CodeSettingsRoute(model, { _, _ -> }) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("code-settings-font").performClick()
        compose.onNodeWithTag("number-custom").performScrollTo().performClick()
        compose.waitUntil { !model.state.value.fontPicker }
        compose.runOnIdle { assertEquals(16, model.state.value.font) }
    }
    private class Fake(private val snapshot: CodeSettingsSnapshot = CodeSettingsSnapshot()) : CodeSettingsPreferences {
        override suspend fun load() = snapshot
        override fun saveFont(value: Int) {}
        override fun saveAutoComplete(value: Boolean) {}
        override fun saveNonPrintable(value: Int) {}
    }
}
