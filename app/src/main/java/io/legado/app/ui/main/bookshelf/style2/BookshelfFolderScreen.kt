package io.legado.app.ui.main.bookshelf.style2

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.main.bookshelf.components.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun BookshelfFolderScreen(
    state: BookshelfFolderState,
    onOpenGroup: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
    onOpenBook: (String) -> Unit,
    onBookInfo: (String) -> Unit,
    onMenu: (Int) -> Unit,
    onBack: () -> Unit,
    onSwipe: (Int) -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
    onRecentInfo: () -> Unit,
    onScrolled: (Long, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    bookCover: @Composable (BookshelfFolderBook, Modifier) -> Unit,
    groupCover: @Composable (BookshelfFolderGroup, Modifier) -> Unit,
) {
    val pages = rememberSaveableStateHolder()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            val title =
                stringResource(R.string.bookshelf) +
                    if (state.root) "" else "(${state.selectedGroup?.name.orEmpty()})"
            BookshelfToolbar(title, onMenu, onBack.takeUnless { state.root })
            BookshelfHeader(state.header, onContinue, onRecentInfo)
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider(state.groupId) {
                    BookshelfFolderContent(
                        state,
                        onOpenGroup,
                        onEditGroup,
                        onOpenBook,
                        onBookInfo,
                        onSwipe,
                        onRefresh,
                        onScrolled,
                        onRetry,
                        bookCover,
                        groupCover,
                    )
                }
            }
        }
    }
}

