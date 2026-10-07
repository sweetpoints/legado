package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.dialog.CodeSyntaxColors
import io.legado.app.ui.widget.dialog.projectCodeSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BookSourceScreenActions(
    val field: (Int, String, String, Int, Int) -> Unit,
    val focus: (Int, String) -> Unit,
    val tab: (Int) -> Unit,
    val options: (BookSourceEditOptions) -> Unit,
    val expanded: (Boolean) -> Unit,
    val save: (BookSourceSaveAction) -> Unit,
    val native: (BookSourceNativeAction, String?) -> Unit,
    val autoComplete: () -> Unit,
    val paste: () -> Unit,
    val clearCookie: () -> Unit,
    val insert: (String) -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val groups: () -> Unit,
    val dismissGroups: () -> Unit,
    val variableEdit: (String) -> Unit,
    val variableSave: (String?) -> Unit,
    val dismissVariable: () -> Unit,
    val cancel: () -> Unit,
    val discard: () -> Unit,
    val keepEditing: () -> Unit,
    val retry: () -> Unit,
    val migration: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSourceEditScreen(
    state: BookSourceComposeState,
    actions: BookSourceScreenActions,
    maxLines: Int,
    keyboardRows: Int,
    keyboardVisible: Boolean,
    transparentBackground: Boolean = false,
) {
    val document = state.document
    var menuOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navigationState = rememberLazyListState()
    val enabled = document != null && !state.busy && !document.finished
    val tabs =
        listOf(
            R.string.source_tab_base,
            R.string.source_tab_search,
            R.string.source_tab_find,
            R.string.source_tab_info,
            R.string.source_tab_toc,
            R.string.source_tab_content,
            R.string.source_tab_review,
        )
    val selectedTab = document?.selectedTab?.coerceIn(0, 6) ?: 0
    val fields = document?.form?.tabs?.getOrNull(selectedTab).orEmpty()
    LaunchedEffect(selectedTab) { listState.scrollToItem(0) }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        navigationState.animateScrollToItem(listState.firstVisibleItemIndex)
    }
    val background =
        if (transparentBackground) Color.Transparent else MaterialTheme.colorScheme.background
    Surface(Modifier.fillMaxSize(), color = background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    actions.cancel,
                    enabled = enabled,
                    modifier = Modifier.testTag("source-close"),
                ) {
                    Text(stringResource(R.string.close))
                }
                Text(
                    stringResource(R.string.edit_book_source),
                    Modifier.weight(1f).padding(top = 12.dp),
                )
                TextButton(
                    { actions.native(BookSourceNativeAction.EDITOR, null) },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-fullscreen"),
                ) {
                    Text(stringResource(R.string.edit_content))
                }
                TextButton(
                    { actions.save(BookSourceSaveAction.FINISH) },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-save"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
                Box {
                    TextButton(
                        { menuOpen = true },
                        enabled = enabled,
                        modifier = Modifier.testTag("source-menu"),
                    ) {
                        Text("⋮")
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        BookSourceMenu(state, { menuOpen = false }, actions)
                    }
                }
            }
            if (state.busy || document == null)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("source-progress"))
            state.error?.let { message ->
                Row {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        actions.retry,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("source-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            if (document != null && !document.finished) {
                BookSourceOptions(document, enabled, actions)
                BookSourceEngineStatus(state, enabled, actions.migration)
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = background,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = index == selectedTab,
                            onClick = { actions.tab(index) },
                            enabled = enabled,
                            modifier = Modifier.testTag("source-tab-$index"),
                            text = { Text(stringResource(title)) },
                        )
                    }
                }
                LazyRow(
                    state = navigationState,
                    modifier = Modifier.testTag("source-field-navigation"),
                ) {
                    itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                        TextButton(
                            {
                                actions.focus(selectedTab, field.key)
                                scope.launch { listState.animateScrollToItem(index) }
                            },
                            enabled = enabled,
                        ) {
                            Text(
                                fieldLabel(field),
                                color =
                                    if (index == listState.firstVisibleItemIndex)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("source-fields"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(fields, key = { _, field -> "$selectedTab:${field.key}" }) {
                        _,
                        field ->
                        BookSourceField(
                            field,
                            selectedTab,
                            document.focusedKey == field.key,
                            enabled,
                            maxLines,
                            actions,
                        )
                    }
                }
                val rows = keyboardRows.coerceIn(1, 5)
                if (keyboardVisible)
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(rows),
                        modifier =
                            Modifier.fillMaxWidth()
                                .height((rows * 40).dp)
                                .testTag("source-keyboard"),
                    ) {
                        item { TextButton({ helpOpen = true }, enabled = enabled) { Text("❓") } }
                        item { TextButton(actions.undo, enabled = enabled) { Text("↩️") } }
                        item { TextButton(actions.redo, enabled = enabled) { Text("↪️") } }
                        items(state.assists, key = { it.key }) { assist ->
                            TextButton({ actions.insert(assist.value) }, enabled = enabled) {
                                Text(assist.key)
                            }
                        }
                    }
            }
        }
    }
    if (state.confirmDiscard)
        AlertDialog(
            onDismissRequest = actions.keepEditing,
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton(actions.keepEditing) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = { TextButton(actions.discard) { Text(stringResource(R.string.no)) } },
        )
    state.groups?.let { groups ->
        AlertDialog(
            onDismissRequest = actions.dismissGroups,
            title = { Text(stringResource(R.string.source_group)) },
            text = {
                LazyColumn {
                    itemsIndexed(groups) { _, group ->
                        TextButton({
                            actions.insert(group)
                            actions.dismissGroups()
                        }) {
                            Text(group)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(actions.dismissGroups) { Text(stringResource(R.string.close)) }
            },
        )
    }
    state.variable?.let { value ->
        AlertDialog(
            onDismissRequest = actions.dismissVariable,
            title = { Text(stringResource(R.string.set_source_variable)) },
            text = {
                Column {
                    Text(state.variableComment.orEmpty())
                    OutlinedTextField(
                        value,
                        actions.variableEdit,
                        modifier = Modifier.testTag("source-variable"),
                    )
                }
            },
            confirmButton = {
                TextButton({ actions.variableSave(value) }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(actions.dismissVariable, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (helpOpen)
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column {
                    TextButton({
                        helpOpen = false
                        actions.native(BookSourceNativeAction.KEYBOARD_CONFIG, null)
                    }) {
                        Text(stringResource(R.string.assists_key_config))
                    }
                    TextButton({
                        helpOpen = false
                        actions.native(BookSourceNativeAction.URL_OPTIONS, null)
                    }) {
                        Text("插入URL参数")
                    }
                    listOf("书源教程" to "ruleHelp", "js教程" to "jsHelp", "正则教程" to "regexHelp")
                        .forEach { (label, key) ->
                            TextButton({
                                helpOpen = false
                                actions.native(BookSourceNativeAction.HELP, key)
                            }) {
                                Text(label)
                            }
                        }
                    if (document?.focusedKey == "bookSourceGroup")
                        TextButton({
                            helpOpen = false
                            actions.groups()
                        }) {
                            Text("插入分组")
                        }
                    else
                        TextButton({
                            helpOpen = false
                            actions.native(BookSourceNativeAction.FILE, null)
                        }) {
                            Text("选择文件")
                        }
                }
            },
            confirmButton = {
                TextButton({ helpOpen = false }) { Text(stringResource(R.string.close)) }
            },
        )
}

@Composable
private fun BookSourceMenu(
    state: BookSourceComposeState,
    close: () -> Unit,
    actions: BookSourceScreenActions,
) {
    val document = state.document ?: return
    val entries =
        listOf(
            R.string.debug_source to { actions.save(BookSourceSaveAction.DEBUG) },
            R.string.search to { actions.save(BookSourceSaveAction.SEARCH) },
            R.string.cookie to actions.clearCookie,
            R.string.auto_complete to actions.autoComplete,
            R.string.copy_source to { actions.native(BookSourceNativeAction.COPY, null) },
            R.string.paste_source to actions.paste,
            R.string.import_by_qr_code to { actions.native(BookSourceNativeAction.QR, null) },
            R.string.str_share to { actions.native(BookSourceNativeAction.SHARE, null) },
            R.string.qr_share to { actions.native(BookSourceNativeAction.QR_SHARE, null) },
            R.string.set_source_variable to { actions.save(BookSourceSaveAction.VARIABLE) },
            R.string.log to { actions.native(BookSourceNativeAction.LOG, null) },
            R.string.help to { actions.native(BookSourceNativeAction.HELP, "ruleHelp") },
        )
    if (document.form.hasLogin())
        DropdownMenuItem(
            text = { Text(stringResource(R.string.login)) },
            onClick = {
                close()
                actions.save(BookSourceSaveAction.LOGIN)
            },
        )
    entries.forEach { (label, action) ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(label) +
                        if (label == R.string.auto_complete && document.autoComplete) " ✓" else ""
                )
            },
            onClick = {
                close()
                action()
            },
        )
    }
}

@Composable
private fun BookSourceEngineStatus(
    state: BookSourceComposeState,
    enabled: Boolean,
    onMigration: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("source-engine-status")) {
        Text("书源引擎：Flutter/V8", style = MaterialTheme.typography.labelLarge)
        Text(
            "旧格式书源由新引擎兼容解析。需要调整的规则可先预览迁移结果。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = onMigration,
            enabled = enabled && state.migrationAvailable && !state.migrationRunning,
            modifier = Modifier.testTag("sourceMigrationPreview"),
        ) {
            Text("预览迁移")
        }
        if (!BuildConfig.FLUTTER_SOURCE_ENGINE) {
            Text(
                "当前安装包未包含 Flutter/V8 引擎，请安装支持此引擎的版本。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("source-engine-unavailable"),
            )
        }
    }
}

@Composable
private fun BookSourceOptions(
    document: BookSourceEditDocument,
    enabled: Boolean,
    actions: BookSourceScreenActions,
) {
    val options = document.form.options
    val types = stringArrayResource(R.array.book_type)
    var typeOpen by remember { mutableStateOf(false) }
    val choices =
        listOf(
            Triple(R.string.is_enable, options.enabled) { value: Boolean ->
                options.copy(enabled = value)
            },
            Triple(R.string.discovery, options.enabledExplore) { value: Boolean ->
                options.copy(enabledExplore = value)
            },
            Triple(R.string.auto_save_cookie, options.enabledCookieJar) { value: Boolean ->
                options.copy(enabledCookieJar = value)
            },
            Triple(R.string.review, options.enabledReview) { value: Boolean ->
                options.copy(enabledReview = value)
            },
            Triple(R.string.is_event_listener, options.eventListener) { value: Boolean ->
                options.copy(eventListener = value)
            },
            Triple(R.string.custom_button, options.customButton) { value: Boolean ->
                options.copy(customButton = value)
            },
        )
    val summary =
        listOf(types.getOrElse(options.type) { types.first() }) +
            choices.filter { it.second }.map { stringResource(it.first) }
    TextButton(
        { actions.expanded(!document.optionsExpanded) },
        enabled = enabled,
        modifier = Modifier.testTag("source-options-toggle"),
    ) {
        Text(summary.joinToString(" | "))
    }
    if (document.optionsExpanded) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            choices.forEach { (label, checked, updated) ->
                Row {
                    Checkbox(
                        checked,
                        { actions.options(updated(it)) },
                        enabled = enabled,
                        modifier = Modifier.testTag("source-option-$label"),
                    )
                    Text(stringResource(label), Modifier.padding(top = 12.dp))
                }
            }
        }
        Box {
            TextButton(
                { typeOpen = true },
                enabled = enabled,
                modifier = Modifier.testTag("source-type"),
            ) {
                Text(types.getOrElse(options.type) { types.first() })
            }
            DropdownMenu(typeOpen, { typeOpen = false }) {
                types.forEachIndexed { index, type ->
                    DropdownMenuItem(
                        modifier = Modifier.testTag("source-type-$index"),
                        text = { Text(type) },
                        onClick = {
                            typeOpen = false
                            actions.options(options.copy(type = index))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun fieldLabel(field: BookSourceEditField): String =
    field.label ?: stringResource(field.labelResource)

@Composable
private fun BookSourceField(
    field: BookSourceEditField,
    tab: Int,
    focused: Boolean,
    enabled: Boolean,
    maxLines: Int,
    actions: BookSourceScreenActions,
) {
    val unsafe =
        EditSafety.isTooLongForInline(field.value) || EditSafety.isCombiningHeavy(field.value)
    val label = fieldLabel(field)
    if (unsafe) {
        val preview =
            if (EditSafety.isTooLongForInline(field.value))
                stringResource(R.string.large_text_placeholder, field.value.length)
            else stringResource(R.string.combining_text_placeholder)
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp)
                .clickable(enabled = enabled) {
                    actions.focus(tab, field.key)
                    actions.native(BookSourceNativeAction.EDITOR, null)
                }
                .testTag("source-field-$tab-${field.key}")
        ) {
            Text(label)
            Text(preview, Modifier.padding(vertical = 12.dp))
        }
        return
    }
    val colors =
        CodeSyntaxColors(
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_blue_800),
            colorResource(R.color.md_blue_grey_500),
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_light_blue_600),
        )
    val syntax by
        produceState(AnnotatedString(field.value), field.value, colors) {
            value = withContext(Dispatchers.Default) { projectCodeSyntax(field.value, colors) }
        }
    val transformation =
        remember(syntax) {
            VisualTransformation { text ->
                TransformedText(
                    if (text.text == syntax.text) syntax else text,
                    OffsetMapping.Identity,
                )
            }
        }
    var composing by remember { mutableStateOf(TextFieldValue()) }
    val selection = TextRange(field.selectionStart, field.selectionEnd)
    val value =
        if (composing.text == field.value && composing.selection == selection) composing
        else TextFieldValue(field.value, selection)
    val requester = remember { FocusRequester() }
    LaunchedEffect(focused) { if (focused && enabled) requester.requestFocus() }
    OutlinedTextField(
        value,
        { updated ->
            composing = updated
            actions.field(
                tab,
                field.key,
                updated.text,
                updated.selection.start,
                updated.selection.end,
            )
        },
        label = { Text(label) },
        enabled = enabled,
        maxLines = maxLines.coerceAtLeast(1),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = transformation,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) actions.focus(tab, field.key) }
                .testTag("source-field-$tab-${field.key}"),
    )
}
