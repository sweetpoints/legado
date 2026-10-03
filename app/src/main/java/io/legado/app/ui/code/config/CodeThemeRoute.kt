package io.legado.app.ui.code.config

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun CodeThemeRoute(
    model: CodeThemeViewModel,
    onPreview: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val preview by rememberUpdatedState(onPreview)
    LaunchedEffect(model, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state
                .filter { !it.loading && it.error == null }
                .map { it.selected }
                .distinctUntilChanged()
                .collect { preview(it) }
        }
    }
    CodeThemeScreen(state, model::setAutomatic, model::select, model::load, modifier)
}
