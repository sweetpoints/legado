package io.legado.app.ui.dict.rule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun DictionaryRuleEditScreen(
    state: DictionaryRuleEditUiState,
    onInput: (DictionaryRuleField, String, Int) -> Unit,
    onFocus: (DictionaryRuleField) -> Unit,
    onFullEdit: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val enabled = !state.loading && !state.loadFailed && !state.saving && !state.finished
    val orange = colorResource(R.color.md_orange_900)
    val blue = colorResource(R.color.md_blue_800)
    val grey = colorResource(R.color.md_blue_grey_500)
    val lightBlue = colorResource(R.color.md_light_blue_600)
    val syntax =
        remember(orange, blue, grey, lightBlue) {
            DictionaryRuleSyntax(orange, blue, grey, lightBlue)
        }
    val requesters = remember { DictionaryRuleField.entries.associateWith { FocusRequester() } }
    LaunchedEffect(state.focused, state.loading) {
        if (!state.loading) state.focused?.let { requesters.getValue(it).requestFocus() }
    }
    val closeLabel = stringResource(R.string.close)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .testTag("dictionary-rule-backdrop")
                .clickable(enabled = !state.saving, onClickLabel = closeLabel, onClick = onClose)
        )
        Surface(
            onClick = {},
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier =
                Modifier.padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(1.dp))
                    .testTag("dictionary-rule-card"),
        ) {
            Column {
                LegadoTopAppBar(
                    stringResource(R.string.dict_rule),
                    onClose,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    actions = {
                        IconButton(
                            onFullEdit,
                            enabled = enabled,
                            modifier = Modifier.testTag("dictionary-rule-fullscreen"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_code),
                                stringResource(R.string.edit_content),
                            )
                        }
                        IconButton(
                            onSave,
                            enabled = enabled,
                            modifier = Modifier.testTag("dictionary-rule-save"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_save),
                                stringResource(R.string.action_save),
                            )
                        }
                        Box {
                            IconButton(
                                { menu = true },
                                enabled = enabled,
                                modifier = Modifier.testTag("dictionary-rule-menu"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.menu),
                                )
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.copy_rule)) },
                                    {
                                        menu = false
                                        onCopy()
                                    },
                                    modifier = Modifier.testTag("dictionary-rule-copy"),
                                )
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.paste_rule)) },
                                    {
                                        menu = false
                                        onPaste()
                                    },
                                    modifier = Modifier.testTag("dictionary-rule-paste"),
                                )
                            }
                        }
                    },
                )
                Column(
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (state.loading || state.saving)
                        CircularProgressIndicator(Modifier.padding(8.dp))
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        if (state.loadFailed)
                            TextButton(onRetry) { Text(stringResource(R.string.retry)) }
                    }
                    DictionaryRuleField.entries.forEach { field ->
                        val label =
                            when (field) {
                                DictionaryRuleField.Name -> R.string.name
                                DictionaryRuleField.UrlRule -> R.string.url_rule
                                DictionaryRuleField.ShowRule -> R.string.show_rule
                            }
                        var input by
                            rememberSaveable(field, stateSaver = TextFieldValue.Saver) {
                                mutableStateOf(
                                    TextFieldValue(
                                        state[field],
                                        TextRange(
                                            state.selections[field.ordinal].coerceIn(
                                                0,
                                                state[field].length,
                                            )
                                        ),
                                    )
                                )
                            }
                        LaunchedEffect(state[field], state.selections[field.ordinal]) {
                            val text = state[field]
                            val cursor = state.selections[field.ordinal].coerceIn(0, text.length)
                            // Retain IME composition for ordinary keystrokes; replace only external
                            // edits.
                            if (input.text != text || input.selection.start != cursor)
                                input = TextFieldValue(text, TextRange(cursor))
                        }
                        OutlinedTextField(
                            input,
                            {
                                input = it
                                onInput(field, it.text, it.selection.start)
                            },
                            enabled = enabled,
                            label = { Text(stringResource(label)) },
                            minLines = if (field == DictionaryRuleField.ShowRule) 4 else 1,
                            maxLines = 8,
                            visualTransformation =
                                if (field == DictionaryRuleField.ShowRule) syntax
                                else VisualTransformation.None,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag("dictionary-rule-${field.name}")
                                    .focusRequester(requesters.getValue(field))
                                    .onFocusChanged { if (it.isFocused) onFocus(field) },
                        )
                    }
                }
            }
        }
    }
    if (state.confirmExit)
        AlertDialog(
            onDismissRequest = onKeep,
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton(onKeep, Modifier.testTag("dictionary-rule-keep")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onDiscard, Modifier.testTag("dictionary-rule-discard")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}
