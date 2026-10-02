package io.legado.app.ui.book.read.config

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.ReaderMenuItem
import io.legado.app.ui.components.LegadoTopAppBar
import kotlinx.coroutines.isActive

/** The left strip slide-selects; dragging the right handle reorders within the same group. */
@Composable
internal fun ReaderMenuConfigScreen(
    state: ReaderMenuConfigUiState,
    onAction: (ReaderMenuEditAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val action by rememberUpdatedState(onAction)
    val density = LocalDensity.current
    val selectionWidth = with(density) { 24.dp.toPx() }
    val reorderWidth = with(density) { 48.dp.toPx() }
    var selectionGesture by remember { mutableStateOf<Boolean?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    fun keyAt(y: Float): String? {
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if (y < (visible.firstOrNull()?.offset ?: 0)) visible.firstOrNull() else visible.lastOrNull()
        return item?.key as? String
    }
    fun moveTo(y: Float) {
        val key = keyAt(y) ?: return
        if (selectionGesture == true) action(ReaderMenuEditAction.SelectionTo(key))
        else draggedKey?.let { action(ReaderMenuEditAction.Move(it, key)) }
    }

    // Keep extending the gesture while the finger remains in an edge hotspot.
    LaunchedEffect(selectionGesture) {
        if (selectionGesture == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val height = info.viewportEndOffset - info.viewportStartOffset
            val edge = height * 0.2f
            val step = when {
                dragY < info.viewportStartOffset + edge -> -10f
                dragY > info.viewportEndOffset - edge -> 10f
                else -> 0f
            }
            if (step != 0f) {
                listState.scrollBy(step)
                moveTo(dragY)
            }
        }
    }

    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            LegadoTopAppBar(stringResource(R.string.reader_menu_config), onClose,
                windowInsets = WindowInsets(0, 0, 0, 0), actions = {
                    Box {
                        IconButton({ menuExpanded = true }, Modifier.testTag("reader-menu-actions")) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu))
                        }
                        DropdownMenu(menuExpanded, { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_menu_select_all)) },
                                onClick = { menuExpanded = false; action(ReaderMenuEditAction.SetAll(true)) },
                                modifier = Modifier.testTag("reader-menu-select-all"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_menu_select_none)) },
                                onClick = { menuExpanded = false; action(ReaderMenuEditAction.SetAll(false)) },
                                modifier = Modifier.testTag("reader-menu-select-none"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.restore_default)) },
                                onClick = { menuExpanded = false; action(ReaderMenuEditAction.Reset) },
                                modifier = Modifier.testTag("reader-menu-reset"))
                        }
                    }
                })
            state.error?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton({ action(ReaderMenuEditAction.RetrySave) }, Modifier.testTag("reader-menu-retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            LazyColumn(state = listState,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().testTag("reader-menu-list")
                    .pointerInput(listState, selectionWidth, reorderWidth) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val selection = down.position.x <= selectionWidth
                            val reorder = down.position.x >= size.width - reorderWidth
                            if (!selection && !reorder) return@awaitEachGesture
                            val key = keyAt(down.position.y) ?: return@awaitEachGesture
                            down.consume()
                            dragY = down.position.y
                            draggedKey = key
                            selectionGesture = selection
                            action(if (selection) ReaderMenuEditAction.StartSelection(key) else ReaderMenuEditAction.StartReorder(key))
                            var completed = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); completed = true; break }
                                    if (change.isConsumed) break
                                    change.consume()
                                    dragY = change.position.y
                                    moveTo(dragY)
                                }
                            } finally {
                                selectionGesture = null
                                draggedKey = null
                                action(ReaderMenuEditAction.FinishGesture(completed))
                            }
                        }
                    }) {
                itemsIndexed(state.entries, key = { _, entry -> entry.key }) { index, entry ->
                    val item = ReaderMenuItem.byKey[entry.key] ?: return@itemsIndexed
                    Column {
                        if (index == 0 || state.entries[index - 1].primary != entry.primary) {
                            Text(stringResource(if (entry.primary) R.string.reader_menu_zone_primary else R.string.reader_menu_zone_more),
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        val moveUp = stringResource(R.string.auto_task_move_up)
                        val moveDown = stringResource(R.string.auto_task_move_down)
                        Row(Modifier.fillMaxWidth().testTag("reader-menu-item-${entry.key}")
                            .toggleable(entry.primary, role = Role.Checkbox,
                                onValueChange = { action(ReaderMenuEditAction.Toggle(entry.key, it)) })
                            .semantics {
                                customActions = buildList {
                                    if (index > 0 && state.entries[index - 1].primary == entry.primary)
                                        add(CustomAccessibilityAction(moveUp) { action(ReaderMenuEditAction.Step(entry.key, -1)); true })
                                    if (index < state.entries.lastIndex && state.entries[index + 1].primary == entry.primary)
                                        add(CustomAccessibilityAction(moveDown) { action(ReaderMenuEditAction.Step(entry.key, 1)); true })
                                }
                            }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(entry.primary, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
                            Text(stringResource(item.titleRes), Modifier.weight(1f))
                            Box(Modifier.size(48.dp).testTag("reader-menu-drag-${entry.key}"), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.drag_to_reorder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
