package io.legado.app.ui.widget.number

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Composable
fun NumberPickerRoute(
    config: NumberPickerConfig,
    customLabel: String?,
    onConfirm: (Int) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by
        rememberSaveable(config) {
            mutableIntStateOf(config.initial.coerceIn(config.minimum, config.maximum))
        }
    var input by rememberSaveable(config) { mutableStateOf(config.label(selected)) }
    var finished by rememberSaveable(config) { mutableStateOf(false) }
    LaunchedEffect(finished) { if (finished) onDismiss() }
    NumberPickerScreen(
        config,
        selected,
        input,
        finished,
        customLabel,
        { value ->
            if (!finished && selected != value) {
                selected = value
                input = config.label(value)
            }
        },
        { if (!finished) input = it },
        {
            if (!finished) {
                finished = true
                onConfirm(config.fromText(input, selected))
            }
        },
        {
            if (!finished) {
                finished = true
                onCustom()
            }
        },
        { if (!finished) finished = true },
    )
}
