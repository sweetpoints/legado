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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.roundToInt

@Composable
internal fun AutoReadScreen(
    state: AutoReadUiState,
    background: Color,
    foreground: Color,
    onSpeedChange: (Int) -> Unit,
    onSpeedChangeFinished: () -> Unit,
    onCatalog: () -> Unit,
    onMenu: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedLabel = stringResource(R.string.auto_page_speed)
    Surface(modifier, color = background, contentColor = foreground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(speedLabel, Modifier.weight(1f))
                Text("${state.speed}s", Modifier.testTag("auto-read-speed-value"))
            }
            Slider(state.speed.toFloat(), { onSpeedChange(it.roundToInt().coerceIn(1, 120)) },
                Modifier.fillMaxWidth().testTag("auto-read-speed").semantics { contentDescription = speedLabel },
                valueRange = 1f..120f, steps = 118, onValueChangeFinished = onSpeedChangeFinished,
                colors = SliderDefaults.colors(activeTrackColor = foreground, thumbColor = foreground,
                    inactiveTrackColor = foreground.copy(alpha = 0.3f)))
            state.error?.let { Text(it, color = foreground, modifier = Modifier.testTag("auto-read-error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Action(stringResource(R.string.chapter_list), R.drawable.ic_toc, "auto-read-catalog", onCatalog, Modifier.weight(1f))
                Action(stringResource(R.string.main_menu), R.drawable.ic_menu, "auto-read-menu", onMenu, Modifier.weight(1f))
                Action(stringResource(R.string.stop), R.drawable.ic_auto_page_stop, "auto-read-stop", onStop, Modifier.weight(1f))
                Action(stringResource(R.string.setting), R.drawable.ic_settings, "auto-read-settings", onSettings, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Action(title: String, @DrawableRes icon: Int, tag: String, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.testTag(tag).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), contentDescription = null)
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
