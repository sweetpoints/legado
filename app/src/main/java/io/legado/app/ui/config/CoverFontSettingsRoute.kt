package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun CoverFontSettingsRoute(
    model: CoverFontSettingsViewModel,
    ready: () -> Boolean,
    font: () -> Unit,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready)
    val launch by rememberUpdatedState(font)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                value.fontEvent?.let { if (available() && model.consumeFontEvent(it)) launch() }
            }
        }
    }
    CoverFontSettingsScreen(
        state,
        CoverFontSettingsActions(
            model::boolean,
            model::edit,
            model::number,
            model::confirm,
            model::fontPicker,
            model::dismiss,
            model::retry,
        ),
        search,
        searchFinished,
        searchEmpty,
    )
}
