package io.legado.app.ui.book.read.config

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.TextSelectMenuItem
import io.legado.app.ui.components.LegadoTopAppBar
import kotlinx.coroutines.isActive

@Composable internal fun TextSelectMenuSettingsScreen(state: TextSelectMenuSettingsUiState,
    onAction: (TextSelectMenuSettingsAction) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val action by rememberUpdatedState(onAction)
    val handleWidth = with(LocalDensity.current) { 48.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var menu by rememberSaveable { mutableStateOf(false) }
    fun keyAt(y: Float): String? {
        val visible = list.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if (y < (visible.firstOrNull()?.offset ?: 0)) visible.firstOrNull() else visible.lastOrNull()
        return item?.key as? String
    }
    fun moveTo(y: Float) { keyAt(y)?.let { action(TextSelectMenuSettingsAction.MoveTo(it)) } }
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val info = list.layoutInfo
            val edge = (info.viewportEndOffset - info.viewportStartOffset) * .2f
            val step = when {
                pointerY < info.viewportStartOffset + edge -> -10f
                pointerY > info.viewportEndOffset - edge -> 10f
                else -> 0f
            }
            if (step != 0f) { list.scrollBy(step); moveTo(pointerY) }
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            LegadoTopAppBar(stringResource(R.string.text_select_menu_config), onClose,
                windowInsets = WindowInsets(0, 0, 0, 0), actions = {
                    Box {
                        IconButton({ menu = true }, Modifier.testTag("text-select-menu-actions")) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.restore_default)) },
                                onClick = { menu = false; action(TextSelectMenuSettingsAction.Reset) },
                                modifier = Modifier.testTag("text-select-menu-reset"))
                        }
                    }
                })
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton({ action(TextSelectMenuSettingsAction.Retry) }, Modifier.testTag("text-select-menu-retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                .testTag("text-select-menu-list").pointerInput(list, handleWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.position.x > handleWidth) return@awaitEachGesture
                        val key = keyAt(down.position.y) ?: return@awaitEachGesture
                        if (key !in TextSelectMenuItem.byKey) return@awaitEachGesture
                        down.consume()
                        pointerY = down.position.y
                        dragging = true
                        action(TextSelectMenuSettingsAction.StartDrag(key))
                        var completed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Compose synthesizes cancellation as an already consumed release.
                                if (!change.pressed) { completed = !change.isConsumed; change.consume(); break }
                                if (change.isConsumed) break
                                change.consume()
                                pointerY = change.position.y
                                moveTo(pointerY)
                            }
                        } finally {
                            dragging = false
                            action(TextSelectMenuSettingsAction.FinishDrag(completed))
                        }
                    }
                }) {
                itemsIndexed(state.rows, key = { _, key -> key }) { index, key ->
                    val item = TextSelectMenuItem.byKey[key]
                    if (item == null) {
                        Text(stringResource(if (key == TextSelectMenuSettingsViewModel.ZONE_BAR) R.string.text_menu_zone_bar else R.string.text_menu_zone_more),
                            Modifier.fillMaxWidth().testTag("text-select-menu-$key").padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val inBar = index < state.rows.indexOf(TextSelectMenuSettingsViewModel.ZONE_MORE)
                        val transferLabel = stringResource(if (inBar) R.string.move_to_more else R.string.move_to_floating_bar)
                        val up = stringResource(R.string.auto_task_move_up)
                        val down = stringResource(R.string.auto_task_move_down)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("text-select-menu-item-$key")
                            .semantics {
                                customActions = buildList {
                                    if (index > 1) add(CustomAccessibilityAction(up) { action(TextSelectMenuSettingsAction.Step(key, -1)); true })
                                    if (index < state.rows.lastIndex) add(CustomAccessibilityAction(down) { action(TextSelectMenuSettingsAction.Step(key, 1)); true })
                                    add(CustomAccessibilityAction(transferLabel) { action(TextSelectMenuSettingsAction.Transfer(key)); true })
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).testTag("text-select-menu-drag-$key"), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.drag_to_reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(item.titleRes), Modifier.weight(1f))
                            IconButton({ action(TextSelectMenuSettingsAction.Transfer(key)) }, Modifier.testTag("text-select-menu-transfer-$key")) {
                                Icon(painterResource(if (inBar) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_drop_up), transferLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}
