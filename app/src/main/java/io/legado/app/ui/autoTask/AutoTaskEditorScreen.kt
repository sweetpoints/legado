package io.legado.app.ui.autoTask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.*
import kotlinx.coroutines.launch

internal fun AutoTaskEditorField.label() =
    when (this) {
        AutoTaskEditorField.Name -> R.string.auto_task_name
        AutoTaskEditorField.Cron -> R.string.auto_task_cron
        AutoTaskEditorField.Comment -> R.string.auto_task_comment
        AutoTaskEditorField.Script -> R.string.auto_task_script
        AutoTaskEditorField.Header -> R.string.auto_task_header
        AutoTaskEditorField.JsLib -> R.string.auto_task_js_lib
        AutoTaskEditorField.ConcurrentRate -> R.string.auto_task_concurrent_rate
        AutoTaskEditorField.LoginUrl -> R.string.login_url
        AutoTaskEditorField.LoginUi -> R.string.login_ui
        AutoTaskEditorField.LoginCheckJs -> R.string.login_check_js
    }

internal fun AutoTaskEditorIssue.label() =
    when (this) {
        AutoTaskEditorIssue.Name -> R.string.auto_task_name_required
        AutoTaskEditorIssue.Cron -> R.string.auto_task_cron_invalid
        AutoTaskEditorIssue.Script -> R.string.auto_task_script_empty
        AutoTaskEditorIssue.Format -> R.string.wrong_format
        AutoTaskEditorIssue.Focus -> R.string.please_focus_cursor_on_textbox
        AutoTaskEditorIssue.NoLogin -> R.string.source_no_login
    }

class AutoTaskEditorActions(
    val field: (AutoTaskEditorField, AutoTaskEditorText) -> Unit = { _, _ -> },
    val focus: (AutoTaskEditorField) -> Unit = {},
    val enabled: (Boolean) -> Unit = {},
    val cookieJar: (Boolean) -> Unit = {},
    val save: (AutoTaskEditorSaveAction) -> Unit = {},
    val exit: () -> Unit = {},
    val keep: () -> Unit = {},
    val discard: () -> Unit = {},
    val editor: () -> Unit = {},
    val copy: () -> Unit = {},
    val paste: () -> Unit = {},
    val help: () -> Unit = {},
    val retry: () -> Unit = {},
    val retryEditor: () -> Unit = {},
    val discardEditor: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTaskEditorScreen(state: AutoTaskEditorState, actions: AutoTaskEditorActions) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var navigation by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.auto_task_edit),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        actions.exit,
                        Modifier.testTag("task-editor-back"),
                        enabled = !state.busy && !state.editorPending,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        actions.editor,
                        Modifier.testTag("task-editor-fullscreen"),
                        enabled = state.canEdit,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_code),
                            stringResource(R.string.edit_content),
                        )
                    }
                    IconButton(
                        { actions.save(AutoTaskEditorSaveAction.Close) },
                        Modifier.testTag("task-editor-save"),
                        enabled = state.canEdit,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.action_save),
                        )
                    }
                    Box {
                        IconButton(
                            { menu = true },
                            Modifier.testTag("task-editor-menu"),
                            enabled = !state.busy,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            fun close(action: () -> Unit) {
                                menu = false
                                action()
                            }
                            DropdownMenuItem(
                                { Text(stringResource(R.string.auto_task_debug)) },
                                { close { actions.save(AutoTaskEditorSaveAction.Debug) } },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.login)) },
                                { close { actions.save(AutoTaskEditorSaveAction.Login) } },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.copy_rule)) },
                                { close(actions.copy) },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.paste_rule)) },
                                { close(actions.paste) },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.help)) },
                                { close(actions.help) },
                            )
                        }
                    }
                },
            )
            Box(Modifier.fillMaxWidth()) {
                TextButton(
                    { navigation = true },
                    Modifier.fillMaxWidth().testTag("task-editor-navigation"),
                    enabled = state.canEdit,
                ) {
                    Text(stringResource((state.focus ?: AutoTaskEditorField.Name).label()))
                }
                DropdownMenu(navigation, { navigation = false }) {
                    AutoTaskEditorField.entries.forEach { field ->
                        DropdownMenuItem(
                            { Text(stringResource(field.label())) },
                            {
                                navigation = false
                                scope.launch {
                                    list.animateScrollToItem(field.ordinal + 1)
                                    actions.focus(field)
                                }
                            },
                            modifier = Modifier.testTag("task-editor-navigate-" + field.name),
                        )
                    }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.issue != null || state.error != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        state.issue?.let { stringResource(it.label()) } ?: state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.error != null)
                        Row {
                            TextButton(
                                if (state.editorPending) actions.retryEditor else actions.retry,
                                enabled = !state.busy,
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            if (state.editorPending)
                                TextButton(
                                    actions.discardEditor,
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag("task-editor-discard-result"),
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                        }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("task-editor-fields"),
                state = list,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("flags") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            state.draft.enabled,
                            actions.enabled,
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("task-editor-enabled"),
                        )
                        Text(stringResource(R.string.enable), Modifier.weight(1f))
                        Checkbox(
                            state.draft.cookieJar,
                            actions.cookieJar,
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("task-editor-cookie"),
                        )
                        Text(stringResource(R.string.auto_task_cookie_jar), Modifier.weight(1f))
                    }
                }
                items(AutoTaskEditorField.entries, key = { it.name }) { field ->
                    val value = state.draft[field]
                    var local by
                        remember(field) {
                            mutableStateOf(
                                TextFieldValue(value.text, TextRange(value.start, value.end))
                            )
                        }
                    val requester = remember(field) { FocusRequester() }
                    SideEffect {
                        if (
                            local.text != value.text ||
                                local.selection != TextRange(value.start, value.end)
                        ) {
                            local = TextFieldValue(value.text, TextRange(value.start, value.end))
                        }
                    }
                    LaunchedEffect(field, state.focus, state.loading) {
                        if (!state.loading && state.focus == field) requester.requestFocus()
                    }
                    OutlinedTextField(
                        local,
                        { next ->
                            local = next
                            actions.field(
                                field,
                                AutoTaskEditorText(
                                    next.text,
                                    next.selection.start,
                                    next.selection.end,
                                ),
                            )
                        },
                        Modifier.fillMaxWidth()
                            .testTag("task-editor-" + field.name)
                            .focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) actions.focus(field) },
                        label = { Text(stringResource(field.label())) },
                        enabled = state.canEdit,
                        singleLine = !field.code && field != AutoTaskEditorField.Comment,
                        minLines =
                            if (field.code) 3
                            else if (field == AutoTaskEditorField.Comment) 2 else 1,
                        maxLines = if (field.code) 8 else 3,
                        textStyle =
                            LocalTextStyle.current.copy(
                                fontFamily =
                                    if (field.code) FontFamily.Monospace else FontFamily.Default
                            ),
                        supportingText =
                            if (field == AutoTaskEditorField.Cron)
                                ({ Text(stringResource(R.string.auto_task_cron_hint)) })
                            else null,
                    )
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
                TextButton(actions.keep, Modifier.testTag("task-editor-keep")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(actions.discard, Modifier.testTag("task-editor-discard")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}
