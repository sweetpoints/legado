package io.legado.app.ui.autoTask

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

@Composable
fun AutoTaskEditorRoute(model: AutoTaskEditorViewModel, onEvent: (AutoTaskEditorEffect, String?) -> Unit,
    onClose: (Boolean) -> Unit, onError: (String) -> Unit, canHandle: () -> Boolean = { true }) {
    val state by model.state.collectAsStateWithLifecycle(); val owner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onEvent); val close by rememberUpdatedState(onClose)
    val ready by rememberUpdatedState(canHandle); val error by rememberUpdatedState(onError)
    var closed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (!ready() || value.loading || value.busy) return@collect
                if (value.finished && !closed) { closed = true; close(value.savedResult); return@collect }
                for (effect in value.effects) {
                    if (!ready()) break
                    try {
                        val payload = if (effect.kind == AutoTaskEditorEffectKind.Clipboard) model.copyText() else null
                        currentCoroutineContext().ensureActive()
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !ready()) break
                        model.consume(effect)
                        if (effect.kind in listOf(AutoTaskEditorEffectKind.Close, AutoTaskEditorEffectKind.SavedClose)) {
                            if (!closed) { closed = true; close(model.state.value.savedResult) }
                        } else deliver(effect, payload)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        currentCoroutineContext().ensureActive(); model.consume(effect)
                        if (effect.kind == AutoTaskEditorEffectKind.Editor) model.editorReturned(false, null, null, 0)
                        error(failure.localizedMessage ?: "Error")
                    }
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { runCatching { model.flush() } } }
        }
    }
    val actions = remember(model) { AutoTaskEditorActions(model::field, model::focus, model::enabled, model::cookieJar,
        model::save, model::requestExit, model::keepEditing, model::discard, model::openEditor, model::copy,
        model::requestPaste, model::help, model::retry, model::retryEditorResult, model::discardEditorResult) }
    BackHandler { if (state.exit) model.keepEditing() else model.requestExit() }
    AutoTaskEditorScreen(state, actions)
}
