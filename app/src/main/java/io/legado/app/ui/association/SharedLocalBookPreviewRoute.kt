package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException

@Composable internal fun SharedLocalBookPreviewRoute(model: SharedLocalBookPreviewViewModel,
    canDeliver: () -> Boolean, currentUris: () -> Map<String, String>, selection: (List<String>) -> Unit,
    effect: (SharedLocalBookPreviewAction, List<String>) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val mapping by rememberUpdatedState(currentUris)
    val selected by rememberUpdatedState(selection); val handle by rememberUpdatedState(effect); val dismiss by rememberUpdatedState(close)
    var closed by remember { mutableStateOf(false) }
    SideEffect { cancelable(!state.busy) }
    BackHandler { model.cancel() }
    LaunchedEffect(state.loaded, state.loading, state.rows, state.selected) {
        if (state.loaded && !state.loading) {
            val uris = mapping()
            selected(state.rows.filter { it.id in state.selected && it.selectable }.mapNotNull { uris[it.id] })
        }
    }
    LaunchedEffect(lifecycle, state.effect, state.loaded, state.loading, state.importing, state.choosingDirectory, state.finished) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        if (state.finished && !closed) { closed = true; dismiss(); return@LaunchedEffect }
        if (!state.loaded || state.loading || state.importing || state.choosingDirectory) return@LaunchedEffect
        state.effect?.let { request ->
            // Reread the pipeline's current URI mapping before consuming a small pending ticket.
            val uris = mapping(); val resolved = request.selection.mapNotNull { uris[it] }
            val consumed = model.consume(request.id) ?: return@LaunchedEffect
            try { if (consumed.action == SharedLocalBookPreviewAction.Directory || resolved.isNotEmpty()) handle(consumed.action, resolved) }
            catch (error: Throwable) { if (error is CancellationException) throw error }
        }
    }
    SharedLocalBookPreviewScreen(state, model::toggle, model::selectAll, model::directory, model::confirm,
        model::cancel, model::load, model.scroll(), model::scrolled)
}
