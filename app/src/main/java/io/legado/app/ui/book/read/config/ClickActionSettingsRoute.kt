package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ClickActionSettingsRoute(viewModel: ClickActionSettingsViewModel,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.startObserving()
        onPauseOrDispose { viewModel.stopObserving() }
    }
    ClickActionSettingsScreen(state, viewModel::selectRegion, viewModel::selectAction, viewModel::dismissPicker, onClose, modifier)
}
