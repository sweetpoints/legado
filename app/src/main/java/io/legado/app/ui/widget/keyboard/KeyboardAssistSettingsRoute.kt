package io.legado.app.ui.widget.keyboard

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun KeyboardAssistSettingsRoute(model: KeyboardAssistSettingsViewModel, ready: () -> Boolean,
    close: () -> Unit, linesChanged: (Int) -> Unit, code: (Boolean) -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val currentReady by rememberUpdatedState(ready); val deliver by rememberUpdatedState(linesChanged)
    SideEffect { cancelable(!state.busy && !state.editorLoading) }
    DisposableEffect(model) { onDispose { model.cancelDrag() } }
    LaunchedEffect(state.pendingLines, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        state.pendingLines?.let { model.linesDelivered(it); deliver(it) }
    }
    KeyboardAssistSettingsScreen(state, KeyboardAssistSettingsActions(close = close,
        add = { model.clearError(); model.openEditor() }, edit = { model.clearError(); model.openEditor(it) }, delete = model::delete,
        begin = model::beginDrag, move = { id, target ->
            val rows = model.state.value.rows; model.move(rows.indexOfFirst { it.id == id }, rows.indexOfFirst { it.id == target })
        }, finish = model::finishDrag, cancelDrag = model::cancelDrag, step = model::moveAccessibly,
        lines = model::openLinePicker, chooseLines = model::chooseLines, cancelLines = model::cancelLines, saveLines = model::saveLines,
        text = model::editorText, saveEditor = model::saveEditor, cancelEditor = model::cancelEditor, code = { if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady()) code(it) },
        retry = { if (model.editorSession != null) model.loadEditor() else model.load() }, retryEditor = model::loadEditor, scroll = model::scroll))
}
