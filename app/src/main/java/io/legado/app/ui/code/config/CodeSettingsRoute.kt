package io.legado.app.ui.code.config

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.ui.widget.number.NumberPickerConfig
import io.legado.app.ui.widget.number.NumberPickerRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun CodeSettingsRoute(
    model: CodeSettingsViewModel,
    onPreview: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val preview by rememberUpdatedState(onPreview)
    LaunchedEffect(model, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state
                .filter { !it.loading && it.error == null }
                .map { it.font to it.autoComplete }
                .distinctUntilChanged()
                .collect { (font, auto) -> preview(font, auto) }
        }
    }
    CodeSettingsScreen(
        state,
        { model.showFontPicker(true) },
        model::setAutoComplete,
        model::toggleFlag,
        model::load,
        modifier,
    )
    if (state.fontPicker)
        Dialog(onDismissRequest = { model.showFontPicker(false) }) {
            val title = stringResource(R.string.font_scale)
            val config = remember {
                NumberPickerConfig(title = title, minimum = 9, maximum = 36, initial = state.font)
            }
            NumberPickerRoute(
                config,
                stringResource(R.string.btn_default_s),
                model::setFont,
                { model.setFont(16) },
                { model.showFontPicker(false) },
            )
        }
}
