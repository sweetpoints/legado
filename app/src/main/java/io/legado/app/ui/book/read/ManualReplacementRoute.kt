package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect

@Composable
internal fun ManualReplacementRoute(
    model: ManualReplacementViewModel,
    canDeliver: () -> Boolean,
    selected: (List<Long>) -> Unit,
    close: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deliver by rememberUpdatedState(selected)
    val dismiss by rememberUpdatedState(close)
    val ready by rememberUpdatedState(canDeliver)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (
                    !current.finished ||
                        lifecycle.currentState != Lifecycle.State.RESUMED ||
                        !ready()
                )
                    return@collect
                val completion = model.consumeCompletion() ?: return@collect
                try {
                    completion.selection?.let(deliver)
                } finally {
                    dismiss()
                }
            }
        }
    }
    ManualReplacementScreen(
        state,
        model::toggle,
        model::all,
        model::confirm,
        model::cancel,
        model::load,
        model::beginRange,
        model::moveRange,
        model::endRange,
        model::cancelRange,
    )
}
