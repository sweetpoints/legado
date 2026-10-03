package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.data.preferences.MoreReaderSetting
import kotlin.math.roundToInt

@Composable
fun MoreReaderSettingsScreen(
    state: MoreReaderSettingsUiState,
    visibleSettings: List<MoreReaderSetting>,
    background: Color,
    slopSummary: String,
    bookmarkSummary: String,
    onToggle: (MoreReaderSetting.Toggle, Boolean) -> Unit,
    onChoice: (MoreReaderSetting.Choice, String) -> Unit,
    onSeekBar: (MoreReaderSetting.SeekBar, Int) -> Unit,
    onAction: (MoreReaderSetting.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = background) {
        LazyColumn(Modifier.fillMaxSize().testTag("more-reader-settings-list")) {
            items(visibleSettings, key = MoreReaderSetting::key) { setting ->
                when (setting) {
                    is MoreReaderSetting.Toggle -> ToggleSettingRow(setting, state.values, onToggle)
                    is MoreReaderSetting.Choice -> ChoiceSettingRow(setting, state.values, onChoice)
                    is MoreReaderSetting.SeekBar ->
                        SeekBarSettingRow(setting, state.values, onSeekBar)
                    is MoreReaderSetting.Action ->
                        ActionSettingRow(
                            setting,
                            when (setting.key) {
                                "pageTouchSlop" -> slopSummary
                                "pullBookmarkDistance" -> bookmarkSummary
                                else -> null
                            },
                            onAction,
                        )
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    setting: MoreReaderSetting.Toggle,
    values: Map<String, String>,
    onToggle: (MoreReaderSetting.Toggle, Boolean) -> Unit,
) {
    val checked = values[setting.key]?.toBooleanStrictOrNull() ?: setting.defaultValue
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("more-reader-setting-${setting.key}")
            .toggleable(checked, role = Role.Switch) { onToggle(setting, it) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(setting.title, setting.summary, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ChoiceSettingRow(
    setting: MoreReaderSetting.Choice,
    values: Map<String, String>,
    onChoice: (MoreReaderSetting.Choice, String) -> Unit,
) {
    val labels = stringResourceArray(setting.labels)
    val choices = stringResourceArray(setting.values)
    val currentValue = values[setting.key] ?: setting.defaultValue
    val currentLabel = labels.getOrNull(choices.indexOf(currentValue)) ?: currentValue
    var expanded by remember(setting.key) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("more-reader-setting-${setting.key}")
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingLabel(setting.title, setting.summary, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(currentLabel, style = MaterialTheme.typography.bodyMedium)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                labels.forEachIndexed { index, label ->
                    val value = choices.getOrNull(index) ?: return@forEachIndexed
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onChoice(setting, value)
                        },
                        modifier = Modifier.testTag("more-reader-option-${setting.key}-$value"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekBarSettingRow(
    setting: MoreReaderSetting.SeekBar,
    values: Map<String, String>,
    onSeekBar: (MoreReaderSetting.SeekBar, Int) -> Unit,
) {
    val enabled = values["mouseWheelPage"]?.toBooleanStrictOrNull() ?: true
    val value =
        (values[setting.key]?.toIntOrNull() ?: setting.defaultValue).coerceIn(
            setting.minimum,
            setting.maximum,
        )
    Column(
        Modifier.fillMaxWidth()
            .testTag("more-reader-setting-${setting.key}")
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(setting.title, setting.summary, Modifier.weight(1f))
            Text(value.toString(), modifier = Modifier.testTag("more-reader-value-${setting.key}"))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { position ->
                val increment = ((position - setting.minimum) / setting.increment).roundToInt()
                onSeekBar(
                    setting,
                    (setting.minimum + increment * setting.increment).coerceAtMost(setting.maximum),
                )
            },
            enabled = enabled,
            valueRange = setting.minimum.toFloat()..setting.maximum.toFloat(),
            steps = (setting.maximum - setting.minimum) / setting.increment - 1,
            modifier = Modifier.fillMaxWidth().testTag("more-reader-slider-${setting.key}"),
        )
    }
}

@Composable
private fun ActionSettingRow(
    setting: MoreReaderSetting.Action,
    summaryOverride: String?,
    onAction: (MoreReaderSetting.Action) -> Unit,
) {
    val summary = summaryOverride ?: setting.summary?.let { stringResource(it) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("more-reader-setting-${setting.key}")
            .clickable { onAction(setting) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(setting.title, summary, Modifier.weight(1f))
    }
}

@Composable
private fun SettingLabel(title: Int, summary: Int?, modifier: Modifier = Modifier) {
    SettingLabel(title, summary?.let { stringResource(it) }, modifier)
}

@Composable
private fun SettingLabel(title: Int, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun stringResourceArray(resourceId: Int): List<String> =
    androidx.compose.ui.platform.LocalContext.current.resources.getStringArray(resourceId).toList()
