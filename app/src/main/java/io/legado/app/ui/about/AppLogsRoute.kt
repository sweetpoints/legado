package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.AppLogDetail
import io.legado.app.data.repository.AppLogExport

@Composable
fun AppLogsRoute(
    viewModel: AppLogsViewModel,
    onShowLog: (AppLogDetail) -> Boolean,
    onShare: (AppLogExport) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val showLog by rememberUpdatedState(onShowLog)
    val share by rememberUpdatedState(onShare)
    LifecycleResumeEffect(viewModel) {
        viewModel.startObserving()
        onPauseOrDispose { viewModel.stopObserving() }
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { current ->
                current.openedLog?.let { if (showLog(it)) viewModel.consumeOpenedLog(it) }
                current.export?.let { if (share(it)) viewModel.consumeExport(it) }
            }
        }
    }
    AppLogsScreen(
        state = state,
        onOpenLog = viewModel::openLog,
        onRequestClear = viewModel::requestClear,
        onConfirmClear = viewModel::confirmClear,
        onDismissClear = viewModel::dismissClearConfirmation,
        onExport = viewModel::prepareExport,
        onRetry = viewModel::refresh,
        onClose = onClose,
        modifier = modifier,
    )
}
