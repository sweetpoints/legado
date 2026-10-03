package io.legado.app.ui.rss.source.edit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.dialog.CodeSyntaxColors
import io.legado.app.ui.widget.dialog.projectCodeSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun RssSourceEditorField.labelResource(): Int? =
    when (this) {
        RssSourceEditorField.SourceName -> R.string.source_name
        RssSourceEditorField.SourceUrl -> R.string.source_url
        RssSourceEditorField.SourceIcon -> R.string.source_icon
        RssSourceEditorField.SourceGroup -> R.string.source_group
        RssSourceEditorField.SourceComment -> R.string.comment
        RssSourceEditorField.SearchUrl -> R.string.r_search_url
        RssSourceEditorField.SortUrl -> R.string.sort_url
        RssSourceEditorField.LoginUrl -> R.string.login_url
        RssSourceEditorField.LoginUi -> R.string.login_ui
        RssSourceEditorField.LoginCheckJs -> R.string.login_check_js
        RssSourceEditorField.CoverDecodeJs -> R.string.cover_decode_js
        RssSourceEditorField.Header -> R.string.source_http_header
        RssSourceEditorField.VariableComment -> R.string.variable_comment
        RssSourceEditorField.ConcurrentRate -> R.string.concurrent_rate
        RssSourceEditorField.StartHtml -> R.string.r_startHtml
        RssSourceEditorField.StartStyle -> R.string.r_startStyle
        RssSourceEditorField.StartJs -> R.string.r_startJs
        RssSourceEditorField.PreloadJs -> R.string.r_preloadJs
        RssSourceEditorField.RuleArticles -> R.string.r_articles
        RssSourceEditorField.RuleNextPage -> R.string.r_next
        RssSourceEditorField.RuleTitle -> R.string.r_title
        RssSourceEditorField.RulePubDate -> R.string.r_date
        RssSourceEditorField.RuleDescription -> R.string.r_description
        RssSourceEditorField.RuleImage -> R.string.r_image
        RssSourceEditorField.RuleLink -> R.string.r_link
        RssSourceEditorField.RuleContent -> R.string.r_content
        RssSourceEditorField.NextContentUrl -> R.string.rule_next_content
        RssSourceEditorField.Style -> R.string.r_style
        RssSourceEditorField.InjectJs -> R.string.r_inject_js
        RssSourceEditorField.ContentWhitelist -> R.string.c_whitelist
        RssSourceEditorField.ContentBlacklist -> R.string.c_blacklist
        RssSourceEditorField.JsLib,
        RssSourceEditorField.ShouldOverrideUrlLoading -> null
    }

internal fun RssSourceEditorField.literalLabel(): String =
    when (this) {
        RssSourceEditorField.JsLib -> "jsLib"
        else -> "url跳转拦截(js, 返回true拦截,js变量url,可以通过js打开url,比如调用阅读搜索,添加书架等,简化规则写法,不用webView js注入)"
    }

@Composable
internal fun RssSourceEditorField.label(): String =
    labelResource()?.let { stringResource(it) } ?: literalLabel()

internal fun RssSourceEditorIssue.label() =
    when (this) {
        RssSourceEditorIssue.Required -> R.string.non_null_name_url
        RssSourceEditorIssue.Format -> R.string.wrong_format
        RssSourceEditorIssue.Focus -> R.string.please_focus_cursor_on_textbox
        RssSourceEditorIssue.NoLogin -> R.string.source_no_login
    }

