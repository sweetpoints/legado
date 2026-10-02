package io.legado.app.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.MarkdownImageRepository

@Composable internal fun UpdateDialogRoute(model: UpdateDialogViewModel, images: MarkdownImageRepository,
    canDeliver: () -> Boolean, deliver: (UpdateDialogEffect) -> Unit, link: (String) -> Unit,
    image: (String) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val action by rememberUpdatedState(deliver)
    val dismiss by rememberUpdatedState(close)
    SideEffect { cancelable(!state.busy) }
    BackHandler { model.cancel() }
    LaunchedEffect(state.effect, state.finished, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        if (state.finished) { dismiss(); return@LaunchedEffect }
        state.effect?.let { effect ->
            try { action(effect); model.delivered(effect.id) }
            catch (error: Exception) { model.failed(effect.id, error.localizedMessage ?: error.toString()) }
        }
    }
    UpdateDialogScreen(state, images, model::download, model::browser, model::ignore, model::cancel, model::load,
        { if (lifecycle == Lifecycle.State.RESUMED && ready()) link(it) },
        { if (lifecycle == Lifecycle.State.RESUMED && ready()) image(it) })
}
