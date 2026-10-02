package io.legado.app.ui.main.my

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MyRoute(
    viewModel: MyViewModel,
    isMore: Boolean,
    onItemClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.startObserving()
        onPauseOrDispose { viewModel.stopObserving() }
    }
    MyScreen(
        state = state, isMore = isMore,
        onItemClick = onItemClick, onLongClick = onLongClick,
        onSwitchChange = viewModel::setSwitch, onThemeModeChange = viewModel::setThemeMode,
        onCustomize = viewModel::openCustomization, onCustomizationToggle = viewModel::toggleCustomization,
        onCustomizationConfirm = viewModel::confirmCustomization, onCustomizationDismiss = viewModel::dismissCustomization,
        onHelp = onHelp, onBack = onBack,
    )
}
