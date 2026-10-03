package io.legado.app.ui.rss.source.manage

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.RssSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun RssSourceManagementRoute(
    model: RssSourceManagementViewModel,
    ready: () -> Boolean,
    back: () -> Unit,
    native: (RssSourceManagementNative, RssSource?) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val deliver by rememberUpdatedState(native)
    LaunchedEffect(state.pending, state.loaded, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val pending = state.pending ?: return@LaunchedEffect
        try {
            val request = model.native(pending.nonce) ?: return@LaunchedEffect
            val source =
                if (pending.action == RssSourceManagementAction.Edit)
                    request.sourceId?.let { model.source(it) }
                else null
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            if (model.delivered(pending.nonce)) deliver(request, source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            model.delivered(pending.nonce)
            model.failed(error.localizedMessage ?: error.javaClass.simpleName)
        }
    }
    val currentBack by rememberUpdatedState(back)
    val actions =
        remember(model) {
            RssSourceManagementActions(
                { currentBack() },
                model::query,
                model::selected,
                model::selectAll,
                model::invertSelection,
                model::selectInterval,
                model::enabled,
                model::edge,
                model::dialog,
                model::draft,
                model::cancelDialog,
                model::confirmDialog,
                model::defaults,
                { action, source -> model.effect(action, source) },
                model::forgetImport,
                model::passphrase,
                model::copyFeedback,
                model::retry,
                model::beginSelection,
                model::selectionRange,
                model::finishSelection,
                model::beginDrag,
                model::dragTo,
                model::finishDrag,
                model::cancelGesture,
                model::scroll,
            )
        }
    RssSourceManagementScreen(state, actions)
}
