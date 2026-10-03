package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun CoverRuleRoute(
    viewModel: CoverRuleViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { if (it.finished) close() }
        }
    }
    CoverRuleScreen(
        state,
        viewModel::setEnabled,
        viewModel::setSearchUrl,
        viewModel::setCoverRule,
        viewModel::save,
        viewModel::delete,
        viewModel::cancel,
        viewModel::load,
        modifier,
    )
}
