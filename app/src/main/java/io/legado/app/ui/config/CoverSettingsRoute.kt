package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.model.cover.CoverSettingImage

@Composable
internal fun CoverSettingsRoute(
    model: CoverSettingsViewModel,
    ready: () -> Boolean,
    picker: (CoverSettingImage) -> Unit,
    destination: (CoverDestination) -> Unit,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready)
    val launch by rememberUpdatedState(picker)
    val navigate by rememberUpdatedState(destination)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (available()) {
                    value.picker?.let { if (model.consumePicker(it.id)) launch(it.key) }
                    value.navigation?.let {
                        if (model.consumeNavigation(it.id)) navigate(it.destination)
                    }
                }
            }
        }
    }
    CoverSettingsScreen(
        state,
        CoverSettingsActions(
            model::boolean,
            model::imageAction,
            model::picker,
            model::removeImage,
            model::destination,
            model::dismissPopup,
            model::retry,
        ),
        search,
        searchFinished,
        searchEmpty,
    )
}
