package io.legado.app.ui.main.bookshelf.style1.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.ui.main.bookshelf.components.*
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfPageScreen(
    state: BookshelfPageUiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onInfo: (String) -> Unit,
    onScrolled: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable (BookshelfPageEntry, Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var fastScrollJob by remember { mutableStateOf<Job?>(null) }
    val layout = state.settings.layout
    val gridLayout = layout >= 2
    val pull = rememberPullToRefreshState()
    val margin = with(LocalDensity.current) { state.settings.marginPx.toDp() }
    val extra = with(LocalDensity.current) { 24.toDp() }
    LaunchedEffect(state.scrollRequest, state.loading, gridLayout) {
        val request = state.scrollRequest
        if (request > 0 && !state.loading) {
            if (gridLayout) {
                if (state.settings.eInk) grid.scrollToItem(0) else grid.animateScrollToItem(0)
            } else {
                if (state.settings.eInk) list.scrollToItem(0) else list.animateScrollToItem(0)
            }
            onScrolled(request)
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.fillMaxSize()
                .testTag("bookshelf-page-${state.parameters.groupId}")
                .pullToRefresh(
                    isRefreshing = false,
                    state = pull,
                    enabled = state.canRefresh,
                    onRefresh = onRefresh,
                )
        ) {
            if (state.loading && state.entries.isEmpty()) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).testTag("bookshelf-page-loading")
                )
            } else if (state.entries.isEmpty()) {
                Text(
                    stringResource(R.string.bookshelf_empty),
                    Modifier.align(Alignment.Center).testTag("bookshelf-page-empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val title = BookshelfGridTitle.entries[state.settings.gridTitle.coerceIn(0, 2)]
                if (gridLayout) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layout),
                        state = grid,
                        contentPadding =
                            PaddingValues(horizontal = margin, vertical = margin + extra),
                        horizontalArrangement = Arrangement.spacedBy(margin * 2),
                        verticalArrangement = Arrangement.spacedBy(margin * 2),
                        modifier = Modifier.fillMaxSize().testTag("bookshelf-page-grid"),
                    ) {
                        items(state.entries, key = { it.key }) { entry ->
                            BookshelfBookCard(
                                entry.card(state),
                                BookshelfCardLayout.Grid,
                                { onOpen(entry.key) },
                                { onInfo(entry.key) },
                                gridTitle = title,
                                cover = { cover(entry, it) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(vertical = margin + extra),
                        verticalArrangement = Arrangement.spacedBy(margin * 2),
                        modifier = Modifier.fillMaxSize().testTag("bookshelf-page-list"),
                    ) {
                        items(state.entries, key = { it.key }) { entry ->
                            BookshelfBookCard(
                                entry.card(state),
                                if (layout == 1) BookshelfCardLayout.Compact
                                else BookshelfCardLayout.List,
                                { onOpen(entry.key) },
                                { onInfo(entry.key) },
                                cover = { cover(entry, it) },
                            )
                        }
                    }
                }
                val visible =
                    if (gridLayout) grid.layoutInfo.visibleItemsInfo.size
                    else list.layoutInfo.visibleItemsInfo.size
                val first =
                    if (gridLayout) grid.firstVisibleItemIndex else list.firstVisibleItemIndex
                val forward = if (gridLayout) grid.canScrollForward else list.canScrollForward
                val backward = if (gridLayout) grid.canScrollBackward else list.canScrollBackward
                if (forward || backward) {
                    val fraction =
                        if (!forward) 1f
                        else first.toFloat() / (state.entries.size - 1).coerceAtLeast(1)
                    BookshelfScrollRail(
                        fraction,
                        visible.toFloat() / state.entries.size,
                        state.settings.fastScroller,
                        { progress ->
                            fastScrollJob?.cancel()
                            fastScrollJob = scope.launch {
                                val index = ((state.entries.size - 1) * progress).roundToInt()
                                if (gridLayout) grid.scrollToItem(index)
                                else list.scrollToItem(index)
                            }
                        },
                        Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            state.error?.let { error ->
                Surface(
                    Modifier.align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onRetry, Modifier.testTag("bookshelf-page-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            if (state.canRefresh)
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = false,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
        }
    }
}

private fun BookshelfPageEntry.card(state: BookshelfPageUiState) =
    BookshelfBookCardModel(
        key,
        name,
        author,
        currentChapter,
        latestChapter,
        unread,
        highlightUnread,
        updating,
        progress,
        BookshelfReadProgressMode.thicknessDp(state.settings.readProgressMode),
        latestUpdate,
    )
