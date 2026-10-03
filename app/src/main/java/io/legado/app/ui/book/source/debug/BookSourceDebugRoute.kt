package io.legado.app.ui.book.source.debug

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

@Composable
fun BookSourceDebugRoute(
    model: BookSourceDebugViewModel,
    onClose: () -> Unit,
    onHtml: (String?) -> Unit,
    onError: (String) -> Unit,
    onScan: () -> Unit,
    onInstructions: () -> Unit,
    style: BookSourceDebugStyle = BookSourceDebugStyle(),
    canHandle: () -> Boolean = { true },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val html by rememberUpdatedState(onHtml)
    val error by rememberUpdatedState(onError)
    val scan by rememberUpdatedState(onScan)
    val instructions by rememberUpdatedState(onInstructions)
    val ready by rememberUpdatedState(canHandle)
    var closed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (ready() && !closed && !value.loading && (value.missing || value.closed)) {
                    closed = true
                    model.close()
                    close()
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
        remember(model, owner) {
            BookSourceDebugActions(
                model::query,
                model::help,
                model::run,
                model::sort,
                { stage ->
                    if (
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
                    ) {
                        try {
                            html(model.html(stage))
                        } catch (failure: Exception) {
                            error(failure.localizedMessage ?: "Error")
                        }
                    }
                },
                {
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready())
                        scan()
                },
                model::refreshExplore,
                {
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready())
                        instructions()
                },
                model::prefix,
                model::detail,
                model::retry,
                model::close,
            )
        }
    BackHandler { model.close() }
    BookSourceDebugScreen(state, actions, style)
}
