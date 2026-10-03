package io.legado.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun SourceCheckSettingsRoute(
    model: SourceCheckSettingsViewModel,
    ready: () -> Boolean,
    close: () -> Unit,
    cancelable: (Boolean) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val currentReady by rememberUpdatedState(ready)
    val dismiss by rememberUpdatedState(close)
    SideEffect { cancelable(!state.saving) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (value.finished && currentReady()) {
                    model.delivered()
                    dismiss()
                }
            }
        }
    }
    BackHandler { if (!model.state.value.saving) dismiss() }
    SourceCheckSettingsScreen(
        state,
        model::seconds,
        model::toggle,
        model::save,
        { if (!model.state.value.saving) dismiss() },
        model::load,
    )
}
