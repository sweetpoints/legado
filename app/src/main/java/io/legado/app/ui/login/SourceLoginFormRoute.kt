package io.legado.app.ui.login

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun SourceLoginFormRoute(viewModel: SourceLoginFormViewModel, canHandle: () -> Boolean,
    onEffect: (SourceLoginFormEffect) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handle by rememberUpdatedState(onEffect); val close by rememberUpdatedState(onClose)
    val available by rememberUpdatedState(canHandle)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        viewModel.state.collect { value ->
            if (value.finished && value.pending.isEmpty()) close()
            value.pending.firstOrNull()?.let { event -> if (available()) {
                viewModel.consume(event.id)
                try { handle(event) } catch (error: Exception) { viewModel.fail(error) }
            } }
        }
    } }
    SourceLoginFormScreen(state, viewModel::edit, viewModel::choose, { row, long -> viewModel.action(row, long) },
        viewModel::toggle, viewModel::submit, viewModel::close, viewModel::showHeader, viewModel::deleteHeader,
        viewModel::copyHeader, viewModel::closeHeader, viewModel::requestClear, viewModel::clear, viewModel::log, modifier, onRetry = viewModel::retry)
}
