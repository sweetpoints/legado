package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun ImportHighlightRuleRoute(viewModel: ImportHighlightRuleViewModel, canHandle: () -> Boolean,
    onImported: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val imported by rememberUpdatedState(onImported)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { viewModel.cancel() }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (ready() && value.finished) {
                    if (value.refreshPending) {
                        // Consume before touching the reader; lifecycle recreation cannot replay delivery.
                        viewModel.consumeRefresh()
                        imported()
                    } else close()
                }
            }
        }
    }
    ImportHighlightRuleScreen(state, viewModel::toggle, viewModel::toggleAll, viewModel::confirm,
        viewModel::cancel, viewModel::load, modifier)
}
