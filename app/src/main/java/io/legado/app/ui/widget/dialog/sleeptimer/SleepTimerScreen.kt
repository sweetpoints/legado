package io.legado.app.ui.widget.dialog.sleeptimer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.SleepTimerMode

@Composable
fun SleepTimerScreen(
    state: SleepTimerUiState,
    onPreset: (SleepTimerMode, Int) -> Unit,
    onCustom: (SleepTimerMode) -> Unit,
    onInput: (String) -> Unit,
    onConfirm: () -> Unit,
    onOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.customMode) {
        if (state.customMode != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val status =
                    when {
                        state.chapter > 0 ->
                            stringResource(
                                if (state.useEpisodes) R.string.audio_stop_chapters
                                else R.string.sleep_timer_chapters,
                                state.chapter,
                            )
                        state.minute > 0 ->
                            stringResource(R.string.sleep_timer_status_time, state.minute)
                        else -> stringResource(R.string.sleep_timer_status_none)
                    }
                Text(status, Modifier.weight(1f).testTag("sleep-status"))
                IconButton(
                    onOff,
                    enabled = state.isActive && !state.isBusy,
                    modifier = Modifier.testTag("sleep-off"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_close),
                        stringResource(R.string.sleep_timer_off),
                    )
                }
            }
            Text(
                stringResource(R.string.sleep_timer_by_time),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Presets(SleepTimerMode.Minutes, SLEEP_MINUTE_PRESETS, state, onPreset)
            FilterChip(
                state.customMode == SleepTimerMode.Minutes ||
                    (state.customMode == null &&
                        state.chapter == 0 &&
                        state.minute > 0 &&
                        state.minute !in SLEEP_MINUTE_PRESETS),
                {
                    onCustom(SleepTimerMode.Minutes)
                    if (state.customMode == SleepTimerMode.Minutes) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                },
                { Text(stringResource(R.string.sleep_timer_custom)) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("sleep-custom-minutes"),
            )
            Text(
                stringResource(
                    if (state.useEpisodes) R.string.sleep_timer_by_episode
                    else R.string.sleep_timer_by_chapter
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Presets(SleepTimerMode.Chapters, SLEEP_CHAPTER_PRESETS, state, onPreset)
            FilterChip(
                state.customMode == SleepTimerMode.Chapters ||
                    (state.customMode == null &&
                        state.chapter > 0 &&
                        state.chapter !in SLEEP_CHAPTER_PRESETS),
                {
                    onCustom(SleepTimerMode.Chapters)
                    if (state.customMode == SleepTimerMode.Chapters) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                },
                { Text(stringResource(R.string.sleep_timer_custom)) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("sleep-custom-chapters"),
            )
            if (state.customMode != null) {
                OutlinedTextField(
                    state.input,
                    onInput,
                    enabled = !state.isBusy,
                    modifier =
                        Modifier.fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("sleep-custom-input"),
                    singleLine = true,
                    isError = state.showValidation,
                    label = {
                        Text(
                            stringResource(
                                when {
                                    state.customMode == SleepTimerMode.Minutes ->
                                        R.string.sleep_timer_minute_hint
                                    state.useEpisodes -> R.string.sleep_timer_episode_hint
                                    else -> R.string.sleep_timer_chapter_hint
                                }
                            )
                        )
                    },
                    supportingText =
                        if (state.showValidation)
                            ({
                                Text(
                                    stringResource(R.string.sleep_timer_range_hint, state.maxCustom)
                                )
                            })
                        else null,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                )
                TextButton(
                    onConfirm,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("sleep-custom-confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@Composable
private fun Presets(
    mode: SleepTimerMode,
    presets: List<Int>,
    state: SleepTimerUiState,
    onPreset: (SleepTimerMode, Int) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { value ->
            val selected =
                state.customMode == null &&
                    if (mode == SleepTimerMode.Minutes) state.chapter == 0 && state.minute == value
                    else state.chapter == value
            FilterChip(
                selected,
                { onPreset(mode, value) },
                {
                    Text(
                        stringResource(
                            if (mode == SleepTimerMode.Minutes) R.string.sleep_timer_minute_short
                            else if (state.useEpisodes) R.string.sleep_timer_episode_short
                            else R.string.sleep_timer_chapter_short,
                            value,
                        )
                    )
                },
                enabled = !state.isBusy,
                modifier = Modifier.testTag("sleep-${mode.name.lowercase()}-$value"),
            )
        }
    }
}
