package io.legado.app.ui.rss.article

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.flow.distinctUntilChanged

class RssCategoryActions(val back: () -> Unit, val select: (Int) -> Unit,
    val menu: (Boolean) -> Unit, val search: (Boolean) -> Unit,
    val draft: (String, Int, Int) -> Unit, val submit: () -> Unit,
    val effect: (RssCategoryEffectKind) -> Unit, val refresh: () -> Unit,
    val switchStyle: () -> Unit, val clear: () -> Unit, val retry: () -> Unit)

internal fun rssCategoryRowSize(count: Int, landscape: Boolean): Int {
    if (count == 0) return 1
    var rows = when { count <= 10 -> 1; count <= 20 -> 2; else -> 3 }
    if (landscape && rows > 1) rows--
    return (count + rows - 1) / rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssCategoryScreen(state: RssCategoryState, landscape: Boolean, actions: RssCategoryActions,
    page: @Composable (Int, Boolean) -> Unit) {
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searchOpen) { if (state.searchOpen) searchFocus.requestFocus() }
    val pager = rememberPagerState(initialPage = state.selected, pageCount = { state.tabs.size })
    var synchronizing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(state)
    val selected by rememberUpdatedState(actions.select)
    var editor by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.selectionStart, state.selectionEnd))) }
    LaunchedEffect(state.draft, state.selectionStart, state.selectionEnd) {
        if (editor.text != state.draft || editor.selection != TextRange(state.selectionStart, state.selectionEnd))
            editor = TextFieldValue(state.draft, TextRange(state.selectionStart, state.selectionEnd))
    }
    LaunchedEffect(state.selected, state.tabs) {
        synchronizing = true
        try { if (state.selected in state.tabs.indices && pager.currentPage != state.selected) pager.scrollToPage(state.selected) }
        finally { synchronizing = false }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, synchronizing) }.distinctUntilChanged().collect { (index, moving, syncing) ->
            if (!moving && !syncing && index in current.tabs.indices && current.selected != index) selected(index)
        }
    }
    fun submit() { focus.clearFocus(); actions.search(false); actions.submit() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().testTag("rss-category-screen")) {
            TopAppBar(title = {
                if (state.searchOpen) TextField(editor, onValueChange = {
                    editor = it; actions.draft(it.text, it.selection.start, it.selection.end)
                }, enabled = state.loaded && !state.busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).testTag("rss-category-query"),
                    placeholder = { Text(stringResource(R.string.search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                else Text(if (state.tabs.size == 1 && state.tabs.first().name.isNotEmpty())
                    state.request?.query ?: state.tabs.first().name else state.sourceName)
            }, navigationIcon = { IconButton(onClick = actions.back, modifier = Modifier.testTag("rss-category-back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            } }, actions = {
                if (state.canSearch) {
                    if (state.searchOpen) {
                        IconButton(onClick = { submit() }, enabled = state.loaded && !state.busy, modifier = Modifier.testTag("rss-category-submit")) { Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search)) }
                        IconButton(onClick = { focus.clearFocus(); actions.search(false) }) { Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.cancel)) }
                    } else IconButton(onClick = { actions.search(true) }, enabled = state.loaded && !state.busy,
                        modifier = Modifier.testTag("rss-category-search")) { Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search)) }
                }
                Box {
                    IconButton(onClick = { actions.menu(true) }, enabled = state.loaded && !state.busy,
                        modifier = Modifier.testTag("rss-category-menu")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu)) }
                    DropdownMenu(expanded = state.menuOpen, onDismissRequest = { actions.menu(false) }) {
                        fun finish(action: () -> Unit) { actions.menu(false); action() }
                        if (state.canLogin) DropdownMenuItem(text = { Text(stringResource(R.string.login)) }, onClick = { finish { actions.effect(RssCategoryEffectKind.Login) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.refresh_sort)) }, onClick = { finish(actions.refresh) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.set_source_variable)) }, onClick = { finish { actions.effect(RssCategoryEffectKind.Variable) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit_source)) }, onClick = { finish { actions.effect(RssCategoryEffectKind.EditSource) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.switchLayout)) }, onClick = { finish(actions.switchStyle) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.read_record)) }, onClick = { finish { actions.effect(RssCategoryEffectKind.ReadRecords) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.clear)) }, onClick = { finish(actions.clear) })
                    }
                }
            })
            if (state.busy || !state.loaded && !state.missingSource && state.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) {
                Text(state.error, Modifier.padding(16.dp)); TextButton(onClick = actions.retry) { Text(stringResource(R.string.retry)) }
            }
            if (state.missingSource) Text(stringResource(R.string.error_no_source), Modifier.padding(16.dp))
            if (state.tabs.size > 1) {
                val size = rssCategoryRowSize(state.tabs.size, landscape)
                state.tabs.chunked(size).forEachIndexed { row, tabs ->
                    val scroll = rememberLazyListState()
                    LaunchedEffect(state.selected, tabs) {
                        val within = state.selected - row * size
                        if (within in tabs.indices) scroll.animateScrollToItem(within)
                    }
                    LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("rss-category-tab-row-$row")) {
                        itemsIndexed(tabs, key = { _, tab -> tab.index }) { _, tab ->
                            FilterChip(selected = state.selected == tab.index, onClick = { focus.clearFocus(); actions.search(false); actions.select(tab.index) },
                                label = { Text(tab.name) }, modifier = Modifier.testTag("rss-category-tab-${tab.index}"))
                        }
                    }
                }
            }
            if (state.loaded && state.tabs.isNotEmpty()) HorizontalPager(state = pager,
                beyondViewportPageCount = 1, modifier = Modifier.weight(1f).fillMaxWidth().testTag("rss-category-pager")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) { focus.clearFocus(); actions.search(false) }
                            }
                        }
                    }) { index ->
                page(index, pager.settledPage == index && !pager.isScrollInProgress)
            }
        }
    }
}
