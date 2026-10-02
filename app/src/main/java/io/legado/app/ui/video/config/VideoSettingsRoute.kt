package io.legado.app.ui.video.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VideoSettingsRoute(viewModel: VideoSettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VideoSettingsScreen(state, viewModel::setEnabled, viewModel::openSpeedPicker,
        viewModel::setSpeedDraft, viewModel::cancelSpeedPicker,
        { viewModel.confirmSpeed() }, { viewModel.confirmSpeed(default = true) }, modifier)
}
