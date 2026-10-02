package io.legado.app.ui.autoTask

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun AutoTaskImportRoute(model: AutoTaskImportViewModel, canDeliver: () -> Boolean,
    openEditor: (String, String) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val editor by rememberUpdatedState(openEditor)
    val dismiss by rememberUpdatedState(close)
    SideEffect { cancelable(!state.busy) }
    BackHandler { model.cancel() }
    LaunchedEffect(state.finished, state.openEditor, state.editor, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        if (state.finished) { dismiss(); return@LaunchedEffect }
        val request = state.editor ?: return@LaunchedEffect
        if (state.openEditor) state.items.firstOrNull { it.key == request.key }?.let {
            model.editorOpened(request.requestId); editor(it.json, request.requestId)
        }
    }
    AutoTaskImportScreen(state, model::toggle, model::edit, model::selectAll, model::clearSelection,
        model::confirm, model::cancel, model::load)
}
