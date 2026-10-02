package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun TextActionMenuRoute(viewModel: TextActionMenuViewModel, canHandle: () -> Boolean,
    onEvent: (TextActionEvent) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val host by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (!ready()) return@collect
                val event = value.events.firstOrNull() ?: return@collect
                viewModel.consume(event.id); host(event)
            }
        }
    }
    TextActionMenuScreen(state, viewModel::invoke, viewModel::longPress, viewModel::toggleMore, viewModel::edit)
}
