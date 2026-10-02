package io.legado.app.ui.book.read.config

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException

@Composable
internal fun SpeakEngineRoute(viewModel: SpeakEngineViewModel, canHandle: () -> Boolean,
    onEffect: (SpeakEngineEffect) -> Unit, onCancel: () -> Unit, onCopy: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handle by rememberUpdatedState(onEffect)
    val canDeliver by rememberUpdatedState(canHandle)
    val close by rememberUpdatedState(onCancel)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (value.finished && value.pending.isEmpty()) close()
                value.pending.firstOrNull()?.let { effect ->
                    if (canDeliver()) {
                        try {
                            val prepared = if (effect.action == SpeakEngineAction.Export)
                                effect.copy(export = viewModel.exportData(effect.argument)) else effect
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canDeliver()
                                && viewModel.state.value.pending.any { it.id == effect.id }) {
                                viewModel.consume(effect.id)
                                handle(prepared)
                            }
                        }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { viewModel.consume(effect.id); viewModel.fail(error) }
                    }
                }
            }
        }
    }
    SpeakEngineScreen(state, viewModel::selectSystem, viewModel::selectHttp, viewModel::login,
        viewModel::edit, viewModel::requestDelete, viewModel::confirmDelete, viewModel::importDefault,
        viewModel::clearCache, viewModel::local, viewModel::openOnline, viewModel::input,
        viewModel::removeHistory, viewModel::confirmOnline, viewModel::export, viewModel::apply,
        onCancel, viewModel::passphrase, onCopy, viewModel::closeShare, modifier)
}
