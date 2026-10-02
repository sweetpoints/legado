package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReadAloudControlsSettingsRoute(viewModel: ReadAloudControlsSettingsViewModel, background: Color,
    onAction: (ReadAloudControlsAction) -> Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.startObserving()
        onPauseOrDispose { viewModel.stopObserving() }
    }
    LifecycleResumeEffect(state.action) {
        state.action?.let { if (onAction(it)) viewModel.actionHandled(it) }
        onPauseOrDispose { }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    ReadAloudControlsSettingsScreen(state, background, viewModel::setToggle, viewModel::drag, viewModel::finish,
        viewModel::step, viewModel::requestAction, modifier)
}
