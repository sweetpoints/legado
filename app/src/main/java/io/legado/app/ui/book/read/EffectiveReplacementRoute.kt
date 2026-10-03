package io.legado.app.ui.book.read

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun EffectiveReplacementRoute(
    model: EffectiveReplacementViewModel,
    canHandle: () -> Boolean,
    onEdit: (Long) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val edit by rememberUpdatedState(onEdit)
    val refresh by rememberUpdatedState(onRefresh)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { if (state.conversionPicker) model.picker(false) else model.close() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (ready()) {
                    if (value.finished && !closed) {
                        if (model.consumeRefresh()) refresh()
                        closed = true
                        close()
                    } else if (!value.finished) model.consumeEdit()?.let(edit)
                }
            }
        }
    }
    EffectiveReplacementScreen(
        state,
        model::open,
        model::remove,
        model::chooseConversion,
        { model.picker(false) },
        model::load,
        model::close,
        modifier,
    )
}
