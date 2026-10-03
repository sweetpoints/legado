package io.legado.app.ui.file

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

@Composable
fun FileManagementRoute(
    model: FileManagementViewModel,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
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
                if (failedToken == navigation.token && current.error != null) return@collect
                try {
                    model
                        .consumeOpen(navigation.token) {
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                ready()
                        }
                        ?.let { open(it.uri) }
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
    val actions =
        remember(model) {
            FileManagementActions(
                model::query,
                model::navigate,
                model::click,
                model::delete,
                model::retry,
                model::back,
                model::close,
            )
        }
    BackHandler { if (state.canAct) model.back() else if (!state.busy) model.close() }
    FileManagementScreen(state, actions)
}
