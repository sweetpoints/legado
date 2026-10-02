package io.legado.app.ui.book.group

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Reordering commits on a real pointer release; synthetic cancellation restores the baseline. */
internal class BookGroupDrag {
    var name by mutableStateOf<Long?>(null)
    var y by mutableFloatStateOf(0f)
    fun clear() { name = null }
}
internal fun LazyListState.groupAt(y: Float): Long? = layoutInfo.visibleItemsInfo
    .firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? Long

@Composable
internal fun rememberBookGroupDrag(list: LazyListState, actions: BookGroupManagementActions): BookGroupDrag {
    val drag = remember { BookGroupDrag() }
    val current by rememberUpdatedState(actions)
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, drag) {
        fun cancel() { drag.clear(); current.cancel() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(list, drag.name) {
        if (drag.name == null) return@LaunchedEffect
        while (true) {
            val height = list.layoutInfo.viewportEndOffset.toFloat()
            val edge = (height * .15f).coerceAtMost(96f)
            val speed = when {
                drag.y < edge -> -((edge - drag.y) / edge).coerceIn(0f, 1f) * 28f
                drag.y > height - edge -> ((drag.y - height + edge) / edge).coerceIn(0f, 1f) * 28f
                else -> 0f
            }
            if (speed != 0f) list.scrollBy(speed)
            list.groupAt(drag.y.coerceIn(0f, (height - 1).coerceAtLeast(0f)))?.let { target ->
                drag.name?.let { current.move(it, target) }
            }
            delay(16)
        }
    }
    return drag
}
internal fun Modifier.bookGroupReorder(name: Long, list: LazyListState, drag: BookGroupDrag,
    enabled: Boolean, actions: BookGroupManagementActions): Modifier = pointerInput(name, list, enabled, actions) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == name } ?: return@awaitEachGesture
        drag.name = name; drag.y = item.offset + item.size / 2f
        var completed = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pressed.id } ?: break
                if (drag.name == null) break
                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                if (change.isConsumed) break
                drag.y += change.positionChange().y
                list.groupAt(drag.y)?.let { actions.move(name, it) }
                change.consume()
            }
        } finally {
            drag.clear()
            if (completed) actions.finish() else actions.cancel()
        }
    }
}
