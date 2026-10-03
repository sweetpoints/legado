package io.legado.app.ui.book.toc.rule

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
fun TxtTocRuleEditorScreen(
    state: TxtTocRuleEditorUiState,
    onInput: (TxtTocEditorField, String, Int) -> Unit,
    onFocus: (TxtTocEditorField) -> Unit,
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
            TxtTocRuleEditorSyntax(orange, blue, grey, lightBlue)
        }
    val requesters = remember { TxtTocEditorField.entries.associateWith { FocusRequester() } }
    LaunchedEffect(state.focused, state.loading) {
        if (!state.loading) state.focused?.let { requesters.getValue(it).requestFocus() }
    }
    val closeLabel = stringResource(R.string.close)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .testTag("txt-toc-editor-backdrop")
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
                    .testTag("txt-toc-editor-card"),
        ) {
            Column {
                LegadoTopAppBar(
                    stringResource(R.string.txt_toc_rule),
                    onClose,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    actions = {
                        IconButton(
                            onFullEdit,
                            enabled = enabled,
                            modifier = Modifier.testTag("txt-toc-editor-fullscreen"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_code),
                                stringResource(R.string.edit_content),
                            )
                        }
                        IconButton(
                            onSave,
                            enabled = enabled,
                            modifier = Modifier.testTag("txt-toc-editor-save"),
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
                                modifier = Modifier.testTag("txt-toc-editor-menu"),
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
                                    modifier = Modifier.testTag("txt-toc-editor-copy"),
                                )
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.paste_rule)) },
                                    {
                                        menu = false
                                        onPaste()
                                    },
                                    modifier = Modifier.testTag("txt-toc-editor-paste"),
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
                    TxtTocEditorField.entries.forEach { field ->
                        val label =
                            when (field) {
                                TxtTocEditorField.Name -> R.string.name
                                TxtTocEditorField.Regex -> R.string.regex
                                TxtTocEditorField.Replacement -> R.string.replace_to_js
                                TxtTocEditorField.Example -> R.string.example
                            }
                        var input by
                            rememberSaveable(field, stateSaver = TextFieldValue.Saver) {
                                mutableStateOf(
                                    TextFieldValue(
                                        state[field],
                                        TextRange(
                                            state.cursors[field.ordinal].coerceIn(
                                                0,
                                                state[field].length,
                                            )
                                        ),
                                    )
                                )
                            }
                        LaunchedEffect(state[field], state.cursors[field.ordinal]) {
                            val text = state[field]
                            val cursor = state.cursors[field.ordinal].coerceIn(0, text.length)
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
                            minLines = if (field == TxtTocEditorField.Replacement) 4 else 1,
                            maxLines = 8,
                            visualTransformation =
                                if (field == TxtTocEditorField.Replacement) syntax
                                else VisualTransformation.None,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag("txt-toc-editor-${field.name}")
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
                TextButton(onKeep, Modifier.testTag("txt-toc-editor-keep")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onDiscard, Modifier.testTag("txt-toc-editor-discard")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}
