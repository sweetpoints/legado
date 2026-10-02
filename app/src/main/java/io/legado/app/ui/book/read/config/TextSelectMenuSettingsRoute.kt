package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun TextSelectMenuSettingsRoute(viewModel: TextSelectMenuSettingsViewModel,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelGesture() } }
    TextSelectMenuSettingsScreen(state, viewModel::edit, onClose, modifier)
}
