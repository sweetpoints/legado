package io.legado.app.ui.book.changesource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun WordCountFilterScreen(state: WordCountFilterState, onMode: (Int) -> Unit,
    onMinimum: (String) -> Unit, onMaximum: (String) -> Unit, onConfirm: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.change_source_word_count_filter), style = MaterialTheme.typography.titleLarge)
            if (state.mode == null) {
                listOf(R.string.change_source_word_count_filter_off, R.string.change_source_word_count_filter_absolute,
                    R.string.change_source_word_count_filter_relative).forEachIndexed { mode, label ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("word-count-mode-$mode")
                        .selectable(false, role = Role.RadioButton, onClick = { onMode(mode) }), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label))
                    }
                }
            } else {
                OutlinedTextField(state.minimum, onMinimum, label = { Text(stringResource(R.string.change_source_word_count_minimum)) },
                    isError = state.invalid, supportingText = { if (state.invalid) Text(stringResource(R.string.error_scope_input)) },
                    suffix = { if (state.mode == 2) Text("%") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("word-count-minimum"))
                OutlinedTextField(state.maximum, onMaximum, label = { Text(stringResource(R.string.change_source_word_count_maximum)) },
                    isError = state.invalid, supportingText = { if (state.invalid) Text(stringResource(R.string.error_scope_input)) },
                    suffix = { if (state.mode == 2) Text("%") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("word-count-maximum"))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClose, Modifier.testTag("word-count-cancel")) { Text(stringResource(R.string.cancel)) }
                if (state.mode != null) TextButton(onConfirm, Modifier.testTag("word-count-confirm")) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
