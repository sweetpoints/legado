package io.legado.app.ui.highlight

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

@Composable internal fun HighlightGroupRoute(model: HighlightGroupViewModel, canHandle: () -> Boolean,
    refreshReader: () -> Unit, close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val refresh by rememberUpdatedState(refreshReader)
    LaunchedEffect(model, lifecycle) {
        model.pauseObservation()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.observe()
            try { awaitCancellation() } finally { model.pauseObservation() }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (value.refresh && ready()) { model.consumeRefresh(); refresh() }
            }
        }
    }
    HighlightGroupScreen(state, model::rename, model::delete, model::name, model::confirmRename,
        model::cancel, model::confirmDelete, model::chooseMove, model::move, model::retry, close)
}
