package io.legado.app.ui.book.read.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.contrastRatio
import io.legado.app.ui.theme.contrastingForeground
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadStyleSettingsScreen(
    state: ReadStyleSettingsState,
    onSlider: (ReadStyleSlider, Int) -> Unit,
    onPreset: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onShared: (Boolean) -> Unit,
    onAnimation: (Int) -> Unit,
    onPicker: (ReadStylePicker?) -> Unit,
    onPick: (Int) -> Unit,
    onOpen: (ReadStyleDestination) -> Unit,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    preview: @Composable (ReadStylePreset, Modifier) -> Unit,
) {
    val settings = state.settings
    val themeAccent = MaterialTheme.colorScheme.secondary
    val accent =
        if (contrastRatio(themeAccent.toArgb(), background.toArgb()) >= 4.5) {
            themeAccent
        } else {
            Color(contrastingForeground(background.toArgb()))
        }
    val weightLabels = stringArrayResource(R.array.text_font_weight)
    val chineseLabels = stringArrayResource(R.array.chinese_mode)
    val indentLabels = stringArrayResource(R.array.indent)
    Surface(modifier.fillMaxWidth(), color = background, contentColor = foreground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weightText = stringResource(R.string.font_weight_text)
                OutlinedButton(
                    { onPicker(ReadStylePicker.Weight) },
                    Modifier.testTag("read-style-weight").semantics {
                        contentDescription =
                            weightLabels[settings.weight.coerceIn(weightLabels.indices)]
                    },
                ) {
                    Text(
                        buildAnnotatedString {
                            weightText.forEachIndexed { index, character ->
                                withStyle(
                                    SpanStyle(
                                        color =
                                            if (index == settings.weight * 2) accent else foreground
                                    )
                                ) {
                                    append(character)
                                }
                            }
                        }
                    )
                }
                ReadStyleAction("read-style-font", R.string.text_font) {
                    onOpen(ReadStyleDestination.Font)
                }
                ReadStyleAction("read-style-indent", R.string.text_indent) {
                    onPicker(ReadStylePicker.Indent)
                }
                OutlinedButton(
                    { onPicker(ReadStylePicker.Chinese) },
                    Modifier.testTag("read-style-chinese").semantics {
                        contentDescription =
                            chineseLabels[settings.chinese.coerceIn(chineseLabels.indices)]
                    },
                ) {
                    Text(
                        buildAnnotatedString {
                            "简/繁"
                                .forEachIndexed { index, character ->
                                    val chosen =
                                        settings.chinese == 1 && index == 0 ||
                                            settings.chinese == 2 && index == 2
                                    withStyle(
                                        SpanStyle(color = if (chosen) accent else foreground)
                                    ) {
                                        append(character)
                                    }
                                }
                        }
                    )
                }
                ReadStyleAction("read-style-padding", R.string.padding) {
                    onOpen(ReadStyleDestination.Padding)
                }
                ReadStyleAction("read-style-tip", R.string.information) {
                    onOpen(ReadStyleDestination.Tip)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                ReadStyleSliderRow(R.string.text_size, ReadStyleSlider.TextSize, settings, onSlider)
                ReadStyleSliderRow(
                    R.string.text_letter_spacing,
                    ReadStyleSlider.LetterSpacing,
                    settings,
                    onSlider,
                )
                ReadStyleSliderRow(
                    R.string.line_size,
                    ReadStyleSlider.LineSpacing,
                    settings,
                    onSlider,
                )
                ReadStyleSliderRow(
                    R.string.paragraph_size,
                    ReadStyleSlider.ParagraphSpacing,
                    settings,
                    onSlider,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.page_anim),
                    style = MaterialTheme.typography.labelMedium,
                )
                val labels =
                    listOf(
                        R.string.page_anim_cover,
                        R.string.page_anim_slide,
                        R.string.page_anim_simulation,
                        R.string.page_anim_scroll,
                        R.string.page_anim_none,
                    )
                FlowRow(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    labels.forEachIndexed { index, label ->
                        Surface(
                            Modifier.testTag("read-style-animation-$index")
                                .heightIn(min = 48.dp)
                                .selectable(
                                    settings.pageAnimation == index,
                                    role = Role.RadioButton,
                                ) {
                                    onAnimation(index)
                                },
                            color =
                                if (settings.pageAnimation == index)
                                    MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                        ) {
                            Box(
                                Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.text_bg_style),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        Modifier.testTag("read-style-share")
                            .heightIn(min = 48.dp)
                            .toggleable(
                                settings.shared,
                                role = Role.Checkbox,
                                onValueChange = onShared,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.share_layout))
                        Checkbox(settings.shared, null)
                    }
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().testTag("read-style-presets"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(settings.presets, key = { it.index }) { preset ->
                    val selected = preset.index == settings.selected
                    Box(
                        Modifier.size(48.dp)
                            .clip(CircleShape)
                            .border(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.secondary
                                else Color(preset.textColor),
                                CircleShape,
                            )
                            .testTag("read-style-preset-${preset.index}")
                            .semantics {
                                this.selected = selected
                                contentDescription = preset.name
                            }
                            .combinedClickable(
                                onClick = { onPreset(preset.index) },
                                onLongClick = { onEdit(preset.index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        preview(preset, Modifier.fillMaxSize())
                        Text(
                            preset.name,
                            color = Color(preset.textColor),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                item("add") {
                    IconButton(
                        onAdd,
                        Modifier.size(48.dp)
                            .testTag("read-style-add")
                            .border(1.dp, foreground, CircleShape),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add))
                    }
                }
            }
        }
    }
    state.picker?.let { picker ->
        val (title, labels) =
            when (picker) {
                ReadStylePicker.Weight -> R.string.text_font_weight_converter to weightLabels
                ReadStylePicker.Chinese -> R.string.chinese_converter to chineseLabels
                ReadStylePicker.Indent -> R.string.text_indent to indentLabels
            }
        AlertDialog(
            onDismissRequest = { onPicker(null) },
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    labels.forEachIndexed { index, text ->
                        Text(
                            text,
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("read-style-pick-$index")
                                .clickable { onPick(index) }
                                .padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton({ onPicker(null) }, Modifier.testTag("read-style-picker-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ReadStyleAction(tag: String, label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.testTag(tag)) { Text(stringResource(label)) }
}

@Composable
private fun ReadStyleSliderRow(
    label: Int,
    slider: ReadStyleSlider,
    settings: ReadStyleSettingsSnapshot,
    onChange: (ReadStyleSlider, Int) -> Unit,
) {
    val progress = settings.progress(slider)
    val name = stringResource(label)
    val reduce = "$name ${stringResource(R.string.reduce)}"
    val plus = "$name ${stringResource(R.string.plus)}"
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(name, Modifier.weight(1f))
            Text(slider.display(progress), Modifier.testTag("read-style-value-${slider.name}"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                { onChange(slider, progress - 1) },
                Modifier.testTag("read-style-minus-${slider.name}"),
                enabled = progress > 0,
            ) {
                Icon(painterResource(R.drawable.ic_reduce), reduce)
            }
            Slider(
                progress.toFloat(),
                { onChange(slider, it.roundToInt()) },
                Modifier.weight(1f).testTag("read-style-slider-${slider.name}").semantics {
                    contentDescription = name
                },
                valueRange = 0f..slider.maximum.toFloat(),
                steps = slider.maximum - 1,
            )
            IconButton(
                { onChange(slider, progress + 1) },
                Modifier.testTag("read-style-plus-${slider.name}"),
                enabled = progress < slider.maximum,
            ) {
                Icon(painterResource(R.drawable.ic_add), plus)
            }
        }
    }
}
