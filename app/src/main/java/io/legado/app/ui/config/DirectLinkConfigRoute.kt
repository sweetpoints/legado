package io.legado.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable fun DirectLinkConfigRoute(model: DirectLinkConfigViewModel, ready: () -> Boolean, close: () -> Unit,
    cancelable: (Boolean) -> Unit, clipboard: () -> String?, copy: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle(); val owner = LocalLifecycleOwner.current
    val currentReady by rememberUpdatedState(ready); val finish by rememberUpdatedState(close)
    val currentClipboard by rememberUpdatedState(clipboard); val currentCopy by rememberUpdatedState(copy)
    var delivered by remember(model) { mutableStateOf(false) }
    SideEffect { cancelable(!state.saving) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value -> if (value.finished && !delivered && currentReady()) {
                delivered = true; model.release(); finish()
            } }
        }
    }
    fun native(action: () -> Unit) { if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady()) action() }
    BackHandler { model.close() }
    DirectLinkConfigScreen(state, DirectLinkConfigActions(model::edit, model::preset,
        { native { model.copy()?.let(currentCopy) } }, { native { model.paste(currentClipboard()) } }, model::test, model::cancelTest,
        model::save, model::close, model::retry, model::clearResult,
        { native { model.state.value.session?.result?.let { currentCopy(it); model.clearResult() } } }))
}
