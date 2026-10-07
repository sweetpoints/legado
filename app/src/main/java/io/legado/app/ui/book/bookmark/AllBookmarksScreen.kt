package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

class AllBookmarksActions(
    val close: () -> Unit = {},
    val open: (Long, Boolean) -> Unit = { _, _ -> },
    val export: (Boolean) -> Unit = {},
    val scroll: (Int, Int) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllBookmarksScreen(state: AllBookmarksState, actions: AllBookmarksActions) {
    val list = rememberLazyListState()
    var menu by rememberSaveable { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(actions)
    val count =
        remember(state.rows) {
            state.rows.size + state.rows.map { it.bookName to it.bookAuthor }.distinct().size
        }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !restored) {
            withFrameNanos {}
            list.scrollToItem(state.scroll.coerceAtMost((count - 1).coerceAtLeast(0)), state.offset)
            restored = true
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect {
                if (restored) action.scroll(it.first, it.second)
            }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(actions.close, Modifier.testTag("all-bookmarks-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    Text(
                        stringResource(R.string.all_bookmark),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Box {
                        IconButton(
                            { menu = true },
                            Modifier.testTag("all-bookmarks-menu"),
                            enabled = !state.exporting && state.effect == null,
                        ) {
                            Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.menu))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export)) },
                                {
                                    menu = false
                                    actions.export(false)
                                },
                                Modifier.testTag("all-bookmarks-export-json"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export_md)) },
                                {
                                    menu = false
                                    actions.export(true)
                                },
                                Modifier.testTag("all-bookmarks-export-md"),
                            )
                        }
                    }
                }
            }
            if (!state.loaded || state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loaded)
                TextButton(actions.retry, Modifier.testTag("all-bookmarks-retry")) {
                    Text(stringResource(R.string.retry))
                }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("all-bookmarks-list"),
                state = list,
            ) {
                state.rows.forEachIndexed { index, row ->
                    if (
                        index == 0 ||
                            state.rows[index - 1].bookName != row.bookName ||
                            state.rows[index - 1].bookAuthor != row.bookAuthor
                    ) {
                        stickyHeader(key = "header-${row.id}") {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Text(
                                    row.group,
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 32.dp)
                                        .padding(horizontal = 16.dp, vertical = 5.dp)
                                        .testTag("all-bookmarks-header-${row.id}"),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    item(key = "bookmark-${row.id}") {
                        Card(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                .testTag("all-bookmarks-row-${row.id}")
                                .combinedClickable(
                                    enabled = !state.exporting && state.effect == null,
                                    onClick = { actions.open(row.id, false) },
                                    onLongClick = { actions.open(row.id, true) },
                                ),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    row.chapter,
                                    Modifier.testTag("all-bookmarks-chapter-${row.id}"),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.original.isNotEmpty())
                                    Text(
                                        row.original,
                                        Modifier.padding(top = 4.dp)
                                            .testTag("all-bookmarks-original-${row.id}"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                if (row.content.isNotEmpty())
                                    Text(
                                        row.content,
                                        Modifier.padding(top = 4.dp)
                                            .testTag("all-bookmarks-content-${row.id}"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}
