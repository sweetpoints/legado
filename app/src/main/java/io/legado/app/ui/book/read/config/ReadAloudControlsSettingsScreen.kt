package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.ReadAloudControlsNumber
import io.legado.app.data.preferences.ReadAloudControlsToggle
import io.legado.app.ui.components.SettingsCategoryHeader
import io.legado.app.ui.components.SettingsRow
import kotlin.math.roundToInt

@Composable
fun ReadAloudControlsSettingsScreen(
    state: ReadAloudControlsSettingsUiState,
    background: Color,
    onToggle: (ReadAloudControlsToggle, Boolean) -> Unit,
    onNumber: (ReadAloudControlsNumber, Int) -> Unit,
    onFinish: (ReadAloudControlsNumber) -> Unit,
    onStep: (ReadAloudControlsNumber, Int) -> Unit,
    onAction: (ReadAloudControlsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = background) {
        LazyColumn(Modifier.fillMaxSize().testTag("aloud-controls-settings-list")) {
            item { SettingsCategoryHeader(stringResource(R.string.read_aloud_controls)) }
            items(ReadAloudControlsToggle.entries.toList(), key = { it.key }) { setting ->
                val title =
                    stringResource(
                        when (setting) {
                            ReadAloudControlsToggle.Realtime ->
                                R.string.read_aloud_controls_realtime
                            ReadAloudControlsToggle.Pause -> R.string.read_aloud_controls_pause
                            ReadAloudControlsToggle.Position ->
                                R.string.read_aloud_controls_position
                            ReadAloudControlsToggle.AutoHide ->
                                R.string.read_aloud_controls_auto_hide
                            ReadAloudControlsToggle.Drag -> R.string.read_aloud_controls_drag
                            ReadAloudControlsToggle.Dock -> R.string.read_aloud_controls_dock
                        }
                    )
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .testTag("aloud-controls-switch-${setting.key}")
                        .toggleable(state.settings[setting], role = Role.Switch) {
                            onToggle(setting, it)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        state.settings[setting],
                        onCheckedChange = null,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            items(ReadAloudControlsNumber.entries.toList(), key = { it.key }) { setting ->
                val labels =
                    when (setting) {
                        ReadAloudControlsNumber.Width ->
                            R.string.read_aloud_controls_size to
                                R.string.read_aloud_controls_size_summary
                        ReadAloudControlsNumber.Opacity ->
                            R.string.read_aloud_controls_opacity to
                                R.string.read_aloud_controls_opacity_summary
                        ReadAloudControlsNumber.Threshold ->
                            R.string.read_aloud_controls_threshold to
                                R.string.read_aloud_controls_threshold_summary
                    }
                val title = stringResource(labels.first)
                val plus = "$title ${stringResource(R.string.plus)}"
                val minus = "$title ${stringResource(R.string.reduce)}"
                val value = state.settings[setting]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row {
                        Text(title, Modifier.weight(1f))
                        Text(
                            value.toString(),
                            Modifier.testTag("aloud-controls-value-${setting.name}"),
                        )
                    }
                    Text(
                        stringResource(labels.second),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            { onStep(setting, -1) },
                            Modifier.testTag("aloud-controls-minus-${setting.name}"),
                            enabled = value > setting.minimum,
                        ) {
                            Icon(painterResource(R.drawable.ic_reduce), minus)
                        }
                        Slider(
                            value.toFloat(),
                            { onNumber(setting, it.roundToInt()) },
                            Modifier.weight(1f)
                                .testTag("aloud-controls-slider-${setting.name}")
                                .semantics { contentDescription = title },
                            valueRange = setting.minimum.toFloat()..setting.maximum.toFloat(),
                            steps = setting.maximum - setting.minimum - 1,
                            onValueChangeFinished = { onFinish(setting) },
                        )
                        IconButton(
                            { onStep(setting, 1) },
                            Modifier.testTag("aloud-controls-plus-${setting.name}"),
                            enabled = value < setting.maximum,
                        ) {
                            Icon(painterResource(R.drawable.ic_add), plus)
                        }
                    }
                }
            }
            item {
                SettingsRow(
                    stringResource(R.string.read_aloud_controls_reveal),
                    stringResource(R.string.read_aloud_controls_reveal_summary),
                    { onAction(ReadAloudControlsAction.Reveal) },
                    Modifier.testTag("aloud-controls-reveal"),
                )
            }
            item {
                SettingsRow(
                    stringResource(R.string.read_aloud_controls_reset),
                    stringResource(R.string.read_aloud_controls_reset_summary),
                    { onAction(ReadAloudControlsAction.ResetPosition) },
                    Modifier.testTag("aloud-controls-reset-position"),
                )
            }
        }
    }
}
