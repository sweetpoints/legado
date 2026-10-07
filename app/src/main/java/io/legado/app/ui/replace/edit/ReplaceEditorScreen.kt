package io.legado.app.ui.replace.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.widget.code.EditSafety

internal fun ReplaceEditorField.label() =
    when (this) {
        ReplaceEditorField.Name -> R.string.replace_rule_summary
        ReplaceEditorField.Group -> R.string.group
        ReplaceEditorField.Pattern -> R.string.replace_rule
        ReplaceEditorField.Replacement -> R.string.replace_to
        ReplaceEditorField.Scope -> R.string.replace_scope
        ReplaceEditorField.ExcludeScope -> R.string.replace_exclude_scope
        ReplaceEditorField.Timeout -> R.string.timeout_millisecond
        ReplaceEditorField.Sample -> R.string.replace_preview_input
    }

class ReplaceEditorActions(
    val field: (ReplaceEditorField, ReplaceEditorText) -> Unit = { _, _ -> },
    val focus: (ReplaceEditorField) -> Unit = {},
    val flags: (Boolean, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    val save: () -> Unit = {},
    val close: () -> Unit = {},
    val keep: () -> Unit = {},
    val discard: () -> Unit = {},
    val editor: (ReplaceEditorField?) -> Unit = {},
    val copy: () -> Unit = {},
    val paste: () -> Unit = {},
    val help: () -> Unit = {},
    val config: () -> Unit = {},
    val insert: (String) -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
    val retry: () -> Unit = {},
    val scroll: (Int) -> Unit = {},
    val retryEditor: () -> Unit = {},
    val discardEditor: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReplaceEditorScreen(
    state: ReplaceEditorState,
    actions: ReplaceEditorActions,
    keys: List<ReplaceEditorAssist> = emptyList(),
    keyboardRows: Int = 1,
    keyboardVisible: Boolean = false,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var helpMenu by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(state.focus) }
    val scroll = rememberScrollState()
    var scrollRestored by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.loaded) {
        if (state.loaded && !scrollRestored) {
            withFrameNanos {}
            scroll.scrollTo(state.scroll)
            scrollRestored = true
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }
            .collect {
                if (scrollRestored && currentState.loaded && currentState.scroll != it)
                    actions.scroll(it)
            }
    }
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.replace_rule_edit),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        actions.close,
                        Modifier.testTag("replace-editor-back"),
                        enabled = !state.busy && state.editor == null,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        { actions.editor(focused) },
                        Modifier.testTag("replace-editor-fullscreen"),
                        enabled = state.editable,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_code),
                            stringResource(R.string.edit_content),
                        )
                    }
                    IconButton(
                        actions.save,
                        Modifier.testTag("replace-editor-save"),
                        enabled = state.editable,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.action_save),
                        )
                    }
                    Box {
                        IconButton(
                            { menu = true },
                            Modifier.testTag("replace-editor-menu"),
                            enabled = state.editable,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_rule)) },
                                onClick = {
                                    menu = false
                                    actions.copy()
                                },
                                modifier = Modifier.testTag("replace-editor-copy"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paste_rule)) },
                                onClick = {
                                    menu = false
                                    actions.paste()
                                },
                                modifier = Modifier.testTag("replace-editor-paste"),
                            )
                        }
                    }
                },
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.editorReturning && !state.busy)
                Row {
                    TextButton(
                        actions.retryEditor,
                        Modifier.testTag("replace-editor-return-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(
                        actions.discardEditor,
                        Modifier.testTag("replace-editor-return-discard"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            if (!state.loaded && !state.busy)
                TextButton(actions.retry, Modifier.testTag("replace-editor-retry")) {
                    Text(stringResource(R.string.retry))
                }
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(10.dp)
                    .testTag("replace-editor-fields"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReplaceEditorField.entries
                    .filter { it != ReplaceEditorField.Sample }
                    .forEach { field ->
                        ReplaceField(field, state, actions) { focused = it }
                        if (field == ReplaceEditorField.Pattern) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Flag(
                                    stringResource(R.string.use_regex),
                                    state.draft.regex,
                                    state.editable,
                                    "regex",
                                ) {
                                    actions.flags(
                                        it,
                                        state.draft.title,
                                        state.draft.source,
                                        state.draft.content,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(actions.help, Modifier.testTag("replace-editor-help")) {
                                    Icon(
                                        painterResource(R.drawable.ic_help),
                                        stringResource(R.string.help),
                                    )
                                }
                            }
                        }
                        if (field == ReplaceEditorField.Replacement)
                            FlowRow {
                                Flag(
                                    stringResource(R.string.scope_title),
                                    state.draft.title,
                                    state.editable,
                                    "title",
                                ) {
                                    actions.flags(
                                        state.draft.regex,
                                        it,
                                        state.draft.source,
                                        state.draft.content,
                                    )
                                }
                                Flag(
                                    stringResource(R.string.scope_source),
                                    state.draft.source,
                                    state.editable,
                                    "source",
                                ) {
                                    actions.flags(
                                        state.draft.regex,
                                        state.draft.title,
                                        it,
                                        state.draft.content,
                                    )
                                }
                                Flag(
                                    stringResource(R.string.scope_content),
                                    state.draft.content,
                                    state.editable,
                                    "content",
                                ) {
                                    actions.flags(
                                        state.draft.regex,
                                        state.draft.title,
                                        state.draft.source,
                                        it,
                                    )
                                }
                            }
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ReplaceField(ReplaceEditorField.Sample, state, actions) { focused = it }
                    }
                    Column(
                        Modifier.weight(1f)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    focused = null
                                }
                            }
                            .clickable { focused = null }
                            .testTag("replace-editor-output")
                    ) {
                        Text(
                            stringResource(R.string.replace_preview_output),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        SelectionContainer {
                            Text(
                                state.preview,
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 112.dp)
                                    .testTag("replace-editor-output-text"),
                            )
                        }
                        if (state.previewFailed)
                            Text(
                                stringResource(
                                    when (state.previewError) {
                                        ReplaceEditorPreviewIssue.Timeout ->
                                            R.string.replace_preview_timeout
                                        ReplaceEditorPreviewIssue.ContextUnavailable ->
                                            R.string.replace_preview_context_unavailable
                                        ReplaceEditorPreviewIssue.JsEvaluation ->
                                            R.string.replace_preview_js_error
                                        null -> R.string.replace_preview_error
                                    }
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("replace-editor-preview-error"),
                            )
                    }
                }
            }
            if (keyboardVisible) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    LazyHorizontalGrid(
                        GridCells.Fixed(keyboardRows.coerceIn(1, 5)),
                        Modifier.fillMaxWidth()
                            .height((48 * keyboardRows.coerceIn(1, 5)).dp)
                            .testTag("replace-editor-keyboard"),
                    ) {
                        item("help") {
                            Box {
                                TextButton(
                                    { helpMenu = true },
                                    Modifier.testTag("replace-editor-key-help"),
                                ) {
                                    Text("❓")
                                }
                                DropdownMenu(helpMenu, { helpMenu = false }) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.assists_key_config))
                                        },
                                        onClick = {
                                            helpMenu = false
                                            actions.config()
                                        },
                                        modifier = Modifier.testTag("replace-editor-key-config"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.help)) },
                                        onClick = {
                                            helpMenu = false
                                            actions.help()
                                        },
                                    )
                                }
                            }
                        }
                        item("undo") {
                            TextButton(
                                actions.undo,
                                Modifier.testTag("replace-editor-undo"),
                                enabled = state.editable && focused != null,
                            ) {
                                Text("↩️")
                            }
                        }
                        item("redo") {
                            TextButton(
                                actions.redo,
                                Modifier.testTag("replace-editor-redo"),
                                enabled = state.editable && focused != null,
                            ) {
                                Text("↪️")
                            }
                        }
                        items(keys, key = { "assist-" + it.key }) { key ->
                            TextButton(
                                { if (focused != null) actions.insert(key.value) },
                                Modifier.testTag("replace-editor-key-" + key.key),
                                enabled = state.editable && focused != null,
                            ) {
                                Text(key.key)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.exit)
        AlertDialog(
            onDismissRequest = actions.keep,
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton(actions.keep, Modifier.testTag("replace-editor-keep")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(actions.discard, Modifier.testTag("replace-editor-discard")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}

@Composable
private fun Flag(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    changed: (Boolean) -> Unit,
) {
    Row(
        Modifier.heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = changed)
            .testTag("replace-editor-" + tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ReplaceField(
    field: ReplaceEditorField,
    state: ReplaceEditorState,
    actions: ReplaceEditorActions,
    focus: (ReplaceEditorField) -> Unit,
) {
    val value = state.draft[field]
    val unsafe =
        remember(value.text) {
            field != ReplaceEditorField.Sample &&
                (EditSafety.isTooLongForInline(value.text) ||
                    EditSafety.isCombiningHeavy(value.text))
        }
    if (unsafe) {
        OutlinedCard(
            onClick = {
                focus(field)
                actions.focus(field)
                actions.editor(field)
            },
            enabled = state.editable,
            modifier = Modifier.fillMaxWidth().testTag("replace-editor-" + field.name),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(field.label()), style = MaterialTheme.typography.labelMedium)
                Text(
                    if (EditSafety.isTooLongForInline(value.text))
                        stringResource(R.string.large_text_placeholder, value.text.length)
                    else stringResource(R.string.combining_text_placeholder),
                    maxLines = EditSafety.PREVIEW_LINES,
                )
            }
        }
    } else {
        var local by
            remember(field) {
                mutableStateOf(TextFieldValue(value.text, TextRange(value.start, value.end)))
            }
        val requester = remember(field) { FocusRequester() }
        var editorWasOpen by remember(field) { mutableStateOf(state.editor != null) }
        LaunchedEffect(state.editor, state.loaded) {
            if (state.editor == null && editorWasOpen && state.loaded && state.focus == field)
                requester.requestFocus()
            editorWasOpen = state.editor != null
        }
        SideEffect {
            if (local.text != value.text || local.selection != TextRange(value.start, value.end))
                local = TextFieldValue(value.text, TextRange(value.start, value.end))
        }
        OutlinedTextField(
            local,
            { next ->
                local = next
                actions.field(
                    field,
                    ReplaceEditorText(next.text, next.selection.start, next.selection.end),
                )
            },
            Modifier.fillMaxWidth()
                .testTag("replace-editor-" + field.name)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        focus(field)
                        actions.focus(field)
                    }
                },
            label = { Text(stringResource(field.label())) },
            enabled = state.editable,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        if (field == ReplaceEditorField.Timeout) KeyboardType.Number
                        else KeyboardType.Text
                ),
            minLines = if (field == ReplaceEditorField.Sample) 4 else 1,
            maxLines = if (field == ReplaceEditorField.Sample) 8 else Int.MAX_VALUE,
        )
    }
}
