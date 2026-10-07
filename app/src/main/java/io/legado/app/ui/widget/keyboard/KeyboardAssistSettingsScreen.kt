package io.legado.app.ui.widget.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.KeyboardAssistSettingsText
import io.legado.app.ui.widget.code.EditSafety
import kotlinx.coroutines.isActive

class KeyboardAssistSettingsActions(
    val close: () -> Unit = {},
    val add: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val begin: () -> Boolean = { false },
    val move: (String, String) -> Unit = { _, _ -> },
    val finish: () -> Unit = {},
    val cancelDrag: () -> Unit = {},
    val step: (String, Int) -> Unit = { _, _ -> },
    val lines: () -> Unit = {},
    val chooseLines: (Int) -> Unit = {},
    val cancelLines: () -> Unit = {},
    val saveLines: () -> Unit = {},
    val text: (Boolean, KeyboardAssistSettingsText) -> Unit = { _, _ -> },
    val saveEditor: () -> Unit = {},
    val cancelEditor: () -> Unit = {},
    val code: (Boolean) -> Unit = {},
    val retry: () -> Unit = {},
    val retryEditor: () -> Unit = {},
    val scroll: (Int, Int) -> Unit = { _, _ -> },
)

@Composable
fun KeyboardAssistSettingsScreen(
    state: KeyboardAssistSettingsState,
    actions: KeyboardAssistSettingsActions,
) {
    val list = rememberLazyListState()
    val action by rememberUpdatedState(actions)
    val currentState by rememberUpdatedState(state)
    val handle = with(LocalDensity.current) { 48.dp.toPx() }
    var active by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !restored) {
            withFrameNanos {}
            list.scrollToItem(
                state.scroll.coerceAtMost((state.rows.size - 1).coerceAtLeast(0)),
                state.offset,
            )
            restored = true
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect {
                if (restored && !currentState.dragging) action.scroll(it.first, it.second)
            }
    }
    fun keyAt(y: Float): String? {
        val visible = list.layoutInfo.visibleItemsInfo
        return (visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
                ?: if (y < (visible.firstOrNull()?.offset ?: 0)) visible.firstOrNull()
                else visible.lastOrNull())
            ?.key as? String
    }
    fun moveTo(y: Float) {
        val id = active ?: return
        keyAt(y)?.let { action.move(id, it) }
    }
    LaunchedEffect(active) {
        if (active == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos {}
            val info = list.layoutInfo
            val edge = (info.viewportEndOffset - info.viewportStartOffset) * .2f
            val delta =
                when {
                    pointerY < info.viewportStartOffset + edge -> -10f
                    pointerY > info.viewportEndOffset - edge -> 10f
                    else -> 0f
                }
            if (delta != 0f) {
                list.scrollBy(delta)
                moveTo(pointerY)
            }
        }
    }
    Surface {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(actions.close, enabled = !state.busy && !state.editorLoading) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(enabled = state.loaded && !state.busy) { actions.lines() }
                            .testTag("keyboard-settings-lines")
                    ) {
                        Text(
                            stringResource(R.string.assists_key_config),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.show_line_number, state.lineCount),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(
                        actions.add,
                        Modifier.testTag("keyboard-settings-add"),
                        enabled =
                            state.loaded && !state.busy && !state.editorLoading && !state.dragging,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add))
                    }
                }
            }
            if (!state.loaded || state.busy || state.editorLoading)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        Modifier.weight(1f).padding(8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        if (state.editorLoading || state.editor != null) actions.retryEditor
                        else actions.retry
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("keyboard-settings-list").pointerInput(
                    list,
                    handle,
                ) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        if (down.position.x > handle) return@awaitEachGesture
                        val id = keyAt(down.position.y) ?: return@awaitEachGesture
                        if (!action.begin()) return@awaitEachGesture
                        down.consume()
                        active = id
                        pointerY = down.position.y
                        var completed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    completed = !change.isConsumed
                                    change.consume()
                                    break
                                }
                                if (change.isConsumed) break
                                change.consume()
                                pointerY = change.position.y
                                moveTo(pointerY)
                            }
                        } finally {
                            active = null
                            if (completed) action.finish() else action.cancelDrag()
                        }
                    }
                },
                state = list,
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    val up = stringResource(R.string.auto_task_move_up)
                    val down = stringResource(R.string.auto_task_move_down)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("keyboard-settings-row-" + row.id)
                            .semantics {
                                customActions = buildList {
                                    if (index > 0)
                                        add(
                                            CustomAccessibilityAction(up) {
                                                action.step(row.id, -1)
                                                true
                                            }
                                        )
                                    if (index < state.rows.lastIndex)
                                        add(
                                            CustomAccessibilityAction(down) {
                                                action.step(row.id, 1)
                                                true
                                            }
                                        )
                                }
                            }
                            .pointerInput(row.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { position ->
                                        if (action.begin()) {
                                            active = row.id
                                            pointerY =
                                                (list.layoutInfo.visibleItemsInfo
                                                    .find { it.key == row.id }
                                                    ?.offset ?: 0) + position.y
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        if (active == row.id) {
                                            change.consume()
                                            pointerY += amount.y
                                            moveTo(pointerY)
                                        }
                                    },
                                    onDragEnd = {
                                        if (active == row.id) {
                                            active = null
                                            action.finish()
                                        }
                                    },
                                    onDragCancel = {
                                        if (active == row.id) {
                                            active = null
                                            action.cancelDrag()
                                        }
                                    },
                                )
                            }
                            .clickable(
                                enabled = !state.busy && !state.dragging && !state.editorLoading
                            ) {
                                action.edit(row.id)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).testTag("keyboard-settings-drag-" + row.id),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_menu),
                                stringResource(R.string.drag_to_reorder),
                            )
                        }
                        val unsafe =
                            remember(row.key) {
                                EditSafety.isTooLongForInline(row.key) ||
                                    EditSafety.isCombiningHeavy(row.key)
                            }
                        Text(
                            if (unsafe)
                                stringResource(R.string.large_text_placeholder, row.key.length)
                            else row.key,
                            Modifier.weight(1f),
                            maxLines = 2,
                        )
                        IconButton(
                            { action.delete(row.id) },
                            Modifier.testTag("keyboard-settings-delete-" + row.id),
                            enabled = !state.busy && !state.dragging && !state.editorLoading,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_outline_delete),
                                stringResource(R.string.delete),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    state.editor?.let { draft ->
        AlertDialog(
            onDismissRequest = actions.cancelEditor,
            title = { Text(stringResource(R.string.assists_key_config)) },
            text = {
                Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    AssistEditorField(
                        "key",
                        draft.key,
                        state.busy || state.editorLoading,
                        { actions.text(true, it) },
                        { actions.code(true) },
                    )
                    AssistEditorField(
                        "value",
                        draft.value,
                        state.busy || state.editorLoading,
                        { actions.text(false, it) },
                        { actions.code(false) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    actions.saveEditor,
                    Modifier.testTag("keyboard-settings-editor-save"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    actions.cancelEditor,
                    Modifier.testTag("keyboard-settings-editor-cancel"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.linePicker)
        AlertDialog(
            onDismissRequest = actions.cancelLines,
            title = { Text(stringResource(R.string.setting_show_line_number)) },
            text = {
                Column {
                    for (rows in 1..5) Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                state.selectedLines == rows,
                                !state.busy,
                                Role.RadioButton,
                            ) {
                                actions.chooseLines(rows)
                            }
                            .testTag("keyboard-settings-lines-$rows"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(state.selectedLines == rows, null)
                        Text(stringResource(R.string.show_line_number, rows))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    actions.saveLines,
                    Modifier.testTag("keyboard-settings-lines-save"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    actions.cancelLines,
                    Modifier.testTag("keyboard-settings-lines-cancel"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}

@Composable
private fun AssistEditorField(
    label: String,
    value: KeyboardAssistSettingsText,
    busy: Boolean,
    changed: (KeyboardAssistSettingsText) -> Unit,
    code: () -> Unit,
) {
    val unsafe =
        remember(value.text) {
            EditSafety.isTooLongForInline(value.text) || EditSafety.isCombiningHeavy(value.text)
        }
    if (unsafe)
        OutlinedCard(
            onClick = code,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("keyboard-settings-editor-$label"),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(label)
                Text(stringResource(R.string.large_text_placeholder, value.text.length))
            }
        }
    else {
        var local by
            remember(label) {
                mutableStateOf(TextFieldValue(value.text, TextRange(value.start, value.end)))
            }
        SideEffect {
            if (local.text != value.text || local.selection != TextRange(value.start, value.end))
                local = TextFieldValue(value.text, TextRange(value.start, value.end))
        }
        OutlinedTextField(
            local,
            {
                local = it
                changed(KeyboardAssistSettingsText(it.text, it.selection.start, it.selection.end))
            },
            Modifier.fillMaxWidth().testTag("keyboard-settings-editor-$label"),
            label = { Text(label) },
            enabled = !busy,
        )
    }
}
