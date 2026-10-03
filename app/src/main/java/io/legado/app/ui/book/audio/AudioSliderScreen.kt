package io.legado.app.ui.book.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun AudioSliderScreen(
    state: AudioSliderState,
    change: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        if (state.mode == AudioSliderMode.Timer)
            stringResource(R.string.timer_m, state.value.toInt())
        else "%.1fX".format(state.value)
    Surface(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text,
                Modifier.testTag("audio-slider-value"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                state.value,
                change,
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("audio-slider-control")
                    .semantics { contentDescription = text },
                valueRange = state.range,
                steps = state.steps,
            )
        }
    }
}
