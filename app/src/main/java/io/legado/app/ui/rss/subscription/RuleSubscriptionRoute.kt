package io.legado.app.ui.rss.subscription

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.RuleSubscriptionOpen
import kotlinx.coroutines.*

@Composable
fun RuleSubscriptionRoute(
    model: RuleSubscriptionViewModel,
    onClose: () -> Unit,
    onOpen: (RuleSubscriptionOpen) -> Unit,
    onError: (String) -> Unit,
    canHandle: () -> Boolean = { true },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val open by rememberUpdatedState(onOpen)
    val error by rememberUpdatedState(onError)
    val ready by rememberUpdatedState(canHandle)
    var closed by remember(model) { mutableStateOf(false) }
    var failedToken by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (!ready()) return@collect
                if (current.closed && !closed) {
                    closed = true
                    close()
                    return@collect
                }
                if (current.busy || !current.loaded) return@collect
                val navigation = current.navigation ?: return@collect
                if (failedToken == navigation.token && current.issue != null) return@collect
                try {
                    model
                        .consumeOpen(navigation.token) {
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                ready()
                        }
                        ?.let(open)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    failedToken = navigation.token
                    model.openFailure(failure)
                    error(failure.localizedMessage ?: "Error")
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { runCatching { model.flush() } }
            }
        }
    }
    DisposableEffect(model) { onDispose { model.cancelDrag() } }
    val actions =
        remember(model) {
            RuleSubscriptionActions(
                model::create,
                model::edit,
                model::delete,
                model::open,
                model::name,
                model::url,
                model::type,
                model::automatic,
                model::interval,
                model::silent,
                model::save,
                model::cancelEditor,
                model::retry,
                model::close,
                model::beginDrag,
                model::move,
                model::finishDrag,
                model::cancelDrag,
            )
        }
    BackHandler {
        if (state.editor != null) model.cancelEditor() else if (!state.busy) model.close()
    }
    RuleSubscriptionScreen(state, actions)
}
