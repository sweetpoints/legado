package io.legado.app.ui.book.audio.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable internal fun AudioSkipCreditsScreen(state: AudioSkipCreditsState, scope: (Boolean) -> Unit,
    opening: (Int) -> Unit, closing: (Int) -> Unit, retry: () -> Unit, close: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(16.dp).testTag("audio-skip-screen")) {
            Text(stringResource(R.string.skip_book_credits), style = MaterialTheme.typography.titleLarge)
            if (state.loading || state.saving || state.scopeLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("audio-skip-progress"))
            state.draft?.let { draft ->
                Row(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEach { global ->
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(draft.useGlobal == global,
                            enabled = !state.saving, role = Role.RadioButton, onClick = { scope(global) })
                            .testTag(if (global) "audio-skip-global" else "audio-skip-book"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(draft.useGlobal == global, null)
                            Text(stringResource(if (global) R.string.audio_skip_scope_global else R.string.audio_skip_scope_book))
                        }
                    }
                }
                AudioSkipChannel(stringResource(R.string.audio_skip_opening_seconds), "opening", draft.opening, !state.saving, opening)
                AudioSkipChannel(stringResource(R.string.audio_skip_closing_seconds), "closing", draft.closing, !state.saving, closing)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("audio-skip-error"))
                TextButton(retry, Modifier.testTag("audio-skip-retry"), enabled = !state.saving && !state.loading) { Text(stringResource(R.string.retry)) } }
            TextButton(close, Modifier.align(Alignment.End).testTag("audio-skip-close"), enabled = !state.saving) { Text(stringResource(R.string.close)) }
        }
    }
}
@Composable private fun AudioSkipChannel(title: String, tag: String, value: Int, enabled: Boolean, commit: (Int) -> Unit) {
    var position by rememberSaveable(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f)); Text(position.roundToInt().toString(), Modifier.testTag("audio-skip-$tag-value"))
            TextButton({ position = (position - 1).coerceAtLeast(0f); commit(position.roundToInt()) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("audio-skip-$tag-minus"), enabled = enabled && position > 0) { Text("−") }
            TextButton({ position = (position + 1).coerceAtMost(180f); commit(position.roundToInt()) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("audio-skip-$tag-plus"), enabled = enabled && position < 180) { Text("+") }
        }
        Slider(position, { position = it }, Modifier.fillMaxWidth().testTag("audio-skip-$tag-slider"), enabled = enabled,
            valueRange = 0f..180f, steps = 179, onValueChangeFinished = { commit(position.roundToInt()) })
    }
}
