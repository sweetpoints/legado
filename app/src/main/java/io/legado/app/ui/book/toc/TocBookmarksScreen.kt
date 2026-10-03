package io.legado.app.ui.book.toc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

class TocBookmarksActions(val open: (Long, Boolean) -> Unit = { _, _ -> }, val scrolled: (Long) -> Unit = {},
    val retry: () -> Unit = {}, val clearError: () -> Unit = {})
@OptIn(ExperimentalFoundationApi::class)
@Composable fun TocBookmarksScreen(state: TocBookmarksState, actions: TocBookmarksActions, active: Boolean = true) {
    val list = rememberLazyListState()
    LaunchedEffect(state.scrollRequest, state.loaded, active) {
        if (active && state.loaded && state.scrollRequest != 0L) {
            withFrameNanos { }; list.scrollToItem(state.scrollTarget.coerceAtMost((state.rows.size - 1).coerceAtLeast(0)), 0)
            actions.scrolled(state.scrollRequest)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f).padding(8.dp).testTag("toc-bookmarks-error"), color = MaterialTheme.colorScheme.error)
                TextButton(actions.retry, Modifier.testTag("toc-bookmarks-retry")) { Text(stringResource(R.string.retry)) }
            } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("toc-bookmarks-list"), state = list) {
                    items(state.rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).testTag("toc-bookmarks-row-${row.id}")
                            .combinedClickable(enabled = state.open == null, onClick = { actions.open(row.id, false) }, onLongClick = { actions.open(row.id, true) }),
                            shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(row.chapter, Modifier.testTag("toc-bookmarks-chapter-${row.id}"), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.original.isNotEmpty()) Text(row.original, Modifier.padding(top = 4.dp).testTag("toc-bookmarks-original-${row.id}"),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.content.isNotEmpty()) Text(row.content, Modifier.padding(top = 4.dp).testTag("toc-bookmarks-content-${row.id}"),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                TocBookmarksFastScroll(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("toc-bookmarks-fast-scroll"))
            }
        }
    }
}
