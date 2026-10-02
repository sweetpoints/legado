package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun AutoReadRoute(
    viewModel: AutoReadViewModel,
    background: Color,
    foreground: Color,
    onUpdateTts: () -> Unit,
    onCatalog: () -> Unit,
    onMenu: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateTts by rememberUpdatedState(onUpdateTts)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect {
                if (it.ttsUpdate > 0) {
                    updateTts()
                    viewModel.ttsUpdated(it.ttsUpdate)
                }
            }
        }
    }
    AutoReadScreen(state, background, foreground, viewModel::changeSpeed,
        viewModel::finishChangingSpeed, onCatalog, onMenu, onStop, onSettings, modifier)
}
