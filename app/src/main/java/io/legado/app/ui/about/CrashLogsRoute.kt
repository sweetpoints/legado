package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.CrashLogContent

@Composable
fun CrashLogsRoute(
    viewModel: CrashLogsViewModel,
    onShowLog: (CrashLogContent) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val showLog by rememberUpdatedState(onShowLog)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { current ->
                current.openedLog?.let { log ->
                    if (showLog(log)) viewModel.consumeOpenedLog(log)
                }
            }
        }
    }
    CrashLogsScreen(state, viewModel::openLog, viewModel::clearLogs, viewModel::refresh, onClose, modifier)
}
