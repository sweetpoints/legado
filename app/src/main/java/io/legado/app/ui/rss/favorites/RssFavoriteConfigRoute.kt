package io.legado.app.ui.rss.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun RssFavoriteConfigRoute(model: RssFavoriteConfigViewModel, canDeliver: () -> Boolean,
    update: (String?, String?) -> Unit, delete: () -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val save by rememberUpdatedState(update)
    val remove by rememberUpdatedState(delete); val dismiss by rememberUpdatedState(close)
    var closed by remember { mutableStateOf(false) }
    SideEffect { cancelable(!state.busy) }
    BackHandler { model.cancel() }
    LaunchedEffect(state.effect, state.finished, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        if (state.finished) {
            if (!closed) { closed = true; dismiss() }
            return@LaunchedEffect
        }
        model.consume()?.let { effect ->
            if (effect.action == io.legado.app.data.repository.RssFavoriteConfigAction.Save) save(effect.title, effect.group) else remove()
        }
    }
    RssFavoriteConfigScreen(state, model::title, model::group, model::confirm, model::delete, model::cancel, model::load)
}