private data class FolderRow(
    val group: BookshelfFolderGroup? = null,
    val book: BookshelfFolderBook? = null,
) {
    val key: String
        get() = group?.let { "group:${it.id}" } ?: "book:${book!!.card.key}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfFolderContent(
    state: BookshelfFolderState,
    onOpenGroup: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
    onOpenBook: (String) -> Unit,
    onBookInfo: (String) -> Unit,
    onSwipe: (Int) -> Unit,
    onRefresh: () -> Unit,
    onScrolled: (Long, Int) -> Unit,
    onRetry: () -> Unit,
    bookCover: @Composable (BookshelfFolderBook, Modifier) -> Unit,
    groupCover: @Composable (BookshelfFolderGroup, Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val layout = state.settings.layout
    val isGrid = layout >= 2
    val pull = rememberPullToRefreshState()
    val margin = with(LocalDensity.current) { state.settings.marginPx.toDp() }
    val extra = with(LocalDensity.current) { 24.toDp() }
    val rows =
        remember(state.groups, state.books, state.root) {
            (if (state.root) state.groups.map { FolderRow(group = it) } else emptyList()) +
                state.books.map { FolderRow(book = it) }
        }
    LaunchedEffect(state.scrollRequest, state.loading, isGrid) {
        if (state.scrollRequest > 0 && !state.loading) {
            if (isGrid) {
                if (state.settings.eInk) grid.scrollToItem(0) else grid.animateScrollToItem(0)
            } else {
                if (state.settings.eInk) list.scrollToItem(0) else list.animateScrollToItem(0)
            }
            onScrolled(state.groupId, state.scrollRequest)
        }
    }
    val swipe by rememberUpdatedState(onSwipe)
    val previousLabel = stringResource(R.string.bookshelf_previous_group)
    val nextLabel = stringResource(R.string.bookshelf_next_group)
    val swipeModifier =
        Modifier.pointerInput(state.groupId, state.previous, state.next) {
                awaitEachGesture {
                    val down =
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val gesture =
                        BookshelfFolderGesture(
                            maxOf(50f, viewConfiguration.touchSlop),
                            state.previous,
                            state.next,
                        )
                    var captured = false
                    while (true) {
                        val change =
                            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                                it.id == down.id
                            } ?: break
                        val delta = change.position - down.position
                        if (!change.pressed) {
                            val committed = gesture.finish(delta.x, delta.y, change.isConsumed)
                            if (captured) change.consume()
                            committed?.let(swipe)
                            break
                        }
                        if (
                            !captured &&
                                abs(delta.y) > viewConfiguration.touchSlop &&
                                abs(delta.y) > abs(delta.x)
                        )
                            break
                        captured = gesture.move(delta.x, delta.y)
                        if (captured) change.consume()
                    }
                }
            }
            .semantics {
                customActions = buildList {
                    if (state.previous)
                        add(
                            CustomAccessibilityAction(previousLabel) {
                                swipe(-1)
                                true
                            }
                        )
                    if (state.next)
                        add(
                            CustomAccessibilityAction(nextLabel) {
                                swipe(1)
                                true
                            }
                        )
                }
            }
    Box(
        Modifier.fillMaxSize()
            .testTag("bookshelf-folder-${state.groupId}")
            .then(swipeModifier)
            .pullToRefresh(
                isRefreshing = false,
                enabled = state.canRefresh,
                state = pull,
                onRefresh = onRefresh,
            )
    ) {
        if (state.loading && state.books.isEmpty()) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag("bookshelf-folder-loading")
            )
        } else if (rows.isEmpty()) {
            Text(
                stringResource(R.string.bookshelf_empty),
                Modifier.align(Alignment.Center).testTag("bookshelf-folder-empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val title = BookshelfGridTitle.entries[state.settings.gridTitle.coerceIn(0, 2)]
            if (isGrid)
                LazyVerticalGrid(
                    GridCells.Fixed(layout),
                    state = grid,
                    contentPadding = PaddingValues(horizontal = margin, vertical = margin + extra),
                    horizontalArrangement = Arrangement.spacedBy(margin * 2),
                    verticalArrangement = Arrangement.spacedBy(margin * 2),
                    modifier = Modifier.fillMaxSize().testTag("bookshelf-folder-grid"),
                ) {
                    items(
                        rows,
                        key = { it.key },
                        contentType = { if (it.group == null) "book" else "group" },
                    ) { row ->
                        FolderCard(
                            row,
                            BookshelfCardLayout.Grid,
                            title,
                            onOpenGroup,
                            onEditGroup,
                            onOpenBook,
                            onBookInfo,
                            bookCover,
                            groupCover,
                        )
                    }
                }
            else
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(vertical = margin + extra),
                    verticalArrangement = Arrangement.spacedBy(margin * 2),
                    modifier = Modifier.fillMaxSize().testTag("bookshelf-folder-list"),
                ) {
                    items(
                        rows,
                        key = { it.key },
                        contentType = { if (it.group == null) "book" else "group" },
                    ) { row ->
                        FolderCard(
                            row,
                            if (layout == 1) BookshelfCardLayout.Compact
                            else BookshelfCardLayout.List,
                            title,
                            onOpenGroup,
                            onEditGroup,
                            onOpenBook,
                            onBookInfo,
                            bookCover,
                            groupCover,
                        )
                    }
                }
            val first = if (isGrid) grid.firstVisibleItemIndex else list.firstVisibleItemIndex
            val visible =
                if (isGrid) grid.layoutInfo.visibleItemsInfo.size
                else list.layoutInfo.visibleItemsInfo.size
            val forward = if (isGrid) grid.canScrollForward else list.canScrollForward
            val backward = if (isGrid) grid.canScrollBackward else list.canScrollBackward
            if (forward || backward)
                BookshelfScrollRail(
                    if (!forward) 1f else first.toFloat() / (rows.size - 1).coerceAtLeast(1),
                    visible.toFloat() / rows.size,
                    state.settings.fastScroller,
                    { progress ->
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            val index = ((rows.size - 1) * progress).roundToInt()
                            if (isGrid) grid.scrollToItem(index) else list.scrollToItem(index)
                        }
                    },
                    Modifier.align(Alignment.CenterEnd),
                )
        }
        if (state.canRefresh)
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = false,
                modifier = Modifier.align(Alignment.TopCenter),
            )
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
                    TextButton(onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(
    row: FolderRow,
    layout: BookshelfCardLayout,
    title: BookshelfGridTitle,
    onOpenGroup: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
    onOpenBook: (String) -> Unit,
    onBookInfo: (String) -> Unit,
    bookCover: @Composable (BookshelfFolderBook, Modifier) -> Unit,
    groupCover: @Composable (BookshelfFolderGroup, Modifier) -> Unit,
) {
    row.group?.let { group ->
        BookshelfGroupCard(
            BookshelfGroupCardModel(group.id, group.name),
            layout,
            { onOpenGroup(group.id) },
            { onEditGroup(group.id) },
            gridTitle = title,
            cover = { groupCover(group, it) },
        )
    }
    row.book?.let { book ->
        BookshelfBookCard(
            book.card,
            layout,
            { onOpenBook(book.card.key) },
            { onBookInfo(book.card.key) },
            gridTitle = title,
            cover = { bookCover(book, it) },
        )
    }
}
