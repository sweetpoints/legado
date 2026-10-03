package io.legado.app.ui.video.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.VideoSetting
import kotlin.math.roundToInt

@Composable
fun VideoSettingsScreen(
    state: VideoSettingsUiState,
    onEnabledChange: (VideoSetting, Boolean) -> Unit,
    onOpenSpeed: () -> Unit,
    onSpeedDraft: (Int) -> Unit,
    onCancelSpeed: () -> Unit,
    onConfirmSpeed: () -> Unit,
    onDefaultSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.config_settings),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            VideoToggle(
                VideoSetting.AutoPlay,
                R.string.auto_play,
                state.settings.autoPlay,
                onEnabledChange,
            )
            VideoToggle(
                VideoSetting.DefaultFloatWindow,
                R.string.default_float_window,
                state.settings.defaultFloatWindow,
                onEnabledChange,
            )
            if (state.settings.autoPlay) {
                VideoToggle(
                    VideoSetting.StartFull,
                    R.string.start_full,
                    state.settings.startFull,
                    onEnabledChange,
                )
            }
            VideoToggle(
                VideoSetting.FullBottomProgress,
                R.string.full_bottom_progress,
                state.settings.fullBottomProgress,
                onEnabledChange,
            )
            Text(
                stringResource(R.string.press_speed_summary, state.settings.pressSpeed / 10f),
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("video-speed-open")
                    .clickable(role = Role.Button, onClick = onOpenSpeed)
                    .padding(vertical = 16.dp),
            )
        }
    }
    if (state.speedPickerVisible) {
        val decrease = stringResource(R.string.reduce)
        val increase = stringResource(R.string.plus)
        val speedDescription = stringResource(R.string.press_speed)
        AlertDialog(
            onDismissRequest = onCancelSpeed,
            title = { Text(stringResource(R.string.press_speed)) },
            text = {
                Column {
                    Text(stringResource(R.string.press_speed_summary, state.speedDraft / 10f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            { onSpeedDraft(state.speedDraft - 1) },
                            enabled = state.speedDraft > 5,
                            modifier =
                                Modifier.testTag("video-speed-minus").semantics {
                                    contentDescription = decrease
                                },
                        ) {
                            Text("−")
                        }
                        Slider(
                            state.speedDraft.toFloat(),
                            { onSpeedDraft(it.roundToInt()) },
                            Modifier.weight(1f).testTag("video-speed-slider").semantics {
                                contentDescription = speedDescription
                            },
                            valueRange = 5f..60f,
                            steps = 54,
                        )
                        TextButton(
                            { onSpeedDraft(state.speedDraft + 1) },
                            enabled = state.speedDraft < 60,
                            modifier =
                                Modifier.testTag("video-speed-plus").semantics {
                                    contentDescription = increase
                                },
                        ) {
                            Text("+")
                        }
                    }
                    TextButton(onDefaultSpeed, Modifier.testTag("video-speed-default")) {
                        Text(stringResource(R.string.btn_default_s))
                    }
                }
            },
            confirmButton = {
                TextButton(onConfirmSpeed, Modifier.testTag("video-speed-confirm")) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onCancelSpeed, Modifier.testTag("video-speed-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun VideoToggle(
    setting: VideoSetting,
    label: Int,
    checked: Boolean,
    onChange: (VideoSetting, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("video-setting-${setting.name}")
            .toggleable(checked, role = Role.Checkbox) { onChange(setting, it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null)
    }
}
