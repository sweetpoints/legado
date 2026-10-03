package io.legado.app.ui.widget.dialog.variable

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
fun VariableRoute(
    viewModel: VariableViewModel,
    onSave: (VariableResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save by rememberUpdatedState(onSave)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect {
                if (it.finished && !closed) {
                    closed = true
                    close()
                } else if (it.saveRequested) viewModel.consumeSave()?.let(save)
            }
        }
    }
    VariableScreen(
        state,
        viewModel::setInput,
        viewModel::requestSave,
        {
            viewModel.cancel()
        },
        modifier,
    )
}
