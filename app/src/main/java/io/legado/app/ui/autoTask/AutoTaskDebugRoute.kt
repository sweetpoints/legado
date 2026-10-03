package io.legado.app.ui.autoTask

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
fun AutoTaskDebugRoute(
    viewModel: AutoTaskDebugViewModel,
    onBack: () -> Unit,
    onTaskMissing: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val back by rememberUpdatedState(onBack)
    val missing by rememberUpdatedState(onTaskMissing)
    var closed by remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.uiState.collect { current ->
                if (!closed && (current.closed || current.taskMissing)) {
                    closed = true
                    if (current.taskMissing) {
                        viewModel.close()
                        missing()
                    } else back()
                }
            }
        }
    }
    // Pausing never interrupts a JS run. Flush only the current log when the UI stops.
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { runCatching { viewModel.flush() } }
            }
        }
    }
    BackHandler { viewModel.close() }
    AutoTaskDebugScreen(
        state,
        viewModel::runDebug,
        viewModel::close,
        onRetry = viewModel::retryLoad,
    )
}
