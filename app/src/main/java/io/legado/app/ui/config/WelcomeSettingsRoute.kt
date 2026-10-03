package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun WelcomeSettingsRoute(model: WelcomeSettingsViewModel, ready: () -> Boolean, picker: (Boolean) -> Unit,
    search: String? = null, searchFinished: () -> Unit = {}, searchEmpty: () -> Unit = {}, message: (String) -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle(); val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready); val launch by rememberUpdatedState(picker); val toast by rememberUpdatedState(message)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (available()) {
                    value.picker?.let { if (model.consumePicker(it.id)) launch(it.night) }
                    if (value.message != null) model.consumeMessage()?.let { toast(it) }
                }
            }
        }
    }
    WelcomeSettingsScreen(state, WelcomeSettingsActions(model::milliseconds, model::step, model::boolean, model::imageAction,
        model::picker, model::removeImage, model::dismissPopup, model::retry), search, searchFinished, searchEmpty)
}
