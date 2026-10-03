package io.legado.app.ui.rss.favorites

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.RssFavoriteRow
import io.legado.app.ui.components.LegadoTopAppBar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun RssFavoriteListScreen(
    state: RssFavoriteListState,
    showToolbar: Boolean,
    back: () -> Unit,
    selectGroup: (String) -> Unit,
    read: (String) -> Unit,
    delete: (String) -> Unit,
    deleteGroup: () -> Unit,
    deleteAll: () -> Unit,
    confirmDelete: () -> Unit,
    cancelDelete: () -> Unit,
    retry: () -> Unit,
    position: (String) -> FavoriteScrollPosition,
    scrolled: (String, Int, Int) -> Unit,
    image: @Composable (RssFavoriteRow) -> Unit = {},
) {
    var groupsMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val groupLabel = stringResource(R.string.group)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().testTag("rss-favorites-screen")) {
            if (showToolbar)
                LegadoTopAppBar(
                    stringResource(R.string.favorites),
                    back,
                    actions = {
                        Box {
                            IconButton(
                                onClick = { groupsMenu = true },
                                enabled = state.loaded,
                                modifier = Modifier.testTag("rss-favorites-groups"),
                            ) {
                                Icon(painterResource(R.drawable.ic_groups), groupLabel)
                            }
                            DropdownMenu(groupsMenu, { groupsMenu = false }) {
                                state.groups.forEach { group ->
                                    DropdownMenuItem(
                                        text = { Text(group) },
                                        modifier =
                                            Modifier.testTag("rss-favorites-group-menu-$group"),
                                        onClick = {
                                            groupsMenu = false
                                            selectGroup(group)
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { moreMenu = true },
                                modifier = Modifier.testTag("rss-favorites-menu"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.menu),
                                )
                            }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_select_group)) },
                                    enabled =
                                        state.loaded && !state.busy && state.groups.isNotEmpty(),
                                    modifier = Modifier.testTag("rss-favorites-delete-group"),
                                    onClick = {
                                        moreMenu = false
                                        deleteGroup()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_all)) },
                                    enabled = state.loaded && !state.busy,
                                    modifier = Modifier.testTag("rss-favorites-delete-all"),
                                    onClick = {
                                        moreMenu = false
                                        deleteAll()
                                    },
                                )
                            }
                        }
                    },
                )
            if (!state.loaded || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("rss-favorites-working"))
            state.error?.let {
                Text(
                    it,
                    Modifier.padding(16.dp).testTag("rss-favorites-error"),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = retry, modifier = Modifier.testTag("rss-favorites-retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            if (state.loaded && state.groups.isNotEmpty()) {
                if (state.groups.size > 1)
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("rss-favorites-tabs")
                    ) {
                        state.groups.forEach { group ->
                            Tab(
                                selected = state.group == group,
                                onClick = { selectGroup(group) },
                                modifier =
                                    Modifier.widthIn(min = 72.dp)
                                        .testTag("rss-favorites-tab-$group"),
                                text = { Text(group, maxLines = 1) },
                            )
                        }
                    }
                val pager =
                    rememberPagerState(
                        initialPage = state.groups.indexOf(state.group).coerceAtLeast(0),
                        pageCount = { state.groups.size },
                    )
                val selected by rememberUpdatedState(selectGroup)
                LaunchedEffect(state.groups, state.group) {
                    val index = state.groups.indexOf(state.group)
                    if (index >= 0 && pager.currentPage != index) pager.scrollToPage(index)
                }
                LaunchedEffect(pager, state.groups) {
                    var moved = false
                    snapshotFlow { pager.isScrollInProgress to pager.settledPage }
                        .distinctUntilChanged()
                        .collect { (moving, index) ->
                            if (moving) moved = true
                            else if (moved) {
                                moved = false
                                state.groups.getOrNull(index)?.let(selected)
                            }
                        }
                }
                HorizontalPager(
                    pager,
                    Modifier.weight(1f).testTag("rss-favorites-pager"),
                    key = { state.groups[it] },
                ) { page ->
                    val group = state.groups[page]
                    key(group) {
                        val initial = remember(group) { position(group) }
                        val list = rememberLazyListState(initial.index, initial.offset)
                        val savePosition by rememberUpdatedState(scrolled)
                        LaunchedEffect(list, group) {
                            snapshotFlow {
                                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                            }
                                .distinctUntilChanged()
                                .collect { (index, offset) -> savePosition(group, index, offset) }
                        }
                        LazyColumn(
                            Modifier.fillMaxSize()
                                .navigationBarsPadding()
                                .testTag("rss-favorites-list-$group"),
                            state = list,
                        ) {
                            items(state.rows.filter { it.group == group }, key = { it.id }) { row ->
                                RssFavoriteListRow(
                                    row,
                                    !state.busy,
                                    { read(row.id) },
                                    { delete(row.id) },
                                    image,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    state.confirmation?.let { confirmation ->
        val target =
            when (confirmation.kind) {
                FavoriteDeleteKind.Row ->
                    state.rows.firstOrNull { it.id == confirmation.target }?.title.orEmpty()
                FavoriteDeleteKind.Group -> confirmation.target + stringResource(R.string.group)
                FavoriteDeleteKind.All ->
                    stringResource(R.string.all) + stringResource(R.string.favorite)
            }
        AlertDialog(
            onDismissRequest = cancelDelete,
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Text(
                    stringResource(R.string.sure_del) + "\n<" + target + ">",
                    Modifier.testTag("rss-favorites-confirm-message"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = confirmDelete,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("rss-favorites-confirm-delete"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = cancelDelete,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("rss-favorites-cancel-delete"),
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssFavoriteListRow(
    row: RssFavoriteRow,
    enabled: Boolean,
    read: () -> Unit,
    delete: () -> Unit,
    image: @Composable (RssFavoriteRow) -> Unit,
) {
    val deleteLabel = stringResource(R.string.delete)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 100.dp)
            .combinedClickable(enabled = enabled, onClick = read, onLongClick = delete)
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            if (enabled) {
                                delete()
                                true
                            } else false
                        }
                    )
            }
            .testTag("rss-favorite-row-${row.id}")
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
            )
            row.pubDate?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        image(row)
    }
}
