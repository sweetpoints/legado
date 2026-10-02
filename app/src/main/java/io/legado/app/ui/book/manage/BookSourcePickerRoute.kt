package io.legado.app.ui.book.manage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect

@Composable internal fun BookSourcePickerRoute(model: BookSourcePickerViewModel,
    onSource: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val source by rememberUpdatedState(onSource)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { if (!state.busy) { if (state.delayOpen) model.closeDelay() else model.cancel() } }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (current.finished && !closed) {
                    closed = true
                    try { model.consumeSource()?.let(source) } finally { close() }
                }
            }
        }
    }
    BookSourcePickerScreen(state, model::search, model::select, model::cancel, model::openDelay,
        model::delayDraft, model::stepDelay, model::saveDelay, model::closeDelay, model::retry, modifier)
}
