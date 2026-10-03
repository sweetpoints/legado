package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun TxtTocFastScroll(list: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val total = list.layoutInfo.totalItemsCount
    val visible = list.layoutInfo.visibleItemsInfo.size
    if (total <= visible || visible == 0) return
    val color = MaterialTheme.colorScheme.primary.copy(alpha = .65f)
    var height by remember { mutableIntStateOf(1) }
    val maximum = (total - visible).coerceAtLeast(1)
    val progress = list.firstVisibleItemIndex.toFloat() / maximum
    val thumb = (visible.toFloat() / total).coerceAtLeast(.08f)
    val currentMaximum by rememberUpdatedState(maximum)
    val currentThumb by rememberUpdatedState(thumb)
    fun scroll(y: Float) {
        val fraction = ((y / height - currentThumb / 2) / (1 - currentThumb)).coerceIn(0f, 1f)
        scope.launch { list.scrollToItem((fraction * currentMaximum).toInt()) }
    }
    Canvas(
        modifier
            .width(20.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                setProgress { value ->
                    scope.launch { list.scrollToItem((value.coerceIn(0f, 1f) * maximum).toInt()) }
                    true
                }
            }
            .pointerInput(list) {
                detectDragGestures(onDragStart = { scroll(it.y) }) { change, _ ->
                    change.consume()
                    scroll(change.position.y)
                }
            }
    ) {
        val thumbHeight =
            (size.height * thumb).coerceAtLeast(32.dp.toPx()).coerceAtMost(size.height)
        drawRoundRect(
            color,
            Offset(
                size.width - 6.dp.toPx(),
                progress.coerceIn(0f, 1f) * (size.height - thumbHeight),
            ),
            Size(4.dp.toPx(), thumbHeight),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}
