package io.legado.app.ui.book.searchContent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.model.book.ContentSearchMatch
import io.legado.app.model.book.contentSearchHighlight
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ContentSearchActions(val close: () -> Unit, val query: (String) -> Unit, val submit: () -> Unit,
    val stop: () -> Unit, val choose: (String) -> Unit, val replace: (Boolean) -> Unit, val regex: (Boolean) -> Unit,
    val retry: () -> Unit, val position: (Int) -> Unit)

@Composable internal fun ContentSearchScreen(state: ContentSearchState, actions: ContentSearchActions,
    modifier: Modifier = Modifier, eInk: Boolean = false, bottomColor: Color = MaterialTheme.colorScheme.surface,
    bottomForeground: Color = MaterialTheme.colorScheme.onSurface) {
    val list = rememberLazyListState(state.position.coerceIn(0, (state.results.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
    var menu by rememberSaveable { mutableStateOf(false) }
    // Save only cursor indices. The possibly large query itself belongs in the private session file.
    var start by rememberSaveable { mutableIntStateOf(-1) }; var end by rememberSaveable { mutableIntStateOf(-1) }
    var input by remember { mutableStateOf(TextFieldValue(state.query,
        TextRange(if (start < 0) state.query.length else start.coerceIn(0, state.query.length),
            if (end < 0) state.query.length else end.coerceIn(0, state.query.length)))) }
    LaunchedEffect(state.loading, state.query) {
        if (!state.loading && input.text != state.query) input = TextFieldValue(state.query,
            TextRange(if (start < 0) state.query.length else start.coerceIn(0, state.query.length),
                if (end < 0) state.query.length else end.coerceIn(0, state.query.length)))
    }
    LaunchedEffect(state.loading, state.loadFailed, state.focusInput) {
        if (!state.loading && !state.loadFailed) { if (state.focusInput && !state.finished) focus.requestFocus() else { manager.clearFocus(); keyboard?.hide() } }
    }
    var listReady by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.loadFailed, state.position) {
        if (!state.loading && !state.loadFailed) { listReady = false
            val target = state.position.coerceIn(0, (state.results.size - 1).coerceAtLeast(0))
            if (list.firstVisibleItemIndex != target) list.scrollToItem(target)
            listReady = true }
    }
    val position by rememberUpdatedState(actions.position)
    LaunchedEffect(list, listReady) { if (listReady) snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { position(it) } }
    val submit = { manager.clearFocus(); keyboard?.hide(); actions.submit() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.close, modifier = Modifier.size(48.dp).testTag("content-search-back")) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
                }
                OutlinedTextField(input, onValueChange = {
                    input = it; start = it.selection.start; end = it.selection.end; actions.query(it.text)
                }, modifier = Modifier.weight(1f).focusRequester(focus).testTag("content-search-query"),
                    enabled = !state.loading && !state.loadFailed && !state.selecting && !state.finished, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                IconButton(onClick = { submit() }, enabled = !state.loading && !state.loadFailed && !state.selecting && !state.finished,
                    modifier = Modifier.size(48.dp).testTag("content-search-submit")) { Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search)) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("content-search-menu")) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.replace)) },
                            onClick = { actions.replace(!state.options.replace); menu = false }, enabled = !state.loading && !state.loadFailed && !state.selecting && !state.finished,
                            leadingIcon = { Checkbox(state.options.replace, null) }, modifier = Modifier.testTag("content-search-replace"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.regex)) },
                            onClick = { actions.regex(!state.options.regex); menu = false }, enabled = !state.loading && !state.loadFailed && !state.selecting && !state.finished,
                            leadingIcon = { Checkbox(state.options.regex, null) }, modifier = Modifier.testTag("content-search-regex"))
                    }
                }
            }
            if (state.loading || state.running || state.selecting) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("content-search-progress"))
            state.error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).testTag("content-search-error"), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.retry, modifier = Modifier.heightIn(min = 48.dp).testTag("content-search-retry")) { Text(stringResource(R.string.retry)) }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().testTag("content-search-results"), state = list, contentPadding = PaddingValues(vertical = 3.dp)) {
                    items(state.results, key = { it.id }) { match ->
                        ContentSearchResultCard(match, state.currentChapter, eInk, !state.finished && !state.selecting,
                            { actions.choose(match.id) })
                    }
                    if (!state.loading && state.completed && state.results.isEmpty()) item("empty") {
                        Text(stringResource(R.string.search_content_empty), Modifier.fillMaxWidth().padding(22.dp).testTag("content-search-empty"), fontSize = 14.sp)
                    }
                }
                if (state.running) FloatingActionButton(onClick = actions.stop,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("content-search-stop")) {
                    Icon(painterResource(R.drawable.ic_stop_black_24dp), stringResource(R.string.stop))
                }
            }
            Surface(color = bottomColor, contentColor = bottomForeground, shadowElevation = 5.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_content_size) + ": ${state.results.size}",
                        Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = !state.loading && !state.loadFailed && !state.finished) { focus.requestFocus(); keyboard?.show() }
                            .padding(horizontal = 20.dp, vertical = 14.dp).testTag("content-search-count"), fontSize = 12.sp)
                    IconButton(onClick = { scope.launch { list.scrollToItem(0) } }, modifier = Modifier.size(48.dp).testTag("content-search-top")) {
                        Icon(painterResource(R.drawable.ic_arrow_drop_up), stringResource(R.string.go_to_top))
                    }
                    IconButton(onClick = { if (state.results.isNotEmpty()) scope.launch { list.scrollToItem(state.results.lastIndex) } },
                        modifier = Modifier.size(48.dp).testTag("content-search-bottom")) {
                        Icon(painterResource(R.drawable.ic_arrow_drop_down), stringResource(R.string.go_to_bottom))
                    }
                }
            }
        }
    }
}

@Composable private fun ContentSearchResultCard(match: ContentSearchMatch, currentChapter: Int,
    eInk: Boolean, enabled: Boolean, choose: () -> Unit) {
    val highlight = remember(match) { contentSearchHighlight(match) }
    val primary = MaterialTheme.colorScheme.secondary
    val text = remember(match, highlight, eInk, primary) { buildAnnotatedString {
        if (highlight != null) {
            pushStyle(SpanStyle(color = if (eInk) Color.Unspecified else primary, textDecoration = if (eInk) TextDecoration.Underline else null))
            append(match.chapterTitle); pop(); append('\n')
            append(match.resultText.substring(0, highlight.first))
            pushStyle(SpanStyle(color = if (eInk) Color.Unspecified else primary, textDecoration = if (eInk) TextDecoration.Underline else null))
            append(match.resultText.substring(highlight.first, highlight.last + 1)); pop()
            append(match.resultText.substring(highlight.last + 1))
        } else append(match.resultText)
    } }
    Card(onClick = choose, enabled = enabled && match.query.isNotBlank(), shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.background_card)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).heightIn(min = 48.dp).testTag("content-search-result-${match.id}")) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp).testTag("content-search-text-${match.id}"), fontSize = 14.sp,
            fontWeight = if (match.chapterIndex == currentChapter) FontWeight.Bold else FontWeight.Normal)
    }
}
