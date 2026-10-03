package io.legado.app.ui.rss.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.RssArticleRow
import kotlinx.coroutines.flow.distinctUntilChanged

private data class RssPageViewport(val first: Int, val offset: Int, val last: Int, val canForward: Boolean)

/** Pure page presentation. Pagination, read records and image IO belong to their owners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssArticlesPageScreen(state: RssArticlesPageState, style: Int, landscape: Boolean,
    active: Boolean, preload: Boolean, onRefresh: () -> Unit, onMore: () -> Unit,
    onRetry: () -> Unit, onOpen: (String) -> Unit, onPosition: (Int, Int) -> Unit,
    onScrolled: (Long) -> Unit, modifier: Modifier = Modifier,
    image: @Composable (RssArticleRow, Modifier, Boolean, Boolean) -> Unit = { _, _, _, _ -> }) {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val waterfall = rememberLazyStaggeredGridState()
    val pull = rememberPullToRefreshState()
    var restored by remember(style) { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(state)
    val position by rememberUpdatedState(onPosition)
    val more by rememberUpdatedState(onMore)
    val acknowledge by rememberUpdatedState(onScrolled)
    // Empty Room emissions must not consume the restoration ticket before cached rows arrive.
    LaunchedEffect(active, style, state.scrollRequest, state.loaded, state.rows.size) {
        if (!active || !state.loaded || state.rows.isEmpty()) return@LaunchedEffect
        if (!restored || state.scrollRequest != 0L) {
            val index = state.firstVisible.coerceIn(0, state.rows.lastIndex)
            when (style) {
                2, 4 -> grid.scrollToItem(index, state.firstOffset)
                3 -> waterfall.scrollToItem(index, state.firstOffset)
                else -> list.scrollToItem(index, state.firstOffset)
            }
            restored = true
            if (state.scrollRequest != 0L) acknowledge(state.scrollRequest)
        }
    }
    LaunchedEffect(active, restored, style, preload) {
        if (!active || !restored) return@LaunchedEffect
        snapshotFlow {
            when (style) {
                2, 4 -> RssPageViewport(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset,
                    grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1, grid.canScrollForward)
                3 -> RssPageViewport(waterfall.firstVisibleItemIndex, waterfall.firstVisibleItemScrollOffset,
                    waterfall.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1, waterfall.canScrollForward)
                else -> RssPageViewport(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                    list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, list.canScrollForward)
            }
        }.distinctUntilChanged().collect { (first, offset, last, canForward) ->
            val value = current
            if (value.scrollRequest == 0L && value.rows.isNotEmpty()) position(first, offset)
            val threshold = if (style == 3 && preload) 5 else 0
            if (value.loaded && value.rows.isNotEmpty() && value.hasMore && value.retry == null &&
                !value.refreshing && !value.loadingMore && (!canForward || threshold > 0 && last >= value.rows.size - threshold)) more()
        }
    }
    val px = LocalDensity.current
    val horizontal = with(px) { (when (style) { 2 -> 8; 4 -> 4; 3 -> 40; else -> 0 }).toDp() }
    val waterHorizontal = with(px) { 40.toDp() }
    val waterVertical = with(px) { 60.toDp() }
    val waterOuterVertical = with(px) { 30.toDp() }
    @Composable fun Card(row: RssArticleRow) {
        RssArticleCard(row, style, landscape, { onOpen(row.key) }, image = { mod, natural, keep -> image(row, mod, natural, keep) })
    }
    @Composable fun Footer() {
        RssArticlesFooter(state, onClick = {
            if (state.error != null) details = true else if (!state.refreshing && !state.loadingMore) onMore()
        })
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().testTag("rss-articles-page")
            .pullToRefresh(isRefreshing = state.refreshing, state = pull,
                enabled = active && state.loaded && !state.loadingMore, onRefresh = onRefresh)) {
            when (style) {
                2, 4 -> LazyVerticalGrid(GridCells.Fixed(if (style == 2) 2 else 3), state = grid,
                    contentPadding = PaddingValues(horizontal = horizontal),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("rss-articles-grid")) {
                    items(state.rows, key = { it.key }) { Card(it) }
                    item(key = "rss-footer", span = { GridItemSpan(maxLineSpan) }) { Footer() }
                }
                3 -> LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(if (landscape) 3 else 2), state = waterfall,
                    contentPadding = PaddingValues(horizontal = horizontal, vertical = waterOuterVertical),
                    horizontalArrangement = Arrangement.spacedBy(waterHorizontal), verticalItemSpacing = waterVertical,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("rss-articles-waterfall")) {
                    items(state.rows, key = { it.key }) { Card(it) }
                    item(key = "rss-footer", span = StaggeredGridItemSpan.FullLine) { Footer() }
                }
                else -> LazyColumn(state = list,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("rss-articles-list")) {
                    items(state.rows, key = { it.key }) {
                        Card(it)
                        if (style != 1) HorizontalDivider()
                    }
                    item(key = "rss-footer") { Footer() }
                }
            }
            if (!state.loaded && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("rss-articles-initial-loading"))
            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter))
        }
    }
    if (details && state.error != null) AlertDialog(onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.error)) }, text = { Text(state.error) },
        confirmButton = { TextButton(onClick = { details = false; onRetry() }) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RssArticlesFooter(state: RssArticlesPageState, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rss-articles-footer")
        .clickable(enabled = !state.refreshing && !state.loadingMore, onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.Center) {
        when {
            state.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp))
            state.error != null -> Text(stringResource(R.string.error_load_msg, stringResource(R.string.error)))
            state.hasMore -> Text(stringResource(R.string.loading))
            state.loaded && !state.refreshing -> Text(stringResource(R.string.bottom_line))
        }
    }
}
