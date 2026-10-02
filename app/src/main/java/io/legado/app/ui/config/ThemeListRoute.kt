package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

@Composable internal fun ThemeListRoute(model: ThemeListViewModel, canHandle: () -> Boolean,
    clipboard: () -> String?, share: (String) -> Unit, importFailed: () -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val getClip by rememberUpdatedState(clipboard)
    val onShare by rememberUpdatedState(share); val failed by rememberUpdatedState(importFailed)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val event = value.event ?: return@collect
                if (value.loading || !ready()) return@collect
                val payload = try {
                    if (event.kind == ThemeListEventKind.Share) model.sharePayload(requireNotNull(event.receipt)) else null
                } catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); model.consume(event); model.failed(error); return@collect }
                currentCoroutineContext().ensureActive()
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !ready() || model.state.value.event != event) return@collect
                model.consume(event)
                when (event.kind) {
                    ThemeListEventKind.Clipboard -> model.importText(getClip())
                    ThemeListEventKind.Share -> onShare(requireNotNull(payload))
                    ThemeListEventKind.ImportFailed -> failed()
                }
            }
        }
    }
    ThemeListScreen(state, model::apply, model::share, model::delete, model::confirmDelete,
        model::cancelDelete, model::importClipboard, model::reload, close, modifier)
}
