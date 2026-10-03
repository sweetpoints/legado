package io.legado.app.ui.autoTask

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Pointer positions are in list pixels, matching the original 16..50px selection strip. */
internal class AutoTaskSlide {
    var name by mutableStateOf<String?>(null)
    var y by mutableFloatStateOf(0f)
    var selecting by mutableStateOf(false)

    fun clear() {
        name = null
        selecting = false
    }
}

internal fun LazyListState.taskAt(y: Float): String? =
    layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
        as? String

@Composable
internal fun rememberAutoTaskSlide(
    list: LazyListState,
    actions: AutoTaskManagementActions,
): AutoTaskSlide {
    val drag = remember { AutoTaskSlide() }
    val current by rememberUpdatedState(actions)
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, drag) {
        fun cancel() {
            drag.clear()
            current.cancelSlide()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) cancel()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            cancel()
        }
    }
    LaunchedEffect(list, drag.name, drag.selecting) {
        if (drag.name == null) return@LaunchedEffect
        while (true) {
            val height = list.layoutInfo.viewportEndOffset.toFloat()
            val edge = (height * .15f).coerceAtMost(96f)
            val speed =
                when {
                    drag.y < edge -> -((edge - drag.y) / edge).coerceIn(0f, 1f) * 28f
                    drag.y > height - edge ->
                        ((drag.y - height + edge) / edge).coerceIn(0f, 1f) * 28f
                    else -> 0f
                }
            if (speed != 0f) list.scrollBy(speed)
            list.taskAt(drag.y.coerceIn(0f, (height - 1).coerceAtLeast(0f)))?.let { target ->
                current.slideTo(target)
            }
            delay(16)
        }
    }
    return drag
}

internal fun Modifier.autoTaskSlideSelection(
    list: LazyListState,
    drag: AutoTaskSlide,
    enabled: Boolean,
    actions: AutoTaskManagementActions,
): Modifier =
    pointerInput(list, enabled, actions) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x !in 16f..50f) return@awaitEachGesture
            val anchor = list.taskAt(down.position.y) ?: return@awaitEachGesture
            val start =
                awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
            drag.name = anchor
            drag.selecting = true
            drag.y = start.position.y
            actions.beginSlide(anchor)
            list.taskAt(drag.y)?.let(actions.slideTo)
            var completed = false
            try {
                while (true) {
                    val change =
                        awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                            it.id == start.id
                        } ?: break
                    if (drag.name == null) break
                    // Synthetic release on cancel is already consumed. Inspect it before consuming.
                    if (!change.pressed) {
                        completed = !change.isConsumed
                        change.consume()
                        break
                    }
                    if (change.isConsumed) break
                    drag.y = change.position.y
                    list.taskAt(drag.y)?.let(actions.slideTo)
                    change.consume()
                }
            } finally {
                if (completed) actions.endSlide() else actions.cancelSlide()
                drag.clear()
            }
        }
    }
