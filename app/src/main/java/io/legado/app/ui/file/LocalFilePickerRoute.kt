package io.legado.app.ui.file

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException

@Composable internal fun LocalFilePickerRoute(model: LocalFilePickerViewModel, title: String,
    canDeliver: () -> Boolean, result: (String) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val callback by rememberUpdatedState(result); val dismiss by rememberUpdatedState(close)
    var closed by remember { mutableStateOf(false) }
    SideEffect { cancelable(!state.busy && state.result == null) }
    BackHandler { if (state.creating) model.cancelCreate() else model.cancel() }
    LaunchedEffect(lifecycle, state.result, state.finished) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        state.result?.let {
            model.delivered(it.id)
            try { callback(it.path) } catch (error: Throwable) { if (error is CancellationException) throw error }
            return@LaunchedEffect
        }
        if (state.finished && !closed) { closed = true; dismiss() }
    }
    LocalFilePickerScreen(state, title, model.config.root, model::click, { model.navigate(it) }, model::confirm, model::load,
        model::showCreate, model::folderName, model::create, model::cancelCreate, model::scroll, model::scrolled)
}
