package io.legado.app.ui.book.toc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.flow.collect

class TocHostActions(
    val back: () -> Unit = {},
    val tab: (Int) -> Unit = {},
    val search: (Boolean) -> Unit = {},
    val query: (String, Int, Int) -> Unit = { _, _, _ -> },
    val menu: (Boolean) -> Unit = {},
    val reverse: () -> Unit = {},
    val expanded: () -> Unit = {},
    val useReplace: () -> Unit = {},
    val countWords: () -> Unit = {},
    val split: () -> Unit = {},
    val effect: (TocHostEffectKind) -> Unit = {},
    val retry: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocHostScreen(
    session: TocHostSessionState,
    state: TocHostState,
    actions: TocHostActions,
    page: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = session.tab, pageCount = { 3 })
    LaunchedEffect(session.tab) {
        if (pager.currentPage != session.tab) pager.animateScrollToPage(session.tab)
    }
    val currentTab by rememberUpdatedState(session.tab)
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (it != currentTab) currentActions.tab(it) }
    }
    // Preserve IME composition locally; only text and the small selection are checkpointed.
    var editor by remember {
        mutableStateOf(
            TextFieldValue(session.query, TextRange(session.selectionStart, session.selectionEnd))
        )
    }
    LaunchedEffect(session.query, session.selectionStart, session.selectionEnd) {
        if (
            editor.text != session.query ||
                editor.selection.start != session.selectionStart ||
                editor.selection.end != session.selectionEnd
        )
            editor =
                TextFieldValue(
                    session.query,
                    TextRange(session.selectionStart, session.selectionEnd),
                )
    }
    val enabled = session.ready && state.loaded && !state.busy
    Surface(color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            if (session.searchOpen)
                                TextField(
                                    editor,
                                    { value ->
                                        editor = value
                                        actions.query(
                                            value.text,
                                            value.selection.start,
                                            value.selection.end,
                                        )
                                    },
                                    Modifier.fillMaxWidth().testTag("toc-host-query"),
                                    enabled = enabled,
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.search)) },
                                )
                            else
                                Text(
                                    stringResource(
                                        when (session.tab) {
                                            1 -> R.string.bookmark
                                            2 -> R.string.highlight_tab
                                            else -> R.string.chapter_list
                                        }
                                    )
                                )
                        },
                        navigationIcon = {
                            TextButton(
                                actions.back,
                                enabled = !state.busy,
                                modifier = Modifier.testTag("toc-host-back"),
                            ) {
                                Text(stringResource(R.string.back))
                            }
                        },
                        actions = {
                            TextButton(
                                { actions.search(!session.searchOpen) },
                                enabled = enabled,
                                modifier =
                                    Modifier.testTag(
                                        if (session.searchOpen) "toc-host-close-search"
                                        else "toc-host-search"
                                    ),
                            ) {
                                Text(
                                    stringResource(
                                        if (session.searchOpen) R.string.close else R.string.search
                                    )
                                )
                            }
                            Box {
                                TextButton(
                                    { actions.menu(true) },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("toc-host-menu"),
                                ) {
                                    Text("⋮")
                                }
                                DropdownMenu(session.menuOpen, { actions.menu(false) }) {
                                    fun invoke(action: () -> Unit) {
                                        actions.menu(false)
                                        action()
                                    }
                                    if (session.tab == 0) {
                                        TocHostMenuItem(
                                            R.string.reverse_toc,
                                            "reverse",
                                            state.reverse,
                                        ) {
                                            invoke(actions.reverse)
                                        }
                                        TocHostMenuItem(
                                            R.string.expand_toc,
                                            "expanded",
                                            state.expanded,
                                        ) {
                                            invoke(actions.expanded)
                                        }
                                        TocHostMenuItem(
                                            R.string.use_replace,
                                            "replace",
                                            state.useReplace,
                                        ) {
                                            invoke(actions.useReplace)
                                        }
                                        TocHostMenuItem(
                                            R.string.load_word_count,
                                            "words",
                                            state.countWords,
                                        ) {
                                            invoke(actions.countWords)
                                        }
                                        if (state.localText) {
                                            TocHostMenuItem(R.string.txt_toc_rule, "regex") {
                                                invoke { actions.effect(TocHostEffectKind.Regex) }
                                            }
                                            TocHostMenuItem(
                                                R.string.split_long_chapter,
                                                "split",
                                                state.split,
                                            ) {
                                                invoke(actions.split)
                                            }
                                        }
                                    } else if (session.tab == 1) {
                                        TocHostMenuItem(R.string.export, "export-json") {
                                            invoke { actions.effect(TocHostEffectKind.PickJson) }
                                        }
                                        TocHostMenuItem(R.string.export_md, "export-markdown") {
                                            invoke {
                                                actions.effect(TocHostEffectKind.PickMarkdown)
                                            }
                                        }
                                    }
                                    TocHostMenuItem(R.string.log, "log") {
                                        invoke { actions.effect(TocHostEffectKind.Log) }
                                    }
                                }
                            }
                        },
                    )
                    if (!session.searchOpen)
                        TabRow(session.tab) {
                            listOf(R.string.chapter_list, R.string.bookmark, R.string.highlight_tab)
                                .forEachIndexed { index, title ->
                                    Tab(
                                        session.tab == index,
                                        { actions.tab(index) },
                                        enabled = enabled,
                                        modifier = Modifier.testTag("toc-host-tab-$index"),
                                        text = { Text(stringResource(title)) },
                                    )
                                }
                        }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!session.ready || !state.loaded && !state.noBook && state.error == null)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                val error =
                    if (state.noBook) stringResource(R.string.no_book)
                    else state.error ?: session.error
                error?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it,
                            Modifier.weight(1f).padding(12.dp).testTag("toc-host-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(actions.retry, modifier = Modifier.testTag("toc-host-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                if (session.ready && state.loaded)
                    HorizontalPager(
                        pager,
                        Modifier.weight(1f).fillMaxWidth().testTag("toc-host-pager"),
                        userScrollEnabled = !state.busy,
                        beyondViewportPageCount = 2,
                    ) {
                        page(it)
                    }
            }
        }
        if (state.busy)
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.padding(28.dp).testTag("toc-host-busy")) {
                        CircularProgressIndicator()
                    }
                }
            }
    }
}

@Composable
private fun TocHostMenuItem(title: Int, tag: String, checked: Boolean? = null, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(title)) },
        onClick = action,
        modifier = Modifier.testTag("toc-host-$tag"),
        leadingIcon =
            if (checked == null) null
            else {
                { Checkbox(checked, null) }
            },
    )
}
