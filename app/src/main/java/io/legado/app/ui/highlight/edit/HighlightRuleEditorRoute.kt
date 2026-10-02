package io.legado.app.ui.highlight.edit

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect

@Composable internal fun HighlightRuleEditorRoute(model: HighlightRuleEditorViewModel, canHandle: () -> Boolean,
    onEvent: (HighlightRuleEditorEvent) -> Unit, onColor: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val host by rememberUpdatedState(onEvent)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val event = value.event ?: return@collect
                if (value.loading || !ready()) return@collect
                model.consume(event); host(event)
            }
        }
    }
    HighlightRuleEditorScreen(state, model::change, model::openStyle, model::save, model::cancel, model::retry, modifier)
    state.color?.takeIf { !state.loading && !state.finished }?.let { color ->
        HighlightRuleColorPicker(color, { value -> onColor(color.channel, value); model.closeColor() }, model::closeColor)
    }
}
