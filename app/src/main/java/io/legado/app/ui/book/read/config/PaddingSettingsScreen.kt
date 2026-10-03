package io.legado.app.ui.book.read.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import io.legado.app.data.preferences.PaddingRegion
import io.legado.app.data.preferences.PaddingSide
import kotlin.math.roundToInt

internal val PaddingRegion.titleRes
    get() =
        when (this) {
            PaddingRegion.HEADER -> R.string.header
            PaddingRegion.BODY -> R.string.main_body
            PaddingRegion.FOOTER -> R.string.footer
        }

@Composable
fun PaddingSettingsScreen(
    state: PaddingSettingsUiState,
    background: Color,
    foreground: Color,
    onRegion: (PaddingRegion) -> Unit,
    onDrag: (PaddingSide, Int) -> Unit,
    onFinish: (PaddingSide) -> Unit,
    onStart: (PaddingSide) -> Unit,
    onStop: (PaddingSide) -> Unit,
    onStep: (PaddingSide, Int) -> Unit,
    onLock: (Boolean) -> Unit,
    onShowLine: (Boolean) -> Unit,
    onReset: () -> Unit,
    onConfirmReset: () -> Unit,
    onCancelReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PaddingRegion.entries.forEach { region ->
                    val selected = region == state.region
                    Surface(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) foreground else background,
                        contentColor = if (selected) background else foreground,
                        border = BorderStroke(1.dp, foreground),
                    ) {
                        Box(
                            Modifier.heightIn(min = 48.dp)
                                .testTag("padding-region-${region.name}")
                                .selectable(
                                    selected,
                                    enabled = !state.isTracking,
                                    role = Role.RadioButton,
                                ) {
                                    onRegion(region)
                                }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(region.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                            )
                        }
                    }
                }
                IconButton(
                    onReset,
                    Modifier.testTag("padding-reset"),
                    enabled = !state.isTracking,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_restore),
                        stringResource(R.string.restore_default),
                        tint = foreground,
                    )
                }
            }
            PaddingSide.entries.forEach { side ->
                PaddingSlider(
                    side,
                    state.current[side],
                    state.isTracking,
                    foreground,
                    onDrag,
                    onFinish,
                    onStart,
                    onStop,
                    onStep,
                )
            }
            PaddingSwitch(
                "padding-lock",
                R.string.lock_left_right_padding,
                state.lockLR,
                !state.isTracking,
                foreground,
                onLock,
            )
            if (state.region != PaddingRegion.BODY)
                PaddingSwitch(
                    "padding-show-line",
                    R.string.showLine,
                    state.current.showLine,
                    true,
                    foreground,
                    onShowLine,
                )
        }
    }
    state.resetRegion?.let { region ->
        AlertDialog(
            onDismissRequest = onCancelReset,
            title = { Text(stringResource(R.string.restore_default)) },
            text = {
                Text(
                    stringResource(
                        R.string.reset_region_padding_confirm,
                        stringResource(region.titleRes),
                    )
                )
            },
            confirmButton = {
                TextButton(onConfirmReset, Modifier.testTag("padding-reset-confirm")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onCancelReset, Modifier.testTag("padding-reset-cancel")) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}

@Composable
private fun PaddingSlider(
    side: PaddingSide,
    value: Int,
    isTracking: Boolean,
    foreground: Color,
    onDrag: (PaddingSide, Int) -> Unit,
    onFinish: (PaddingSide) -> Unit,
    onStart: (PaddingSide) -> Unit,
    onStop: (PaddingSide) -> Unit,
    onStep: (PaddingSide, Int) -> Unit,
) {
    val title =
        stringResource(
            when (side) {
                PaddingSide.TOP -> R.string.padding_top
                PaddingSide.BOTTOM -> R.string.padding_bottom
                PaddingSide.LEFT -> R.string.padding_left
                PaddingSide.RIGHT -> R.string.padding_right
            }
        )
    val plusDescription = "$title ${stringResource(R.string.plus)}"
    val minusDescription = "$title ${stringResource(R.string.reduce)}"
    val interactions = remember { MutableInteractionSource() }
    val latestStart by rememberUpdatedState(onStart)
    val latestStop by rememberUpdatedState(onStop)
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> latestStart(side)
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> latestStop(side)
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(value.toString(), Modifier.testTag("padding-value-${side.name}"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                { onStep(side, -1) },
                Modifier.testTag("padding-minus-${side.name}"),
                enabled = !isTracking && value > 0,
            ) {
                Icon(painterResource(R.drawable.ic_reduce), minusDescription, tint = foreground)
            }
            Slider(
                value.coerceIn(0, side.maximum).toFloat(),
                { onDrag(side, it.roundToInt()) },
                Modifier.weight(1f).testTag("padding-slider-${side.name}").semantics {
                    contentDescription = title
                },
                valueRange = 0f..side.maximum.toFloat(),
                steps = side.maximum - 1,
                onValueChangeFinished = { onFinish(side) },
                interactionSource = interactions,
                colors =
                    SliderDefaults.colors(
                        thumbColor = foreground,
                        activeTrackColor = foreground,
                        inactiveTrackColor = foreground.copy(alpha = .3f),
                    ),
            )
            IconButton(
                { onStep(side, 1) },
                Modifier.testTag("padding-plus-${side.name}"),
                enabled = !isTracking && value < side.maximum,
            ) {
                Icon(painterResource(R.drawable.ic_add), plusDescription, tint = foreground)
            }
        }
    }
}

@Composable
private fun PaddingSwitch(
    tag: String,
    label: Int,
    checked: Boolean,
    enabled: Boolean,
    foreground: Color,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(
            checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = foreground,
                    checkedThumbColor =
                        if (foreground.red + foreground.green + foreground.blue > 1.5f) Color.Black
                        else Color.White,
                ),
        )
    }
}
