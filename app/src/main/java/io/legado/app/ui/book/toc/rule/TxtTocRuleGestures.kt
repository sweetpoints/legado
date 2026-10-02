package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Pointer positions are in list pixels, matching the original 16..50px selection strip. */
internal class TxtTocListDrag {
    var name by mutableStateOf<Long?>(null)
    var y by mutableFloatStateOf(0f)
    var selecting by mutableStateOf(false)
    fun clear() { name = null; selecting = false }
}
internal fun LazyListState.ruleAt(y: Float): Long? = layoutInfo.visibleItemsInfo
    .firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? Long

@Composable
internal fun rememberTxtTocListDrag(list: LazyListState, actions: TxtTocManagementActions): TxtTocListDrag {
    val drag = remember { TxtTocListDrag() }
    val current by rememberUpdatedState(actions)
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, drag) {
        fun cancel() { drag.clear(); current.cancelSlide(); current.cancelReorder() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(list, drag.name, drag.selecting) {
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
            list.ruleAt(drag.y.coerceIn(0f, (height - 1).coerceAtLeast(0f)))?.let { target ->
                if (drag.selecting) current.slideTo(target) else drag.name?.let { current.move(it, target) }
            }
            delay(16)
        }
    }
    return drag
}
internal fun Modifier.txtTocSlideSelection(list: LazyListState, drag: TxtTocListDrag,
    enabled: Boolean, actions: TxtTocManagementActions): Modifier = pointerInput(list, enabled, actions) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x !in 16f..50f) return@awaitEachGesture
        val anchor = list.ruleAt(down.position.y) ?: return@awaitEachGesture
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        drag.name = anchor; drag.selecting = true; drag.y = start.position.y
        actions.beginSlide(anchor)
        list.ruleAt(drag.y)?.let(actions.slideTo)
        var completed = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == start.id } ?: break
                if (drag.name == null) break
                // Synthetic release on cancel is already consumed. Inspect it before consuming.
                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                if (change.isConsumed) break
                drag.y = change.position.y; list.ruleAt(drag.y)?.let(actions.slideTo); change.consume()
            }
        } finally {
            if (completed) actions.endSlide() else actions.cancelSlide()
            drag.clear()
        }
    }
}
internal fun Modifier.txtTocReorder(name: Long, list: LazyListState, drag: TxtTocListDrag,
    enabled: Boolean, actions: TxtTocManagementActions): Modifier = pointerInput(name, list, enabled, actions) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == name } ?: return@awaitEachGesture
        drag.name = name; drag.selecting = false; drag.y = item.offset + item.size / 2f
        var completed = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pressed.id } ?: break
                if (drag.name == null) break
                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                if (change.isConsumed) break
                drag.y += change.positionChange().y
                list.ruleAt(drag.y)?.let { actions.move(name, it) }
                change.consume()
            }
        } finally {
            drag.clear()
            if (completed) actions.finishReorder() else actions.cancelReorder()
        }
    }
}
