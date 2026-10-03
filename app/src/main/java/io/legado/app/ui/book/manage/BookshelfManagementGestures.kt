package io.legado.app.ui.book.manage

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal class ShelfManagementGesture {
    var id by mutableStateOf<String?>(null)
    var selecting by mutableStateOf(false)
    var y by mutableFloatStateOf(0f)
    fun clear() { id = null; selecting = false }
}
private fun LazyListState.shelfAt(y: Float) = layoutInfo.visibleItemsInfo.firstOrNull {
    y >= it.offset && y < it.offset + it.size
}
@Composable internal fun rememberShelfManagementGesture(list: LazyListState, actions: BookshelfManagementActions): ShelfManagementGesture {
    val gesture = remember { ShelfManagementGesture() }
    val current by rememberUpdatedState(actions)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, gesture) {
        fun cancel() { gesture.clear(); current.cancelGesture() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(list, gesture.id, gesture.selecting) {
        while (gesture.id != null) {
            val height = list.layoutInfo.viewportEndOffset.toFloat()
            val edge = (height * .15f).coerceAtMost(96f).coerceAtLeast(1f)
            val speed = when {
                gesture.y < edge -> -((edge - gesture.y) / edge).coerceIn(0f, 1f) * 28f
                gesture.y > height - edge -> ((gesture.y - height + edge) / edge).coerceIn(0f, 1f) * 28f
                else -> 0f
            }
            if (speed != 0f) list.scrollBy(speed)
            val target = list.shelfAt(gesture.y.coerceIn(0f, (height - 1f).coerceAtLeast(0f)))
            val key = target?.key as? String
            if (key != null) {
                if (gesture.selecting) gesture.id?.let { current.selectionRange(it, key) }
                else current.dragTo(key)
            }
            delay(16)
        }
    }
    return gesture
}
internal fun Modifier.shelfManagementSlideSelection(list: LazyListState, gesture: ShelfManagementGesture,
    enabled: Boolean, actions: BookshelfManagementActions) = pointerInput(list, enabled, actions) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > 64.dp.toPx()) return@awaitEachGesture
        val first = list.shelfAt(down.position.y)?.key as? String ?: return@awaitEachGesture
        var moved: PointerInputChange? = null
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || change.isConsumed || (gesture.id != null && !gesture.selecting)) break
            if (kotlin.math.abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                change.consume(); moved = change; break
            }
        }
        val movement = moved ?: return@awaitEachGesture
        if (!actions.beginSelection()) return@awaitEachGesture
        gesture.id = first; gesture.selecting = true; gesture.y = movement.position.y
        list.shelfAt(gesture.y)?.key?.let { actions.selectionRange(first, it as String) }
        var completed = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (gesture.id == null) break
                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                if (change.isConsumed) break
                gesture.y = change.position.y
                list.shelfAt(gesture.y)?.key?.let { actions.selectionRange(first, it as String) }
                change.consume()
            }
        } finally {
            gesture.clear(); if (completed) actions.finishSelection() else actions.cancelGesture()
        }
    }
}
internal fun Modifier.shelfManagementReorder(id: String, list: LazyListState, gesture: ShelfManagementGesture,
    enabled: Boolean, actions: BookshelfManagementActions) = pointerInput(id, list, enabled, actions) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return@awaitEachGesture
        if (!actions.beginDrag(id)) return@awaitEachGesture
        gesture.id = id; gesture.selecting = false; gesture.y = item.offset + item.size / 2f
        var completed = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pressed.id } ?: break
                if (gesture.id == null) break
                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                if (change.isConsumed) break
                gesture.y += change.positionChange().y
                list.shelfAt(gesture.y)?.let { target -> (target.key as? String)?.let { actions.dragTo(it) } }
                change.consume()
            }
        } finally {
            gesture.clear(); if (completed) actions.finishDrag() else actions.cancelGesture()
        }
    }
}
