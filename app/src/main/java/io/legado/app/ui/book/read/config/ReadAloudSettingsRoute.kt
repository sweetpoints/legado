package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReadAloudSettingsRoute(viewModel: ReadAloudSettingsViewModel, background: Color,
    onNavigate: (ReadAloudSettingsDestination) -> Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.startObserving()
        onPauseOrDispose { viewModel.stopObserving() }
    }
    LifecycleResumeEffect(state.navigation) {
        state.navigation?.let { if (onNavigate(it)) viewModel.navigated(it) }
        onPauseOrDispose { }
    }
    ReadAloudSettingsScreen(state, background, viewModel::setSwitch, viewModel::openStartPicker,
        viewModel::setStart, viewModel::dismissStartPicker, viewModel::navigate, viewModel::refreshEngine, modifier)
}
