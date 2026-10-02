package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.data.repository.HighlightStyleRepository
import io.legado.app.help.HighlightStyle
import kotlin.math.roundToInt

internal fun highlightChannelLabel(channel: HighlightChannel) = when (channel) {
    HighlightChannel.Fill -> R.string.highlight_bg_color
    HighlightChannel.Text -> R.string.highlight_text_color
    HighlightChannel.Bold -> R.string.highlight_bold
    HighlightChannel.Italic -> R.string.highlight_italic
    HighlightChannel.Underline -> R.string.highlight_underline
    HighlightChannel.Strike -> R.string.highlight_strike
    HighlightChannel.Box -> R.string.highlight_box
    HighlightChannel.Emphasis -> R.string.highlight_emphasis
    HighlightChannel.Shadow -> R.string.highlight_shadow
}
private fun fillLabel(shape: HighlightStyle.FillShape) = when (shape) {
    HighlightStyle.FillShape.RECTANGLE -> R.string.highlight_fill_rectangle
    HighlightStyle.FillShape.ROUNDED -> R.string.highlight_fill_rounded
    HighlightStyle.FillShape.MARKER -> R.string.highlight_fill_marker
    HighlightStyle.FillShape.HALF -> R.string.highlight_fill_half
    HighlightStyle.FillShape.BASELINE -> R.string.highlight_fill_baseline
    HighlightStyle.FillShape.PILL -> R.string.highlight_fill_pill
}
private fun underlineLabel(kind: HighlightStyle.Kind) = when (kind) {
    HighlightStyle.Kind.SOLID -> R.string.highlight_underline_solid
    HighlightStyle.Kind.WAVY -> R.string.highlight_underline_wavy
    HighlightStyle.Kind.DASHED -> R.string.highlight_underline_dashed
    HighlightStyle.Kind.DOTTED -> R.string.highlight_underline_dotted
    HighlightStyle.Kind.DOUBLE -> R.string.highlight_underline_double
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HighlightStyleScreen(state: HighlightStyleState, presets: List<HighlightStyle>, fontName: String,
    onPreset: (Int) -> Unit, onToggle: (HighlightChannel, Boolean) -> Unit, onColor: (HighlightChannel) -> Unit,
    onExtra: (HighlightChannel) -> Unit, onTune: (HighlightChannel) -> Unit, onFont: () -> Unit,
    onNumber: (HighlightNumber) -> Unit, onNumberText: (String) -> Unit, onNumberValue: (Int) -> Unit,
    onNumberClose: () -> Unit, onNumberSave: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val repo = remember { HighlightStyleRepository() }
    val style = state.style
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(stringResource(R.string.highlight_presets), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                presets.forEachIndexed { index, preset ->
                    val label = "${stringResource(R.string.highlight_presets)} ${index + 1}"
                    Box(Modifier.size(48.dp).semantics { contentDescription = label }.testTag("highlight-style-preset-$index")
                        .clickable(role = Role.Button) { onPreset(index) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(repo.swatch(preset))))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            HighlightChannel.entries.forEach { channel ->
                val enabled = repo.enabled(style, channel)
                val label = stringResource(highlightChannelLabel(channel))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("highlight-style-channel-$channel")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled, { onToggle(channel, it) }, Modifier.testTag("highlight-style-toggle-$channel"))
                        Text(label, Modifier.weight(1f).clickable(role = Role.Checkbox) { onToggle(channel, !enabled) }, fontSize = 15.sp)
                        if (enabled && channel != HighlightChannel.Bold && channel != HighlightChannel.Italic)
                            Box(Modifier.size(48.dp).testTag("highlight-style-color-$channel").semantics { contentDescription = label }
                                .clickable(role = Role.Button) { onColor(channel) }, contentAlignment = Alignment.Center) {
                                Box(Modifier.width(28.dp).height(20.dp).clip(RoundedCornerShape(4.dp))
                                    .background(Color(repo.color(style, channel).takeIf { it != 0 } ?: HighlightStyleRepository.DEFAULT_SWATCH)))
                            }
                    }
                    if (enabled) FlowRow(Modifier.padding(start = 48.dp)) {
                        when (channel) {
                            HighlightChannel.Fill -> {
                                TextButton({ onExtra(channel) }, Modifier.testTag("highlight-style-extra-$channel")) { Text(stringResource(fillLabel(style.resolvedFillShape))) }
                                if (style.resolvedFillShape == HighlightStyle.FillShape.PILL)
                                    TextButton({ onTune(channel) }, Modifier.testTag("highlight-style-tune-$channel")) {
                                        Text(stringResource(R.string.highlight_pill_padding_value, (style.resolvedPillPaddingScale * 100).roundToInt()))
                                    }
                            }
                            HighlightChannel.Underline -> {
                                TextButton({ onExtra(channel) }, Modifier.testTag("highlight-style-extra-$channel")) { Text(stringResource(underlineLabel(style.underline!!.kind))) }
                                TextButton({ onTune(channel) }, Modifier.testTag("highlight-style-tune-$channel")) { Text(stringResource(R.string.highlight_underline_adjust)) }
                            }
                            HighlightChannel.Shadow -> TextButton({ onExtra(channel) }, Modifier.testTag("highlight-style-extra-$channel")) {
                                val shadow = style.shadow!!
                                Text(stringResource(R.string.highlight_shadow_values, shadow.radius, shadow.dx, shadow.dy))
                            }
                            else -> Unit
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onFont).testTag("highlight-style-font")
                .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.text_font), Modifier.weight(1f), fontSize = 15.sp)
                Text(fontName, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ onNumber(HighlightNumber.FontSize) }, Modifier.fillMaxWidth().testTag("highlight-style-font-size")) {
                Text("${stringResource(R.string.text_size)} · ${style.resolvedFontSize?.roundToInt() ?: stringResource(R.string.btn_default_s)}")
            }
            TextButton({ onNumber(HighlightNumber.LetterSpacing) }, Modifier.fillMaxWidth().testTag("highlight-style-letter-spacing")) {
                Text("${stringResource(R.string.text_letter_spacing)} · ${style.resolvedLetterSpacing?.let { "${(it * 100).roundToInt()}%" } ?: stringResource(R.string.btn_default_s)}")
            }
        }
    }
    state.number?.let { draft ->
        val title = stringResource(when (draft.setting) {
            HighlightNumber.FontSize -> R.string.text_size
            HighlightNumber.LetterSpacing -> R.string.text_letter_spacing
            HighlightNumber.PillPadding -> R.string.highlight_pill_padding
        })
        val range = HighlightStyleViewModel.range(draft.setting)
        AlertDialog(onDismissRequest = onNumberClose, title = { Text(title) }, text = {
            Column {
                OutlinedTextField(draft.text, onNumberText, Modifier.fillMaxWidth().testTag("highlight-style-number-input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Slider(draft.value.toFloat(), { onNumberValue(it.roundToInt()) }, Modifier.testTag("highlight-style-number-slider"),
                    valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
                TextButton({ onNumberSave(true) }, Modifier.testTag("highlight-style-number-default")) { Text(stringResource(R.string.btn_default_s)) }
            }
        }, confirmButton = { TextButton({ onNumberSave(false) }, enabled = draft.text.toIntOrNull() != null,
            modifier = Modifier.testTag("highlight-style-number-save")) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onNumberClose) { Text(stringResource(R.string.cancel)) } })
    }
}
