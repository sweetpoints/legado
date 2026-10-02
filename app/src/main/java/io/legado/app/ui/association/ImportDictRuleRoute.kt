package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun ImportDictRuleRoute(viewModel: ImportDictRuleViewModel, canHandle: () -> Boolean,
    onCode: (ImportDictRuleCode) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val code by rememberUpdatedState(onCode)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { viewModel.cancel() }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (ready()) {
                    if (value.finished) close()
                    else value.code?.let { event -> viewModel.consumeCode(event.key); code(event) }
                }
            }
        }
    }
    ImportDictRuleScreen(state, viewModel::toggle, viewModel::toggleAll, viewModel::openCode,
        viewModel::confirm, viewModel::cancel, viewModel::load, modifier)
}
