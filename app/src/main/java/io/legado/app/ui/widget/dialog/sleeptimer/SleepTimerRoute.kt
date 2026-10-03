package io.legado.app.ui.widget.dialog.sleeptimer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun SleepTimerRoute(
    viewModel: SleepTimerViewModel,
    onSelection: (SleepTimerSelection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deliver by rememberUpdatedState(onSelection)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect {
                if (it.finished && !closed) {
                    closed = true
                    close()
                } else if (it.pending != null) viewModel.consumeSelection()?.let(deliver)
            }
        }
    }
    SleepTimerScreen(
        state,
        viewModel::selectPreset,
        viewModel::showCustom,
        viewModel::setInput,
        viewModel::confirmCustom,
        viewModel::turnOff,
        modifier,
    )
}
