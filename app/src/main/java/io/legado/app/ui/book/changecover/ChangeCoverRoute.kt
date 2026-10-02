package io.legado.app.ui.book.changecover

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover
import kotlinx.coroutines.flow.collect

@Composable internal fun ChangeCoverRoute(model: ChangeCoverComposeViewModel, canHandle: () -> Boolean,
    onSelected: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    cover: @Composable (CoverRequest, Modifier) -> Unit = { request, size -> ComposeCover(request, size) }) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val host by rememberUpdatedState(onSelected)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val selected = value.selected ?: return@collect
                if (value.loading || !ready()) return@collect
                model.consume(selected); host(selected)
            }
        }
    }
    ChangeCoverScreen(state, model::startStop, model::retry, model::select, { model.stop(); onClose() }, modifier, cover)
}
