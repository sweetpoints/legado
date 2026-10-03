package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cover rendering is supplied by the Route; this component only consumes immutable presentation
 * data.
 */
@Composable
fun BookshelfBookCard(
    model: BookshelfBookCardModel,
    layout: BookshelfCardLayout,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    gridTitle: BookshelfGridTitle = BookshelfGridTitle.Below,
    cover: @Composable (Modifier) -> Unit,
) {
    Surface(
        modifier
            .combinedClickable(onClick = onOpen, onLongClick = onInfo)
            .testTag("shelf-book-${model.key}"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (layout) {
            BookshelfCardLayout.Grid -> GridBookCard(model, gridTitle, cover)
            else -> ListBookCard(model, layout == BookshelfCardLayout.Compact, cover)
        }
    }
}

@Composable
private fun GridBookCard(
    model: BookshelfBookCardModel,
    title: BookshelfGridTitle,
    cover: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f)) {
            cover(Modifier.fillMaxSize())
            if (title == BookshelfGridTitle.Overlay)
                Text(
                    model.name,
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .7f))
                            )
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("shelf-name-${model.key}"),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            BookUpdateBadge(model, Modifier.align(Alignment.TopEnd))
            BookProgress(model, Modifier.align(Alignment.BottomCenter))
        }
        if (title == BookshelfGridTitle.Below)
            Text(
                model.name,
                Modifier.fillMaxWidth().padding(top = 4.dp).testTag("shelf-name-${model.key}"),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
    }
}

@Composable
private fun ListBookCard(
    model: BookshelfBookCardModel,
    compact: Boolean,
    cover: @Composable (Modifier) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover(Modifier.size(if (compact) 48.dp else 72.dp, if (compact) 64.dp else 96.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    Modifier.weight(1f).testTag("shelf-name-${model.key}"),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BookUpdateBadge(model)
            }
            if (compact) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model.author,
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        model.currentChapter,
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    model.latestUpdateLabel?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                BookDetail(model.author)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookDetail(model.currentChapter, Modifier.weight(1f))
                    model.latestUpdateLabel?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookDetail(model.latestChapter, Modifier.weight(1f))
                model.progressPercent?.let {
                    Text(
                        it,
                        Modifier.padding(start = 6.dp).testTag("shelf-percent-${model.key}"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
            BookProgress(model)
        }
    }
}

@Composable
private fun BookDetail(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BookUpdateBadge(model: BookshelfBookCardModel, modifier: Modifier = Modifier) {
    if (model.updating) {
        CircularProgressIndicator(
            modifier.size(22.dp).testTag("shelf-updating-${model.key}"),
            strokeWidth = 2.dp,
        )
    } else if (model.unreadCount > 0) {
        val color =
            if (model.highlightUnread) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest
        Surface(
            modifier.testTag("shelf-unread-${model.key}"),
            color = color,
            contentColor =
                if (model.highlightUnread) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                model.unreadCount.toString(),
                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun BookProgress(model: BookshelfBookCardModel, modifier: Modifier = Modifier) {
    model.readProgress?.let { fraction ->
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(model.progressThicknessDp.dp)
                    .testTag("shelf-progress-${model.key}"),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
