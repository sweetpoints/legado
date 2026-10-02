package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PaddingSettingsRoute(viewModel: PaddingSettingsViewModel, background: Color, foreground: Color,
    onTrackingChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isTracking) { onTrackingChange(state.isTracking) }
    DisposableEffect(viewModel) { onDispose { viewModel.viewDestroyed(); onTrackingChange(false) } }
    PaddingSettingsScreen(state, background, foreground, viewModel::selectRegion,
        viewModel::drag, viewModel::finish, viewModel::startTracking, viewModel::stopTracking,
        viewModel::step, viewModel::setLock, viewModel::setShowLine, viewModel::askReset,
        viewModel::confirmReset, viewModel::cancelReset, modifier)
}
