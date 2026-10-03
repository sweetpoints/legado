package io.legado.app.ui.rss.subscription

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal class RuleSubscriptionDrag {
    var id by mutableStateOf<Long?>(null)
    var y by mutableFloatStateOf(0f)

    fun clear() {
        id = null
    }
}

private fun LazyListState.subscriptionAt(y: Float): Long? =
    layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
        as? Long

@Composable
internal fun rememberRuleSubscriptionDrag(
    list: LazyListState,
    actions: RuleSubscriptionActions,
): RuleSubscriptionDrag {
    val drag = remember { RuleSubscriptionDrag() }
    val current by rememberUpdatedState(actions)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, drag) {
        fun cancel() {
            drag.clear()
            current.cancelDrag()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            cancel()
        }
    }
    LaunchedEffect(list, drag.id) {
        while (drag.id != null) {
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
            list.subscriptionAt(drag.y.coerceIn(0f, (height - 1).coerceAtLeast(0f)))?.let { target
                ->
                drag.id?.let { current.move(it, target) }
            }
            delay(16)
        }
    }
    return drag
}

internal fun Modifier.ruleSubscriptionReorder(
    id: Long,
    list: LazyListState,
    drag: RuleSubscriptionDrag,
    enabled: Boolean,
    actions: RuleSubscriptionActions,
) =
    pointerInput(id, list, enabled, actions) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val item =
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                    ?: return@awaitEachGesture
            if (!actions.beginDrag()) return@awaitEachGesture
            drag.id = id
            drag.y = item.offset + item.size / 2f
            var completed = false
            try {
                while (true) {
                    val change =
                        awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                            it.id == pressed.id
                        } ?: break
                    if (drag.id == null) break
                    // Compose cancellation synthesizes a consumed release; check before consume.
                    if (!change.pressed) {
                        completed = !change.isConsumed
                        change.consume()
                        break
                    }
                    if (change.isConsumed) break
                    drag.y += change.positionChange().y
                    list.subscriptionAt(drag.y)?.let { actions.move(id, it) }
                    change.consume()
                }
            } finally {
                drag.clear()
                if (completed) actions.finishDrag() else actions.cancelDrag()
            }
        }
    }
