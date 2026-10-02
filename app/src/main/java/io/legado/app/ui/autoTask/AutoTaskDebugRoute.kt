package io.legado.app.ui.autoTask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AutoTaskDebugRoute(
    viewModel: AutoTaskDebugViewModel,
    onBack: () -> Unit,
    onTaskMissing: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.taskMissing) { if (state.taskMissing) onTaskMissing() }
    AutoTaskDebugScreen(state, onRunAgain = viewModel::runDebug, onBack = onBack)
}
