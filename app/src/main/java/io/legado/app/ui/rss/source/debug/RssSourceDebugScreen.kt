package io.legado.app.ui.rss.source.debug

import android.util.Patterns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

class RssSourceDebugActions(
    val query: (String, Int, Int) -> Unit = { _, _, _ -> },
    val help: (Boolean) -> Unit = {},
    val run: (String?) -> Unit = {},
    val sort: (Int) -> Unit = {},
    val html: (Boolean) -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RssSourceDebugScreen(state: RssSourceDebugState, actions: RssSourceDebugActions) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var categories by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.loaded) { if (state.loaded && state.help) requester.requestFocus() }
    var local by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd)))
    }
    SideEffect {
        if (
            local.text != state.query ||
                local.selection != TextRange(state.queryStart, state.queryEnd)
        )
            local = TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))
    }
    fun search(query: String?) {
        keyboard?.hide()
        focus.clearFocus()
        actions.run(query)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val output =
        remember(state.output, linkColor) {
            buildAnnotatedString {
                append(state.output)
                val matcher = Patterns.WEB_URL.matcher(state.output)
                while (matcher.find()) {
                    if (matcher.start() > 0 && state.output[matcher.start() - 1] == '@') continue
                    val url = matcher.group().orEmpty()
                    addLink(
                        LinkAnnotation.Url(
                            if (url.contains("://")) url else "http://$url",
                            TextLinkStyles(SpanStyle(color = linkColor)),
                        ),
                        matcher.start(),
                        matcher.end(),
                    )
                }
            }
        }
    val enabled = state.loaded && !state.closed
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.debug_source),
                        Modifier,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(actions.close, Modifier.testTag("rss-debug-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            { menu = true },
                            Modifier.testTag("rss-debug-menu"),
                            enabled = enabled,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.list_src)) },
                                {
                                    menu = false
                                    actions.html(false)
                                },
                                modifier = Modifier.testTag("rss-debug-list-html"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.content_src)) },
                                {
                                    menu = false
                                    actions.html(true)
                                },
                                modifier = Modifier.testTag("rss-debug-content-html"),
                            )
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    local,
                    { next ->
                        local = next
                        actions.query(next.text, next.selection.start, next.selection.end)
                    },
                    Modifier.weight(1f)
                        .testTag("rss-debug-query")
                        .focusRequester(requester)
                        .onFocusChanged { actions.help(it.isFocused) },
                    label = { Text(stringResource(R.string.search)) },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(state.query) }),
                )
                IconButton(
                    { search(state.query) },
                    Modifier.testTag("rss-debug-search"),
                    enabled = enabled,
                ) {
                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                }
            }
            if (state.loading || state.running && !state.help)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("rss-debug-loading"))
            if (state.issue != null || state.error != null)
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.error
                            ?: stringResource(
                                if (state.issue == RssSourceDebugIssue.Busy) R.string.rss_debug_busy
                                else R.string.rss_debug_interrupted
                            ),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.loaded)
                        TextButton(
                            actions.retry,
                            Modifier.testTag("rss-debug-retry"),
                            enabled = !state.loading,
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
            if (state.help)
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag("rss-debug-help")
                ) {
                    Text(stringResource(R.string.rss_debug_search_help))
                    Row {
                        TextButton(
                            { search("我的") },
                            Modifier.testTag("rss-debug-example-my"),
                            enabled = enabled,
                        ) {
                            Text("我的")
                        }
                        TextButton(
                            { search("系统") },
                            Modifier.testTag("rss-debug-example-system"),
                            enabled = enabled,
                        ) {
                            Text("系统")
                        }
                    }
                    Text(stringResource(R.string.rss_debug_category_help))
                    val sort = state.sorts.getOrNull(state.selectedSort)
                    Text(
                        sort?.query ?: "系统::http://xxx",
                        Modifier.fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .testTag("rss-debug-category")
                            .combinedClickable(
                                enabled = enabled && sort?.name?.startsWith("ERROR:") != true,
                                onClick = {
                                    if (sort == null) search("系统::http://xxx")
                                    else actions.sort(state.selectedSort)
                                },
                                onLongClick = { if (state.sorts.isNotEmpty()) categories = true },
                            ),
                    )
                    Text(stringResource(R.string.rss_debug_content_help))
                    TextButton(
                        { if (state.query.isNotBlank()) search(state.query) },
                        Modifier.testTag("rss-debug-example-content"),
                        enabled = enabled,
                    ) {
                        Text("https://m.qidian.com/book/1015609210")
                    }
                }
            else
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                    ) {
                        Text(
                            output,
                            Modifier.fillMaxWidth().testTag("rss-debug-log"),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
        }
    }
    if (categories)
        AlertDialog(
            onDismissRequest = { categories = false },
            title = { Text(stringResource(R.string.rss_debug_choose_category)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.sorts.forEachIndexed { index, sort ->
                        TextButton(
                            {
                                categories = false
                                focus.clearFocus()
                                keyboard?.hide()
                                actions.sort(index)
                            },
                            Modifier.fillMaxWidth().testTag("rss-debug-sort-$index"),
                        ) {
                            Text(sort.name)
                        }
                    }
                }
            },
            confirmButton = {},
        )
}
