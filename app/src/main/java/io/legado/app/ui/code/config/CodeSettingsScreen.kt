package io.legado.app.ui.code.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R

@Composable
fun CodeSettingsScreen(
    state: CodeSettingsUiState,
    onFont: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onFlag: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.config_settings),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.loading) CircularProgressIndicator(Modifier.padding(16.dp))
            state.error?.let {
                Text(it)
                TextButton(onRetry) { Text(stringResource(R.string.retry)) }
            }
            TextButton(
                onFont,
                enabled = !state.loading && state.error == null,
                modifier = Modifier.fillMaxWidth().testTag("code-settings-font"),
            ) {
                Text(stringResource(R.string.font_size, state.font))
            }
            CodeSettingsToggle(
                stringResource(R.string.auto_complete),
                state.autoComplete,
                !state.loading && state.error == null,
                "code-settings-auto",
                onAuto,
            )
            Text(
                stringResource(R.string.non_printable_set),
                Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            val options =
                listOf(
                    CodeEditor.FLAG_DRAW_WHITESPACE_LEADING to R.string.whitespace_leading,
                    CodeEditor.FLAG_DRAW_WHITESPACE_INNER to R.string.whitespace_inner,
                    CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING to R.string.whitespace_trailing,
                    CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE to R.string.whitespace_empty,
                    CodeEditor.FLAG_DRAW_LINE_SEPARATOR to R.string.line_separator,
                    CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION to R.string.whitespace_selection,
                )
            options.forEach { (flag, label) ->
                CodeSettingsToggle(
                    stringResource(label),
                    state.nonPrintable and flag != 0,
                    !state.loading && state.error == null,
                    "code-settings-flag-$flag",
                    { onFlag(flag) },
                )
            }
        }
    }
}

@Composable
private fun CodeSettingsToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChange,
            ),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Checkbox(checked, null, enabled = enabled)
    }
}
