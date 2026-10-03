package io.legado.app.ui.book.toc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

class TocChapterActions(
    val open: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    val toggle: (String, String?) -> Unit = { _, _ -> },
    val scrolled: (Long) -> Unit = {},
    val current: () -> Unit = {},
    val top: () -> Unit = {},
    val bottom: () -> Unit = {},
    val retry: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TocChapterScreen(state: TocChapterState, actions: TocChapterActions, active: Boolean = true) {
    val list = rememberLazyListState()
    fun firstKey(): String? = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
    LaunchedEffect(state.scrollRequest, state.loaded, active) {
        if (active && state.loaded && state.scrollRequest != 0L) {
            withFrameNanos {}
            list.scrollToItem(
                state.scrollTarget.coerceIn(0, (state.rows.size - 1).coerceAtLeast(0))
            )
            actions.scrolled(state.scrollRequest)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        Modifier.weight(1f).padding(8.dp).testTag("toc-chapter-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(actions.retry, Modifier.testTag("toc-chapter-retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("toc-chapter-list"), state = list) {
                    items(state.rows, key = { it.key }) { row ->
                        val enabled = state.open == null
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(
                                    if (row.volume && !state.pdf)
                                        MaterialTheme.colorScheme.surfaceContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .combinedClickable(
                                    enabled = enabled,
                                    onClick = { actions.open(row.key, false, firstKey()) },
                                    onLongClick = { actions.open(row.key, true, firstKey()) },
                                )
                                .padding(
                                    start = (12 + row.depth * 10).dp,
                                    end = 4.dp,
                                    top = 4.dp,
                                    bottom = 4.dp,
                                )
                                .testTag("toc-chapter-row-${row.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (row.locked)
                                Icon(
                                    painterResource(R.drawable.ic_lock_outline),
                                    "vip",
                                    Modifier.size(24.dp)
                                        .padding(end = 8.dp)
                                        .testTag("toc-chapter-locked-${row.key}"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            Column(Modifier.weight(1f)) {
                                val title =
                                    row.title.ifBlank {
                                        if (state.pdf) stringResource(R.string.pdf_outline_untitled)
                                        else ""
                                    }
                                Text(
                                    title,
                                    Modifier.testTag("toc-chapter-title-${row.key}"),
                                    color =
                                        if (row.current) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    maxLines = if (state.pdf) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val summary =
                                    if (state.pdf)
                                        row.pdfPage?.let {
                                            stringResource(R.string.pdf_outline_page, it + 1)
                                        }
                                    else if (row.volume) volumeSummary(row)
                                    else row.tag?.takeIf { it.isNotEmpty() }
                                Row {
                                    summary?.let {
                                        Text(
                                            it,
                                            Modifier.weight(1f, fill = false)
                                                .padding(end = 18.dp)
                                                .testTag("toc-chapter-summary-${row.key}"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    row.words
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let {
                                            Text(
                                                it,
                                                Modifier.testTag("toc-chapter-words-${row.key}"),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                }
                            }
                            if (row.canToggle) {
                                val description =
                                    stringResource(
                                        if (state.pdf) {
                                            if (row.collapsed) R.string.pdf_outline_expand
                                            else R.string.pdf_outline_collapse
                                        } else {
                                            if (row.collapsed) R.string.toc_expand_volume
                                            else R.string.toc_collapse_volume
                                        }
                                    )
                                IconButton(
                                    { actions.toggle(row.key, firstKey()) },
                                    Modifier.testTag("toc-chapter-toggle-${row.key}"),
                                    enabled = enabled,
                                ) {
                                    Icon(
                                        painterResource(
                                            if (row.collapsed) R.drawable.ic_arrow_right
                                            else R.drawable.ic_expand_more
                                        ),
                                        description,
                                    )
                                }
                            } else if (!state.pdf && !row.volume) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (row.current || !row.cached)
                                        Icon(
                                            painterResource(
                                                if (row.current) R.drawable.ic_check
                                                else R.drawable.ic_outline_cloud_24
                                            ),
                                            null,
                                            Modifier.size(24.dp)
                                                .testTag(
                                                    if (row.current)
                                                        "toc-chapter-current-${row.key}"
                                                    else "toc-chapter-cloud-${row.key}"
                                                ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                }
                            } else Spacer(Modifier.width(48.dp))
                        }
                        HorizontalDivider()
                    }
                }
                TocBookmarksFastScroll(
                    list,
                    Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .testTag("toc-chapter-fast-scroll"),
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 5.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.currentInfo,
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(onClick = actions.current)
                            .padding(10.dp)
                            .testTag("toc-chapter-current-info"),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(actions.top, Modifier.testTag("toc-chapter-top")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_drop_up),
                            stringResource(R.string.go_to_top),
                        )
                    }
                    IconButton(actions.bottom, Modifier.testTag("toc-chapter-bottom")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_drop_down),
                            stringResource(R.string.go_to_bottom),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun volumeSummary(row: TocChapterRow): String {
    val summary =
        if (row.matchedCount == null) stringResource(R.string.all_chapter_num, row.chapterCount)
        else if (row.matchedSelf && row.matchedCount == 0)
            stringResource(R.string.toc_volume_title_match)
        else stringResource(R.string.toc_search_match_count, row.matchedCount, row.chapterCount)
    return row.tag
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.toc_volume_tag_summary, it, summary) } ?: summary
}
