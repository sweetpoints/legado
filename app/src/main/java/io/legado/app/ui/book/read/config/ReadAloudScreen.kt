package io.legado.app.ui.book.read.config

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
internal fun ReadAloudScreen(state: ReadAloudUiState, background: Color, foreground: Color,
    onControl: (ReadAloudControl) -> Unit, onRateChange: (Int) -> Unit, onRateFinished: () -> Unit,
    onRateStep: (Int) -> Unit, onFollow: (Boolean) -> Unit, onTimerChange: (Int) -> Unit,
    onTimerFinished: () -> Unit, onSaveTimer: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.finished
    val rateEnabled = enabled && !state.followSystem
    val rateLabel = stringResource(R.string.read_aloud_speed)
    val timerLabel = when {
        state.timerEditing -> stringResource(R.string.timer_m, state.timer)
        state.chapter > 0 -> stringResource(R.string.sleep_timer_chapters, state.chapter)
        state.minute > 0 -> stringResource(R.string.timer_m, state.minute)
        else -> stringResource(R.string.set_timer)
    }
    val sliderColors = SliderDefaults.colors(thumbColor = foreground, activeTrackColor = foreground,
        inactiveTrackColor = foreground.copy(alpha = 0.3f), disabledThumbColor = foreground.copy(alpha = 0.38f),
        disabledActiveTrackColor = foreground.copy(alpha = 0.38f), disabledInactiveTrackColor = foreground.copy(alpha = 0.12f))
    Surface(modifier, color = background, contentColor = foreground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val displayedEngine = state.engineName.ifEmpty {
                stringResource(if (state.engineLoading) R.string.loading else R.string.system_tts)
            }
            val engineLabel = stringResource(R.string.speak_engine) + ": " + displayedEngine
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("read-aloud-engine")
                .clickable(enabled = enabled, role = Role.Button) { onControl(ReadAloudControl.Engine) }
                .semantics { contentDescription = engineLabel },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_volume_up), null)
                Text(displayedEngine, Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_arrow_drop_down), null)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ onControl(ReadAloudControl.PreviousChapter) }, Modifier.testTag("read-aloud-previous-chapter"), enabled) {
                    Text(stringResource(R.string.previous_chapter), color = foreground)
                }
                TextButton({ onControl(ReadAloudControl.NextChapter) }, Modifier.testTag("read-aloud-next-chapter"), enabled) {
                    Text(stringResource(R.string.next_chapter), color = foreground)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PlaybackIcon(R.drawable.ic_skip_previous, stringResource(R.string.prev_sentence), "read-aloud-previous-paragraph", enabled) {
                    onControl(ReadAloudControl.PreviousParagraph)
                }
                PlaybackIcon(if (state.paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
                    stringResource(if (state.paused) R.string.audio_play else R.string.pause), "read-aloud-play-pause", enabled) {
                    onControl(ReadAloudControl.PlayPause)
                }
                PlaybackIcon(R.drawable.ic_stop_black_24dp, stringResource(R.string.stop), "read-aloud-stop", enabled) {
                    onControl(ReadAloudControl.Stop)
                }
                PlaybackIcon(R.drawable.ic_skip_next, stringResource(R.string.next_sentence), "read-aloud-next-paragraph", enabled) {
                    onControl(ReadAloudControl.NextParagraph)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlaybackIcon(R.drawable.ic_time_add_24dp, stringResource(R.string.set_timer), "read-aloud-save-timer", enabled, onSaveTimer)
                TextButton({ onControl(ReadAloudControl.SleepTimer) }, Modifier.weight(1f).testTag("read-aloud-sleep-timer"), enabled) {
                    Text(timerLabel, color = foreground)
                }
            }
            Slider(state.timer.toFloat(), { onTimerChange(it.roundToInt().coerceIn(0, 180)) },
                Modifier.fillMaxWidth().testTag("read-aloud-timer").semantics { contentDescription = timerLabel },
                enabled = enabled, valueRange = 0f..180f, steps = 179, onValueChangeFinished = onTimerFinished,
                colors = sliderColors)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rateLabel, Modifier.weight(1f))
                if (!state.followSystem) Text(state.rateText, Modifier.testTag("read-aloud-rate-value"))
            }
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("read-aloud-follow-system")
                .toggleable(state.followSystem, enabled = enabled, role = Role.Switch, onValueChange = onFollow),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.flow_sys), Modifier.weight(1f))
                Switch(state.followSystem, onCheckedChange = null, enabled = enabled)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlaybackIcon(R.drawable.ic_reduce, stringResource(R.string.tts_speech_reduce), "read-aloud-rate-minus", rateEnabled && state.rate > 0) { onRateStep(-1) }
                Slider(state.rate.toFloat(), { onRateChange(it.roundToInt().coerceIn(0, 45)) },
                    Modifier.weight(1f).testTag("read-aloud-rate").semantics { contentDescription = rateLabel },
                    enabled = rateEnabled, valueRange = 0f..45f, steps = 44, onValueChangeFinished = onRateFinished,
                    colors = sliderColors)
                PlaybackIcon(R.drawable.ic_add, stringResource(R.string.tts_speech_add), "read-aloud-rate-plus", rateEnabled && state.rate < 45) { onRateStep(1) }
            }
            state.error?.let { Text(it, Modifier.testTag("read-aloud-error"), color = foreground) }
            Row(Modifier.fillMaxWidth()) {
                BottomAction(R.drawable.ic_toc, stringResource(R.string.chapter_list), "read-aloud-catalog", enabled, Modifier.weight(1f)) { onControl(ReadAloudControl.Catalog) }
                BottomAction(R.drawable.ic_menu, stringResource(R.string.main_menu), "read-aloud-main-menu", enabled, Modifier.weight(1f)) { onControl(ReadAloudControl.MainMenu) }
                BottomAction(R.drawable.ic_visibility_off, stringResource(R.string.to_backstage), "read-aloud-background", enabled, Modifier.weight(1f)) { onControl(ReadAloudControl.Background) }
                BottomAction(R.drawable.ic_settings, stringResource(R.string.setting), "read-aloud-settings", enabled, Modifier.weight(1f)) { onControl(ReadAloudControl.Settings) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackIcon(@DrawableRes icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()) {
        IconButton(onClick, Modifier.testTag(tag), enabled) { Icon(painterResource(icon), label) }
    }
}

@Composable
private fun BottomAction(@DrawableRes icon: Int, label: String, tag: String, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
