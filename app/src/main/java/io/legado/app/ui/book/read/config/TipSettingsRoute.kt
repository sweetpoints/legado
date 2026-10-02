package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TipSettingsRoute(viewModel: TipSettingsViewModel, onSelectFont: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TipSettingsScreen(state, viewModel::set, onSelectFont, viewModel::openSelector,
        viewModel::openTemplate, viewModel::select, viewModel::openColor,
        viewModel::editColor, viewModel::setColorChannel, viewModel::confirmColor,
        viewModel::editTemplate, viewModel::insertPlaceholder, viewModel::confirmTemplate,
        viewModel::dismissEditors, modifier)
}
