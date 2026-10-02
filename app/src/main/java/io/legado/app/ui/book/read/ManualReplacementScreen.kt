package io.legado.app.ui.book.read

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable internal fun ManualReplacementScreen(state: ManualReplacementState, toggle: (Long) -> Unit, all: () -> Unit,
    confirm: () -> Unit, cancel: () -> Unit, retry: () -> Unit, rangeStart: (Int) -> Unit, rangeMove: (Int) -> Unit,
    rangeEnd: () -> Unit, rangeCancel: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.loading && state.error == null && !state.finished
    val list = rememberLazyListState(); val density = LocalDensity.current
    val callbacks by rememberUpdatedState(listOf(rangeStart, rangeMove))
    val finish by rememberUpdatedState(rangeEnd); val abort by rememberUpdatedState(rangeCancel)
    val slideLeft = with(density) { 16.dp.toPx() }; val slideRight = with(density) { 50.dp.toPx() }
    val edge = with(density) { 48.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }; var fingerY by remember { mutableFloatStateOf(0f) }
    fun indexAt(y: Float): Int? {
        val items = list.layoutInfo.visibleItemsInfo
        return items.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index
            ?: if (y < 0) items.firstOrNull()?.index else items.lastOrNull()?.index
    }
    LaunchedEffect(dragging) {
        if (dragging) while (isActive) {
            val height = list.layoutInfo.viewportEndOffset.toFloat()
            val speed = when { fingerY < edge -> -((edge - fingerY) / edge).coerceIn(0f, 1f) * 18f
                fingerY > height - edge -> ((fingerY - height + edge) / edge).coerceIn(0f, 1f) * 18f; else -> 0f }
            if (speed != 0f) { list.scrollBy(speed); indexAt(fingerY)?.let { callbacks[1](it) } }
            delay(16)
        }
    }
    DisposableEffect(Unit) { onDispose { abort() } }
    Surface(modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Text(stringResource(R.string.manual_replace_rule), Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleLarge)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error); TextButton(retry) { Text(stringResource(R.string.retry)) } }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("manual-rule-list").pointerInput(enabled, list) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.position.x !in slideLeft..slideRight) return@awaitEachGesture
                    val index = indexAt(down.position.y) ?: return@awaitEachGesture
                    down.consume(); callbacks[0](index); fingerY = down.position.y; dragging = true
                    var complete = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.isConsumed) break
                            fingerY = pointer.position.y; indexAt(fingerY)?.let { callbacks[1](it) }; pointer.consume()
                            if (!pointer.pressed) { complete = true; break }
                        }
                    } finally { dragging = false; if (complete) finish() else abort() }
                }
            }, state = list) {
                items(state.rows, key = { it.id }) { row ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manual-rule-${row.id}")
                        .toggleable(row.id in state.selected, enabled, Role.Checkbox) { toggle(row.id) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Checkbox(row.id in state.selected, null)
                        Text(row.name, Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(all, enabled = enabled, modifier = Modifier.weight(1f).testTag("manual-all")) {
                    Text(stringResource(if (state.allSelected) R.string.select_cancel_count else R.string.select_all_count, state.selected.size, state.rows.size))
                }
                TextButton(cancel, modifier = Modifier.testTag("manual-cancel")) { Text(stringResource(R.string.cancel)) }
                TextButton(confirm, enabled = enabled, modifier = Modifier.testTag("manual-confirm")) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
