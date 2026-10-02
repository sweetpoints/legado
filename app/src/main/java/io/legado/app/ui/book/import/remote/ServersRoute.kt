package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun ServersRoute(model: ServersViewModel, onAdd: () -> Unit, onEdit: (Long) -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(model) { model.start(); onPauseOrDispose { model.stop() } }
    LaunchedEffect(model, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        model.state.collect { if (it.finished) close() }
    } }
    ServersScreen(state, model::choose, onAdd, onEdit, model::requestDelete, model::delete,
        model::applySelection, model::useDefault, model::close, model::retry)
}
