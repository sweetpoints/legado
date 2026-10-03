package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.ReadAloudSwitch
import io.legado.app.ui.components.SettingsCategoryHeader
import io.legado.app.ui.components.SettingsRow

@Composable
fun ReadAloudSettingsScreen(
    state: ReadAloudSettingsUiState,
    background: Color,
    onSwitch: (ReadAloudSwitch, Boolean) -> Unit,
    onStartPicker: () -> Unit,
    onStart: (String) -> Unit,
    onDismissStart: () -> Unit,
    onNavigate: (ReadAloudSettingsDestination) -> Unit,
    onRetryEngine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startNames = stringArrayResource(R.array.read_aloud_start_entries)
    val startValues = stringArrayResource(R.array.read_aloud_start_values)
    Surface(modifier.fillMaxWidth(), color = background) {
        LazyColumn(Modifier.fillMaxWidth().testTag("read-aloud-settings-list")) {
            item { SettingsCategoryHeader(stringResource(R.string.aloud_config)) }
            items(ReadAloudSwitch.entries.toList(), key = { it.key }) { setting ->
                val labels =
                    when (setting) {
                        ReadAloudSwitch.IgnoreAudioFocus ->
                            R.string.ignore_audio_focus_title to R.string.ignore_audio_focus_summary
                        ReadAloudSwitch.PauseDuringCalls ->
                            R.string.pause_read_aloud_while_phone_calls_title to
                                R.string.pause_read_aloud_while_phone_calls_summary
                        ReadAloudSwitch.WakeLock ->
                            R.string.read_aloud_wake_lock to R.string.read_aloud_wake_lock_summary
                        ReadAloudSwitch.MediaButtonNext ->
                            R.string.pref_media_button_per_next to
                                R.string.pref_media_button_per_next_summary
                        ReadAloudSwitch.ByPage ->
                            R.string.read_aloud_by_page to R.string.read_aloud_by_page_summary
                        ReadAloudSwitch.FollowManualPage ->
                            R.string.read_aloud_follow_manual_page to
                                R.string.read_aloud_follow_manual_page_summary
                        ReadAloudSwitch.StreamAudio ->
                            R.string.stream_read_aloud_audio to
                                R.string.stream_read_aloud_audio_summary
                    }
                val enabled =
                    setting != ReadAloudSwitch.PauseDuringCalls ||
                        state.preferences[ReadAloudSwitch.IgnoreAudioFocus]
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .testTag("read-aloud-switch-${setting.key}")
                        .toggleable(
                            state.preferences[setting],
                            enabled = enabled,
                            role = Role.Switch,
                        ) {
                            onSwitch(setting, it)
                        }
                        .alpha(if (enabled) 1f else .38f)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(labels.first),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(labels.second),
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        state.preferences[setting],
                        onCheckedChange = null,
                        enabled = enabled,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            item {
                SettingsRow(
                    stringResource(R.string.read_aloud_start),
                    startNames[startValues.indexOf(state.preferences.start).coerceAtLeast(0)],
                    onStartPicker,
                    Modifier.testTag("read-aloud-start"),
                )
            }
            item {
                SettingsRow(
                    stringResource(R.string.read_aloud_controls),
                    null,
                    { onNavigate(ReadAloudSettingsDestination.Controls) },
                    Modifier.testTag("read-aloud-controls"),
                )
            }
            item {
                SettingsRow(
                    stringResource(R.string.speak_engine),
                    state.engineName ?: stringResource(R.string.system_tts),
                    { onNavigate(ReadAloudSettingsDestination.Engine) },
                    Modifier.testTag("read-aloud-engine"),
                )
            }
            item {
                SettingsRow(
                    stringResource(R.string.sys_tts_config),
                    stringResource(R.string.sys_tts_config_summary),
                    { onNavigate(ReadAloudSettingsDestination.SystemTts) },
                    Modifier.testTag("read-aloud-system-tts"),
                )
            }
            state.error?.let { error ->
                item {
                    Text(
                        error,
                        Modifier.padding(16.dp).testTag("read-aloud-settings-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onRetryEngine) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
    if (state.showStartPicker) {
        AlertDialog(
            onDismissRequest = onDismissStart,
            title = { Text(stringResource(R.string.read_aloud_start)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    startValues.forEachIndexed { index, value ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("read-aloud-start-$value")
                                .selectable(
                                    state.preferences.start == value,
                                    role = Role.RadioButton,
                                ) {
                                    onStart(value)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(state.preferences.start == value, onClick = null)
                            Text(startNames[index], Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onDismissStart) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
