package io.legado.app.ui.widget.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Composable
internal fun CodeDialogRoute(
    model: CodeDialogViewModel,
    manualEnabled: Boolean,
    canHandle: () -> Boolean,
    onEffect: (CodeDialogEffect) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val enabled by rememberUpdatedState(canHandle)
    val handle by rememberUpdatedState(onEffect)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler {
        if (!state.busy) {
            if (state.searchOpen) model.searchOpen(false) else model.close()
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { model.flushDraft() }
            }
        }
    }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (enabled()) {
                    if (value.finished && !closed) {
                        closed = true
                        close()
                    }
                    if (value.loaded && !value.finished)
                        value.effects.firstOrNull()?.let { effect ->
                            if (effect.action == CodeDialogAction.Editor && !value.editorPrepared)
                                model.prepareEditor()
                            else if (
                                effect.action == CodeDialogAction.Editor ||
                                    effect.action == CodeDialogAction.EditorSaved ||
                                    !value.busy
                            ) {
                                model.consume(effect.id)
                                handle(effect)
                            }
                        }
                }
            }
        }
    }
    CodeDialogScreen(
        state,
        model.editable,
        model.sourcePreview,
        manualEnabled,
        model::text,
        model::selection,
        model::preview,
        model::searchOpen,
        model::search,
        model::match,
        { model.action(it, manualEnabled) },
        model::close,
        model::load,
        modifier,
    )
}
