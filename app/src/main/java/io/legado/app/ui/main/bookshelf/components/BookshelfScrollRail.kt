package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import io.legado.app.R

/** An optional drag rail plus a passive scrollbar when fast scrolling is disabled. */
@Composable internal fun BookshelfScrollRail(fraction: Float, visibleFraction: Float, interactive: Boolean,
    onScroll: (Float) -> Unit, modifier: Modifier = Modifier) {
    val scroll by rememberUpdatedState(onScroll)
    val description = stringResource(R.string.show_bookshelf_fast_scroller)
    val thumb = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    val rail = if (interactive) Modifier.width(32.dp).testTag("bookshelf-fast-scroll")
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            setProgress { scroll(it.coerceIn(0f, 1f)); true }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                scroll((down.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                    change.consume()
                    scroll((change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
        } else Modifier.width(4.dp).testTag("bookshelf-scrollbar")
    Box(modifier.then(rail).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
        Canvas(Modifier.width(4.dp).fillMaxHeight()) {
            val height = (size.height * visibleFraction.coerceIn(.05f, 1f)).coerceAtMost(size.height)
            if (interactive) drawRoundRect(track, cornerRadius = CornerRadius(size.width / 2))
            drawRoundRect(thumb, topLeft = Offset(0f, (size.height - height) * fraction.coerceIn(0f, 1f)),
                size = Size(size.width, height), cornerRadius = CornerRadius(size.width / 2))
        }
    }
}
