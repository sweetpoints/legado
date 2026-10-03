package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun ServerConfigRoute(model: ServerConfigViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { if (it.finished) close() }
        }
    }
    ServerConfigScreen(state, model::edit, model::save, model::close, model::load)
}
