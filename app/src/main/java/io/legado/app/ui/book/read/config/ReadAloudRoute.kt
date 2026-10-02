package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun ReadAloudRoute(viewModel: ReadAloudViewModel, background: Color, foreground: Color,
    canHandle: (ReadAloudControl) -> Boolean, onEffect: (ReadAloudEffect) -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handle by rememberUpdatedState(onEffect)
    val canDeliver by rememberUpdatedState(canHandle)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshRuntime()
        viewModel.reloadEngine()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { current ->
                if (current.finished) close()
                else current.pending.firstOrNull()?.let { effect ->
                    if (canDeliver(effect.control)) viewModel.consumeEffect(effect.id)?.let(handle)
                }
            }
        }
    }
    ReadAloudScreen(state, background, foreground, { viewModel.request(it) },
        viewModel::changeRate, viewModel::finishRate, viewModel::stepRate,
        viewModel::setFollowSystem, viewModel::changeTimer, viewModel::finishTimer,
        viewModel::saveDefaultTimer, modifier)
}