class RssSourceEditorActions(
    val field: (RssSourceEditorField, RssSourceEditorText) -> Unit = { _, _ -> },
    val focus: (RssSourceEditorField) -> Unit = {},
    val tab: (Int) -> Unit = {},
    val options: ((RssSourceEditorDraft) -> RssSourceEditorDraft) -> Unit = {},
    val expanded: (Boolean) -> Unit = {},
    val autoComplete: (Boolean) -> Unit = {},
    val save: (RssSourceEditorSaveAction) -> Unit = {},
    val exit: () -> Unit = {},
    val keep: () -> Unit = {},
    val discard: () -> Unit = {},
    val editor: () -> Unit = {},
    val action: (RssSourceEditorEffectKind) -> Unit = {},
    val cookie: () -> Unit = {},
    val paste: () -> Unit = {},
    val retry: () -> Unit = {},
    val retryEditor: () -> Unit = {},
    val discardEditor: () -> Unit = {},
    val insert: (String) -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
    val keyboardConfig: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RssSourceEditorScreen(
    state: RssSourceEditorState,
    actions: RssSourceEditorActions,
    assists: List<RssSourceEditorAssist> = emptyList(),
    rows: Int = 1,
    maxLines: Int = 6,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var navigation by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val fields = RssSourceEditorField.entries.filter { it.tab == state.tab }
    val holder = rememberSaveableStateHolder()
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        actions.exit,
                        Modifier.testTag("rss-editor-back"),
                        enabled = !state.busy && !state.editorPending,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    Text(
                        stringResource(R.string.rss_source_edit),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    IconButton(
                        actions.editor,
                        Modifier.testTag("rss-editor-fullscreen"),
                        enabled = state.canEdit,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_code),
                            stringResource(R.string.edit_content),
                        )
                    }
                    IconButton(
                        { actions.save(RssSourceEditorSaveAction.Close) },
                        Modifier.testTag("rss-editor-save"),
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
                            Modifier.testTag("rss-editor-menu"),
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
                                { Text(stringResource(R.string.debug_source)) },
                                { close { actions.save(RssSourceEditorSaveAction.Debug) } },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.login)) },
                                { close { actions.save(RssSourceEditorSaveAction.Login) } },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.set_source_variable)) },
                                { close { actions.save(RssSourceEditorSaveAction.Variable) } },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.cookie)) },
                                { close(actions.cookie) },
                                enabled = state.canEdit,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.auto_complete)) },
                                { close { actions.autoComplete(!state.autoComplete) } },
                                leadingIcon = { Checkbox(state.autoComplete, null) },
                                enabled = state.canEdit,
                            )
                            listOf(
                                    R.string.copy_source to RssSourceEditorEffectKind.Clipboard,
                                    R.string.import_by_qr_code to RssSourceEditorEffectKind.ScanQr,
                                    R.string.str_share to RssSourceEditorEffectKind.ShareText,
                                    R.string.qr_share to RssSourceEditorEffectKind.ShareQr,
                                    R.string.log to RssSourceEditorEffectKind.Log,
                                    R.string.help to RssSourceEditorEffectKind.Help,
                                )
                                .forEach { (label, kind) ->
                                    DropdownMenuItem(
                                        { Text(stringResource(label)) },
                                        { close { actions.action(kind) } },
                                        enabled = state.canEdit,
                                    )
                                }
                            DropdownMenuItem(
                                { Text(stringResource(R.string.paste_source)) },
                                { close(actions.paste) },
                                enabled = state.canEdit,
                            )
                        }
                    }
                }
            }
            if (!ime) {
                val expansion =
                    stringResource(
                        if (state.expanded) R.string.book_intro_collapse
                        else R.string.book_intro_expand
                    )
                TextButton(
                    { actions.expanded(!state.expanded) },
                    Modifier.fillMaxWidth().testTag("rss-editor-options").semantics {
                        stateDescription = expansion
                    },
                    enabled = state.canEdit,
                ) {
                    val yes = stringResource(R.string.yes)
                    val no = stringResource(R.string.no)
                    val options =
                        listOf(
                            stringResource(R.string.is_enable) to state.draft.enabled,
                            stringResource(R.string.single_url) to state.draft.singleUrl,
                            stringResource(R.string.auto_save_cookie) to state.draft.cookieJar,
                            stringResource(R.string.enable_preload) to state.draft.preload,
                        )
                    Text(
                        stringResource(R.string.setting) +
                            ": " +
                            options.joinToString(" | ") {
                                "${it.first}: ${if (it.second) yes else no}"
                            },
                        maxLines = 2,
                    )
                }
                if (state.expanded) {
                    Row(Modifier.fillMaxWidth()) {
                        RssEditorFlag(
                            state.draft.enabled,
                            R.string.is_enable,
                            state.canEdit,
                            { actions.options { draft -> draft.copy(enabled = it) } },
                            Modifier.weight(1f),
                        )
                        RssEditorFlag(
                            state.draft.singleUrl,
                            R.string.single_url,
                            state.canEdit,
                            { actions.options { draft -> draft.copy(singleUrl = it) } },
                            Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        RssEditorFlag(
                            state.draft.cookieJar,
                            R.string.auto_save_cookie,
                            state.canEdit,
                            { actions.options { draft -> draft.copy(cookieJar = it) } },
                            Modifier.weight(1f),
                        )
                        RssEditorFlag(
                            state.draft.preload,
                            R.string.enable_preload,
                            state.canEdit,
                            { actions.options { draft -> draft.copy(preload = it) } },
                            Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    RssEditorChoice(
                        stringResource(R.string.book_type),
                        stringArrayResource(R.array.rss_type),
                        state.draft.type,
                        state.canEdit,
                        { actions.options { draft -> draft.copy(type = it) } },
                        Modifier.weight(1f),
                    )
                    RssEditorChoice(
                        stringResource(R.string.layout_type),
                        stringArrayResource(R.array.layout_type),
                        state.draft.articleStyle,
                        state.canEdit,
                        { actions.options { draft -> draft.copy(articleStyle = it) } },
                        Modifier.weight(1f),
                    )
                }
                ScrollableTabRow(state.tab, edgePadding = 0.dp) {
                    listOf(
                            stringResource(R.string.source_tab_base),
                            stringResource(R.string.source_tab_start),
                            stringResource(R.string.source_tab_list),
                            "WEB_VIEW",
                        )
                        .forEachIndexed { index, title ->
                            Tab(
                                state.tab == index,
                                {
                                    focus.clearFocus()
                                    actions.tab(index)
                                },
                                text = { Text(title) },
                                enabled = state.canEdit,
                                modifier = Modifier.testTag("rss-editor-tab-$index"),
                            )
                        }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.issue != null || state.error != null)
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.issue?.let { stringResource(it.label()) } ?: state.error.orEmpty(),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.error != null) {
                        TextButton(
                            if (state.editorPending) actions.retryEditor else actions.retry,
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                        if (state.editorPending)
                            TextButton(actions.discardEditor, enabled = !state.busy) {
                                Text(stringResource(R.string.cancel))
                            }
                    }
                }
            holder.SaveableStateProvider(state.tab) {
                val list = rememberLazyListState()
                Box(Modifier.fillMaxWidth()) {
                    TextButton(
                        { navigation = true },
                        Modifier.fillMaxWidth().testTag("rss-editor-navigation"),
                        enabled = state.canEdit,
                    ) {
                        Text((state.focus ?: fields.first()).label(), maxLines = 1)
                    }
                    DropdownMenu(navigation, { navigation = false }) {
                        fields.forEachIndexed { index, field ->
                            DropdownMenuItem(
                                { Text(field.label()) },
                                {
                                    navigation = false
                                    scope.launch {
                                        list.animateScrollToItem(
                                            index + if (state.tab == 3) 1 else 0
                                        )
                                        actions.focus(field)
                                    }
                                },
                                modifier = Modifier.testTag("rss-editor-navigate-" + field.name),
                            )
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("rss-editor-fields"),
                    state = list,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.tab == 3)
                        item("web-flags") {
                            Column {
                                RssEditorFlag(
                                    state.draft.enableJs,
                                    R.string.enable_js,
                                    state.canEdit,
                                    { actions.options { draft -> draft.copy(enableJs = it) } },
                                )
                                RssEditorFlag(
                                    state.draft.loadWithBaseUrl,
                                    R.string.load_with_base_url,
                                    state.canEdit,
                                    {
                                        actions.options { draft ->
                                            draft.copy(loadWithBaseUrl = it)
                                        }
                                    },
                                )
                                RssEditorFlag(
                                    state.draft.showWebLog,
                                    R.string.load_with_web_log,
                                    state.canEdit,
                                    { actions.options { draft -> draft.copy(showWebLog = it) } },
                                )
                                RssEditorFlag(
                                    state.draft.cacheFirst,
                                    R.string.cache_first,
                                    state.canEdit,
                                    { actions.options { draft -> draft.copy(cacheFirst = it) } },
                                )
                            }
                        }
                    items(fields, key = { it.name }) { field ->
                        RssEditorInput(
                            field,
                            state.draft[field],
                            state.canEdit,
                            state.focus == field,
                            maxLines,
                            actions,
                        )
                    }
                }
            }
            if (ime) {
                LazyHorizontalGrid(
                    GridCells.Fixed(rows.coerceIn(1, 5)),
                    Modifier.fillMaxWidth().height((rows.coerceIn(1, 5) * 48).dp),
                ) {
                    item("help") { TextButton({ help = true }) { Text("❓") } }
                    item("undo") {
                        TextButton(actions.undo, enabled = state.canEdit) { Text("↩️") }
                    }
                    item("redo") {
                        TextButton(actions.redo, enabled = state.canEdit) { Text("↪️") }
                    }
                    items(assists, key = { it.key }) { assist ->
                        TextButton({ actions.insert(assist.value) }, enabled = state.canEdit) {
                            Text(assist.key)
                        }
                    }
                }
            }
        }
    }
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column {
                    TextButton({
                        help = false
                        actions.keyboardConfig()
                    }) {
                        Text(stringResource(R.string.assists_key_config))
                    }
                    listOf(
                            "插入URL参数" to RssSourceEditorEffectKind.UrlOptions,
                            "订阅源教程" to RssSourceEditorEffectKind.Help,
                            "js教程" to RssSourceEditorEffectKind.JsHelp,
                            "正则教程" to RssSourceEditorEffectKind.RegexHelp,
                            "选择文件" to RssSourceEditorEffectKind.File,
                        )
                        .forEach { (title, kind) ->
                            TextButton({
                                help = false
                                actions.action(kind)
                            }) {
                                Text(title)
                            }
                        }
                }
            },
            confirmButton = {},
        )
    if (state.exit)
        AlertDialog(
            onDismissRequest = actions.keep,
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton(actions.keep, Modifier.testTag("rss-editor-keep")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(actions.discard, Modifier.testTag("rss-editor-discard")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
}

@Composable
private fun RssEditorFlag(
    value: Boolean,
    label: Int,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange, enabled = enabled)
        Text(stringResource(label))
    }
}

