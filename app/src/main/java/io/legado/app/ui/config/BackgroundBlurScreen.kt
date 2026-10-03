package io.legado.app.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
fun BackgroundBlurScreen(state: BackgroundBlurState, onRadius: (Int) -> Unit, onSave: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.background_image_blurring), style = MaterialTheme.typography.titleLarge)
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.radius.toString(), Modifier.testTag("blur-radius"))
        Slider(state.radius.toFloat(), { onRadius(it.roundToInt()) }, valueRange = 0f..25f, steps = 24,
            enabled = !state.loading && !state.saving && !state.finished && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("blur-slider"))
        Text(stringResource(R.string.background_image_hint))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onRetry, enabled = !state.saving, modifier = Modifier.testTag("blur-retry")) { Text(stringResource(R.string.retry)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onCancel, enabled = !state.saving, modifier = Modifier.testTag("blur-cancel")) { Text(stringResource(R.string.cancel)) }
            TextButton(onSave, enabled = !state.loading && !state.saving && !state.finished && state.error == null,
                modifier = Modifier.testTag("blur-save")) { Text(stringResource(R.string.ok)) }
        }
    }
}
