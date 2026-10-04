package io.legado.app.ui.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

internal data class CodeEditorAssist(val key: String, val value: String)

internal enum class CodeEditorAction {
    SEARCH,
    SAVE,
    DEBUG,
    LOGIN,
    PREVIOUS,
    NEXT,
    REPLACE,
    REPLACE_ALL,
    THEME,
    SELECT_ALL,
    FORMAT,
    SYNTAX,
    CURL,
    SETTINGS,
    WRAP,
    LOG,
    HELP,
    KEYBOARD_CONFIG,
    RULE_HELP,
    RSS_HELP,
    JS_HELP,
    REGEX_HELP,
    UNDO,
    REDO,
}

/** All page controls are Compose; editorContent contains only the chosen native text surface. */
@Composable
internal fun CodeEditorScreen(
    state: CodeEditorComposeState,
    status: CodeEditorEngineStatus,
    safe: Boolean,
    keyboardVisible: Boolean,
    keyboardRows: Int,
    autoWrap: Boolean = false,
    assists: List<CodeEditorAssist>,
    onAction: (CodeEditorAction) -> Unit,
    onSearch: (CodeEditorSearch) -> Unit,
    onInsert: (String) -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    editorContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val enabled =
        !state.busy &&
            session?.returnReceipt == null &&
            !status.reading &&
            !status.replacing &&
            status.ready
    var menuVisible by remember { mutableStateOf(false) }
    var helpVisible by remember { mutableStateOf(false) }
    var focusedSearch by remember(state.engineOwner) { mutableStateOf<Int?>(null) }
    val search = session?.search ?: CodeEditorSearch()
    val queryFocus = remember { FocusRequester() }
    val replacementFocus = remember { FocusRequester() }
    LaunchedEffect(search.visible, search.replaceVisible) {
        if (search.visible && !safe) {
            if (search.replaceVisible) replacementFocus.requestFocus()
            else queryFocus.requestFocus()
        }
    }
    var query by
        remember(state.engineOwner) {
            mutableStateOf(TextFieldValue(search.query, search.querySelection.range()))
        }
    var replacement by
        remember(state.engineOwner) {
            mutableStateOf(TextFieldValue(search.replacement, search.replacementSelection.range()))
        }
    LaunchedEffect(
        search.query,
        search.querySelection,
        search.replacement,
        search.replacementSelection,
    ) {
        if (query.text != search.query || query.selection != search.querySelection.range()) {
            query = TextFieldValue(search.query, search.querySelection.range())
        }
        if (
            replacement.text != search.replacement ||
                replacement.selection != search.replacementSelection.range()
        ) {
            replacement = TextFieldValue(search.replacement, search.replacementSelection.range())
        }
    }
    fun changeQuery(value: TextFieldValue) {
        query = value
        onSearch(search.copy(query = value.text, querySelection = value.selection.selection()))
    }
    fun changeReplacement(value: TextFieldValue) {
        replacement = value
        onSearch(
            search.copy(
                replacement = value.text,
                replacementSelection = value.selection.selection(),
            )
        )
    }
    fun insertAssist(text: String) {
        when (focusedSearch) {
            0 -> changeQuery(query.insert(text))
            1 -> changeReplacement(replacement.insert(text))
            else -> onInsert(text)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .testTag("code-toolbar")
            ) {
                TextButton(onClick = onExit, enabled = !state.busy) { Text("‹") }
                Text(
                    session?.title ?: stringResource(R.string.edit_code),
                    modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                    maxLines = 1,
                )
                if (!safe) {
                    TextButton(
                        onClick = { onAction(CodeEditorAction.SEARCH) },
                        enabled = enabled,
                        modifier = Modifier.testTag("code-search-toggle"),
                    ) {
                        Text(stringResource(R.string.search))
                    }
                }
                if (session?.writable == true) {
                    TextButton(
                        onClick = { onAction(CodeEditorAction.SAVE) },
                        enabled = enabled,
                        modifier = Modifier.testTag("code-save"),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
                if (
                    session != null &&
                        shouldShowDebugSourceAction(session.writable, session.showDebugSource)
                ) {
                    TextButton(
                        onClick = { onAction(CodeEditorAction.DEBUG) },
                        enabled = enabled,
                        modifier = Modifier.testTag("code-debug"),
                    ) {
                        Text(stringResource(R.string.debug_source))
                    }
                }
                Column {
                    TextButton(onClick = { menuVisible = true }, enabled = !state.busy) {
                        Text("⋮")
                    }
                    DropdownMenu(
                        expanded = menuVisible,
                        onDismissRequest = { menuVisible = false },
                    ) {
                        fun action(action: CodeEditorAction) {
                            menuVisible = false
                            onAction(action)
                        }
                        if (
                            session != null &&
                                shouldShowLoginSourceAction(
                                    session.writable,
                                    session.showLoginSource,
                                )
                        ) {
                            CodeEditorMenuItem(R.string.login, enabled) {
                                action(CodeEditorAction.LOGIN)
                            }
                        }
                        if (!safe) {
                            CodeEditorMenuItem(R.string.change_theme, enabled) {
                                action(CodeEditorAction.THEME)
                            }
                            CodeEditorMenuItem(R.string.select_all, enabled) {
                                action(CodeEditorAction.SELECT_ALL)
                            }
                            CodeEditorMenuItem(R.string.format_code, enabled) {
                                action(CodeEditorAction.FORMAT)
                            }
                            if (
                                shouldShowJavaScriptSyntaxAction(
                                    safe,
                                    session?.checkJavaScriptSyntax == true,
                                )
                            ) {
                                CodeEditorMenuItem(R.string.check_javascript_syntax, enabled) {
                                    action(CodeEditorAction.SYNTAX)
                                }
                            }
                            CodeEditorMenuItem(R.string.config_settings, enabled) {
                                action(CodeEditorAction.SETTINGS)
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.auto_wrap)) },
                                leadingIcon = {
                                    Checkbox(checked = autoWrap, onCheckedChange = null)
                                },
                                enabled = enabled,
                                onClick = { action(CodeEditorAction.WRAP) },
                            )
                        }
                        CodeEditorMenuItem(R.string.curl_analyze_url_converter, enabled) {
                            action(CodeEditorAction.CURL)
                        }
                        CodeEditorMenuItem(R.string.log, !state.busy) {
                            action(CodeEditorAction.LOG)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (
                state.busy ||
                    status.reading ||
                    status.replacing ||
                    (session != null && !status.ready && !status.failed)
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("code-progress"))
            }
            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
                Row {
                    Button(
                        onClick = onRetry,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("code-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = onExit, enabled = !state.busy) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
            status.replacementError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }
            if (status.failed) {
                Text(
                    stringResource(R.string.safe_code_editor_load_failed),
                    modifier = Modifier.padding(8.dp),
                )
                Button(onClick = onRestart, enabled = !state.busy) {
                    Text(stringResource(R.string.retry))
                }
            }
            editorContent(Modifier.weight(1f).fillMaxWidth().testTag("code-editor-surface"))
            if (!safe && search.visible) {
                Column(Modifier.fillMaxWidth().testTag("code-search")) {
                    Row {
                        OutlinedTextField(
                            value = query,
                            onValueChange = ::changeQuery,
                            enabled = enabled,
                            label = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier =
                                Modifier.weight(1f)
                                    .testTag("code-query")
                                    .focusRequester(queryFocus)
                                    .onFocusChanged {
                                        if (it.isFocused) focusedSearch = 0
                                        else if (focusedSearch == 0) focusedSearch = null
                                    },
                        )
                        TextButton(onClick = { onSearch(search.copy(visible = false)) }) {
                            Text("×")
                        }
                    }
                    Row {
                        Checkbox(
                            checked = search.regex,
                            enabled = enabled,
                            onCheckedChange = { onSearch(search.copy(regex = it)) },
                        )
                        Text(stringResource(R.string.regex), Modifier.padding(top = 12.dp))
                        Text(status.searchResult, Modifier.weight(1f).padding(12.dp))
                        TextButton(
                            onClick = { onAction(CodeEditorAction.PREVIOUS) },
                            enabled = enabled,
                        ) {
                            Text("↑")
                        }
                        TextButton(
                            onClick = { onAction(CodeEditorAction.NEXT) },
                            enabled = enabled,
                        ) {
                            Text("↓")
                        }
                        TextButton(
                            onClick = {
                                if (search.replaceVisible) onAction(CodeEditorAction.REPLACE)
                                else onSearch(search.copy(replaceVisible = true))
                            },
                            enabled = enabled,
                        ) {
                            Text(stringResource(R.string.replace))
                        }
                    }
                    if (search.replaceVisible) {
                        Row {
                            OutlinedTextField(
                                value = replacement,
                                onValueChange = ::changeReplacement,
                                enabled = enabled,
                                singleLine = true,
                                label = { Text(stringResource(R.string.replace)) },
                                modifier =
                                    Modifier.weight(1f)
                                        .testTag("code-replacement")
                                        .focusRequester(replacementFocus)
                                        .onFocusChanged {
                                            if (it.isFocused) focusedSearch = 1
                                            else if (focusedSearch == 1) focusedSearch = null
                                        },
                            )
                            TextButton(
                                onClick = { onAction(CodeEditorAction.REPLACE_ALL) },
                                enabled = enabled,
                                modifier = Modifier.testTag("code-replace-all"),
                            ) {
                                Text(stringResource(R.string.replace_all))
                            }
                            TextButton(
                                onClick = { onSearch(search.copy(replaceVisible = false)) }
                            ) {
                                Text("×")
                            }
                        }
                    }
                }
            }
            if (keyboardVisible) {
                val rows = keyboardRows.coerceIn(1, 5)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(rows),
                    modifier =
                        Modifier.fillMaxWidth().height((rows * 40).dp).testTag("code-keyboard"),
                ) {
                    item {
                        TextButton(onClick = { helpVisible = true }, enabled = enabled) {
                            Text("❓")
                        }
                    }
                    item {
                        TextButton(
                            onClick = { onAction(CodeEditorAction.UNDO) },
                            enabled = enabled,
                        ) {
                            Text("↩️")
                        }
                    }
                    item {
                        TextButton(
                            onClick = { onAction(CodeEditorAction.REDO) },
                            enabled = enabled,
                        ) {
                            Text("↪️")
                        }
                    }
                    items(assists) { assist ->
                        TextButton(onClick = { insertAssist(assist.value) }, enabled = enabled) {
                            Text(assist.key)
                        }
                    }
                }
            }
        }
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onDiscard, modifier = Modifier.testTag("code-discard-confirm")) { Text(stringResource(R.string.no)) }
            },
        )
    }
    if (helpVisible) {
        AlertDialog(
            onDismissRequest = { helpVisible = false },
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column {
                    listOf(
                            "键盘辅助配置" to CodeEditorAction.KEYBOARD_CONFIG,
                            "书源教程" to CodeEditorAction.RULE_HELP,
                            "订阅源教程" to CodeEditorAction.RSS_HELP,
                            "js教程" to CodeEditorAction.JS_HELP,
                            "正则教程" to CodeEditorAction.REGEX_HELP,
                        )
                        .forEach { (label, action) ->
                            TextButton(
                                onClick = {
                                    helpVisible = false
                                    onAction(action)
                                }
                            ) {
                                Text(label)
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { helpVisible = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun CodeEditorMenuItem(resource: Int, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(resource)) },
        enabled = enabled,
        onClick = onClick,
    )
}

private fun CodeEditorSelection.range() = TextRange(start, end)

private fun TextRange.selection() = CodeEditorSelection(start, end)

private fun TextFieldValue.insert(text: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    return TextFieldValue(this.text.replaceRange(start, end, text), TextRange(start + text.length))
}
