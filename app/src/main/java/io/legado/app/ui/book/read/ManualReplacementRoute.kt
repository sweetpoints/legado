package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ManualReplacementRoute(
    model: ManualReplacementViewModel,
    canDeliver: () -> Boolean,
    selected: (List<Long>) -> Unit,
    close: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    val deliver by rememberUpdatedState(selected)
    val dismiss by rememberUpdatedState(close)
    val ready by rememberUpdatedState(canDeliver)
    LaunchedEffect(state.finished, state.confirmationPending, lifecycle) {
        if (!state.finished || lifecycle != Lifecycle.State.RESUMED || !ready())
            return@LaunchedEffect
        model.consumeConfirmation()?.let(deliver)
        dismiss()
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