@Composable
private fun RssEditorChoice(
    label: String,
    values: Array<String>,
    selected: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text("$label: ${values[selected]}")
        }
        DropdownMenu(open, { open = false }) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    { Text(value) },
                    {
                        open = false
                        onChange(index)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssEditorInput(
    field: RssSourceEditorField,
    value: RssSourceEditorText,
    enabled: Boolean,
    focused: Boolean,
    maxLines: Int,
    actions: RssSourceEditorActions,
) {
    val editable =
        remember(value.text) {
            !EditSafety.isTooLongForInline(value.text) && !EditSafety.isCombiningHeavy(value.text)
        }
    if (!editable) {
        val placeholder =
            if (value.text.length > EditSafety.MAX_INLINE_TEXT_LENGTH)
                stringResource(R.string.large_text_placeholder, value.text.length)
            else stringResource(R.string.combining_text_placeholder)
        OutlinedCard(
            Modifier.fillMaxWidth()
                .testTag("rss-editor-" + field.name)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        actions.focus(field)
                        actions.editor()
                    },
                    onLongClick = {
                        actions.focus(field)
                        actions.editor()
                    },
                )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(field.label())
                Text(placeholder, fontFamily = FontFamily.Monospace)
            }
        }
        return
    }
    var local by
        remember(field) {
            mutableStateOf(TextFieldValue(value.text, TextRange(value.start, value.end)))
        }
    SideEffect {
        if (local.text != value.text || local.selection != TextRange(value.start, value.end))
            local = TextFieldValue(value.text, TextRange(value.start, value.end))
    }
    val requester = remember(field) { FocusRequester() }
    LaunchedEffect(focused, enabled) { if (focused && enabled) requester.requestFocus() }
    val colors =
        CodeSyntaxColors(
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_blue_800),
            colorResource(R.color.md_blue_grey_500),
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_light_blue_600),
        )
    val syntax by
        produceState(AnnotatedString(value.text), value.text, colors) {
            this.value = withContext(Dispatchers.Default) { projectCodeSyntax(value.text, colors) }
        }
    val transformation =
        remember(syntax) {
            VisualTransformation { text ->
                TransformedText(
                    if (syntax.text == text.text) syntax else text,
                    OffsetMapping.Identity,
                )
            }
        }
    OutlinedTextField(
        local,
        { next ->
            local = next
            actions.field(
                field,
                RssSourceEditorText(next.text, next.selection.start, next.selection.end),
            )
        },
        Modifier.fillMaxWidth()
            .testTag("rss-editor-" + field.name)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) actions.focus(field) },
        label = { Text(field.label()) },
        enabled = enabled,
        minLines = 1,
        maxLines = maxLines.coerceAtLeast(1),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = transformation,
    )
}
