package io.legado.app.ui.book.read.config

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun HttpTtsEditRoute(
    viewModel: HttpTtsEditViewModel,
    canHandle: () -> Boolean,
    onEffect: (HttpTtsEditorEffect) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handle by rememberUpdatedState(onEffect)
    val available by rememberUpdatedState(canHandle)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (value.finished && value.pending.isEmpty()) close()
                value.pending.firstOrNull()?.let { event ->
                    if (available()) {
                        viewModel.consume(event.id)
                        try {
                            handle(event)
                        } catch (error: Exception) {
                            viewModel.fail(error)
                        }
                    }
                }
            }
        }
    }
    HttpTtsEditScreen(
        state,
        viewModel::edit,
        viewModel::focus,
        viewModel::cookie,
        viewModel::request,
        viewModel::showHeader,
        viewModel::deleteHeader,
        viewModel::closeHeader,
        viewModel::requestExit,
        viewModel::keepEditing,
        viewModel::discard,
        modifier,
        onRetry = viewModel::retryLoad,
    )
}
