package io.legado.app.ui.book.changesource

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun WordCountFilterRoute(
    model: WordCountFilterViewModel,
    onChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val changed by rememberUpdatedState(onChanged)
    val close by rememberUpdatedState(onClose)
    var delivered by remember(model) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !delivered) {
                    delivered = true
                    model.consumeReload()?.let(changed)
                    close()
                }
            }
        }
    }
    WordCountFilterScreen(
        state,
        model::choose,
        model::minimum,
        model::maximum,
        model::confirm,
        model::close,
    )
}
