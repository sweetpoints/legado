package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun ThemeSettingsRoute(model: ThemeSettingsViewModel, ready: () -> Boolean,
    destination: (ThemeSettingsDestination) -> Unit, search: String? = null, searchFinished: () -> Unit = {}, searchEmpty: () -> Unit = {}, message: (String) -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle(); val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launch by rememberUpdatedState(destination); val available by rememberUpdatedState(ready); val toast by rememberUpdatedState(message)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (available() && value.downloaded) { model.clearMessage(); toast("设定成功") }
                value.event?.let { event ->
                    if (available() && !value.loading && !value.failed && model.consumeEvent(event.id)) launch(event.destination)
                }
            }
        }
    }
    ThemeSettingsScreen(state, ThemeSettingsActions(model::popup, model::boolean, model::destination, model::toggleNight, model::retry,
        model::dismissPopup, model::number, model::color, model::name, model::confirm, model::launcher, model::removeBackground),
        search = search, searchFinished = searchFinished, searchEmpty = searchEmpty)
}
