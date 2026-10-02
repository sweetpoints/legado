package io.legado.app.ui.book.read.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.*
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun BgTextSettingsScreen(state: BgTextSettingsState, onIntent: (BgTextSettingsIntent) -> Unit,
    background: Color, foreground: Color, secondary: Color, modifier: Modifier = Modifier,
    assetPreview: @Composable (String, Modifier) -> Unit, svgPreview: @Composable (String, Modifier) -> Unit) {
    val settings = state.settings
    val modes = listOf(R.string.highlight_action_trigger_off, R.string.highlight_underline_solid, R.string.highlight_underline_dashed,
        R.string.highlight_underline_dotted, R.string.highlight_underline_double, R.string.highlight_underline_wavy, R.string.underline_double_dashed)
    Surface(modifier.fillMaxWidth(), color = background, contentColor = foreground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.style_name))
                Text(settings.name.ifBlank { stringResource(R.string.text) }, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("bg-name"),
                    color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.Name)) }, Modifier.testTag("bg-edit-name")) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
                }
                TextButton({ onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.Restore)) }, Modifier.testTag("bg-restore")) { Text(stringResource(R.string.restore)) }
            }
            BgToggle("bg-dark-status", R.string.dark_status_icon, settings.darkStatus) { onIntent(BgTextSettingsIntent.DarkStatus(it)) }
            if (!settings.imageBook) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bg-underline-mode")
                    .clickable { onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.UnderlineMode)) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_underline), Modifier.weight(1f))
                    Text(stringResource(modes[settings.underlineMode.coerceIn(modes.indices)]), color = secondary)
                }
                OutlinedButton({ onIntent(BgTextSettingsIntent.Color(BgTextColor.Underline)) }, Modifier.testTag("bg-color-Underline")) {
                    Text(stringResource(R.string.underline_color)); BgColorSwatch(settings.underlineColor)
                }
                BgToggle("bg-underline-body", R.string.underline_body, settings.underlineBody) { onIntent(BgTextSettingsIntent.UnderlineBody(it)) }
                BgToggle("bg-underline-title", R.string.underline_title, settings.underlineTitle) { onIntent(BgTextSettingsIntent.UnderlineTitle(it)) }
                BgSlider(R.string.underline_width, BgTextSlider.Width, settings, onIntent)
                BgSlider(R.string.underline_distance, BgTextSlider.Distance, settings, onIntent)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(BgTextColor.Text to R.string.text_color, BgTextColor.Background to R.string.bg_color, BgTextColor.Accent to R.string.text_accent_color).forEach { (channel, label) ->
                    OutlinedButton({ onIntent(BgTextSettingsIntent.Color(channel)) }, Modifier.testTag("bg-color-${channel.name}")) { Text(stringResource(label)); BgColorSwatch(settings.color(channel)) }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.Templates)) }, Modifier.testTag("bg-templates")) { Text(stringResource(R.string.review_icon_templates_title)) }
                OutlinedButton({ onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.ReviewScale)) }, Modifier.testTag("bg-review-scale")) { Text(stringResource(R.string.review_icon_size_title)); Text(" ${settings.reviewScale}%") }
                Box(Modifier.testTag("bg-color-Review").heightIn(min = 48.dp)
                    .combinedClickable(onClick = { onIntent(BgTextSettingsIntent.Color(BgTextColor.Review)) }, onLongClick = { onIntent(BgTextSettingsIntent.ResetReviewColor) })
                    .border(1.dp, MaterialTheme.colorScheme.outline).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.review_icon_color_title)); BgColorSwatch(settings.effectiveReviewColor) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ onIntent(BgTextSettingsIntent.Picker(BgTextAction.PickImport)) }, Modifier.testTag("bg-import")) { Icon(painterResource(R.drawable.ic_import), stringResource(R.string.import_str)) }
                IconButton({ onIntent(BgTextSettingsIntent.Picker(BgTextAction.PickExport)) }, Modifier.testTag("bg-export")) { Icon(painterResource(R.drawable.ic_export), stringResource(R.string.export_str)) }
                IconButton({ onIntent(BgTextSettingsIntent.DeletePreset) }, Modifier.testTag("bg-delete")) { Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.delete)) }
            }
            BgSlider(R.string.bg_alpha, BgTextSlider.Alpha, settings, onIntent)
            Text(stringResource(R.string.bg_image), Modifier.padding(vertical = 8.dp))
            if (state.loadingAssets) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("bg-assets-loading"))
            LazyRow(Modifier.fillMaxWidth().testTag("bg-assets"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item("select") { Column(Modifier.width(66.dp).height(88.dp).testTag("bg-pick-image")
                    .clickable { onIntent(BgTextSettingsIntent.Picker(BgTextAction.PickBackground)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_image), null, Modifier.weight(1f).fillMaxWidth())
                    Text(stringResource(R.string.select_image), maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary)
                } }
                items(state.assets, key = { it }) { asset -> Column(Modifier.width(66.dp).height(88.dp).testTag("bg-asset-$asset")
                    .clickable { onIntent(BgTextSettingsIntent.Asset(asset)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    assetPreview(asset, Modifier.weight(1f).fillMaxWidth()); Text(asset.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary)
                } }
            }
            if (state.work != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("bg-working"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.bg_text_working), Modifier.padding(start = 8.dp))
            }
        }
    }
    state.editor?.let { editor ->
        when (editor.kind) {
            BgTextEditorKind.Templates -> BgTemplateDialog(state, onIntent, svgPreview)
            BgTextEditorKind.DeleteTemplate -> AlertDialog(onDismissRequest = { onIntent(BgTextSettingsIntent.CancelEditor) },
                title = { Text(editor.text.ifBlank { stringResource(R.string.review_icon_template_unnamed) }) }, text = { Text(stringResource(R.string.sure_del)) },
                confirmButton = { TextButton({ onIntent(BgTextSettingsIntent.DeleteTemplate) }, Modifier.testTag("bg-template-delete-confirm")) { Text(stringResource(R.string.yes)) } },
                dismissButton = { Row {
                    TextButton({ onIntent(BgTextSettingsIntent.SaveTemplate("", BgTextTemplate(editor.text, editor.svg))) }, Modifier.testTag("bg-template-rename")) { Text(stringResource(R.string.edit)) }
                    TextButton({ onIntent(BgTextSettingsIntent.CancelEditor) }) { Text(stringResource(R.string.no)) }
                } })
            BgTextEditorKind.Restore, BgTextEditorKind.UnderlineMode -> {
                val labels = if (editor.kind == BgTextEditorKind.Restore) state.defaults.map { it.name } else modes.map { stringResource(it) }
                AlertDialog(onDismissRequest = { onIntent(BgTextSettingsIntent.CancelEditor) }, title = { Text(stringResource(if (editor.kind == BgTextEditorKind.Restore) R.string.bg_text_restore_title else R.string.text_underline)) },
                    text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        labels.forEachIndexed { index, text -> Text(text, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bg-select-$index")
                            .clickable { onIntent(if (editor.kind == BgTextEditorKind.Restore) BgTextSettingsIntent.Default(index) else BgTextSettingsIntent.UnderlineMode(index)) }.padding(12.dp)) }
                    } }, confirmButton = {}, dismissButton = { TextButton({ onIntent(BgTextSettingsIntent.CancelEditor) }) { Text(stringResource(R.string.cancel)) } })
            }
            else -> BgInputDialog(editor, state.work?.kind in setOf(BgTextWorkKind.SvgEdit, BgTextWorkKind.TemplatePrepare, BgTextWorkKind.TemplateApply), onIntent)
        }
    }
}
@Composable private fun BgToggle(tag: String, label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(checked, null)
    }
}
@Composable private fun BgColorSwatch(value: Int) { Box(Modifier.padding(start = 8.dp).size(18.dp).border(1.dp, MaterialTheme.colorScheme.outline).background(Color(value))) }
@Composable private fun BgSlider(label: Int, slider: BgTextSlider, settings: BgTextSettingsSnapshot, onIntent: (BgTextSettingsIntent) -> Unit) {
    val progress = slider.value(settings); val text = stringResource(label)
    val minus = "$text ${stringResource(R.string.reduce)}"; val plus = "$text ${stringResource(R.string.plus)}"
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(text, Modifier.weight(1f)); Text(slider.display(progress), Modifier.testTag("bg-value-${slider.name}")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onIntent(BgTextSettingsIntent.Slider(slider, progress - 1)) }, Modifier.testTag("bg-minus-${slider.name}"), enabled = progress > 0) { Icon(painterResource(R.drawable.ic_reduce), minus) }
            Slider(progress.toFloat(), { onIntent(BgTextSettingsIntent.Slider(slider, it.roundToInt())) }, Modifier.weight(1f).testTag("bg-slider-${slider.name}")
                .semantics { contentDescription = text }, valueRange = 0f..slider.maximum.toFloat(), steps = slider.maximum - 1,
                onValueChangeFinished = { if (slider == BgTextSlider.Alpha) onIntent(BgTextSettingsIntent.AlphaFinished) })
            IconButton({ onIntent(BgTextSettingsIntent.Slider(slider, progress + 1)) }, Modifier.testTag("bg-plus-${slider.name}"), enabled = progress < slider.maximum) { Icon(painterResource(R.drawable.ic_add), plus) }
        }
    }
}
@Composable private fun BgTemplateDialog(state: BgTextSettingsState, onIntent: (BgTextSettingsIntent) -> Unit,
    preview: @Composable (String, Modifier) -> Unit) {
    val defaultName = stringResource(R.string.review_icon_template_default_name, state.settings.templates.size + 1)
    AlertDialog(onDismissRequest = { onIntent(BgTextSettingsIntent.CancelEditor) }, title = { Text(stringResource(R.string.review_icon_templates_title)) },
        text = { Column {
            state.editor?.error?.let { Text(bgEditorError(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bg-editor-error")) }
            if (state.work != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 176.dp).testTag("bg-template-grid")) {
                itemsIndexed(state.settings.templates, key = { index, _ -> index }) { index, template ->
                    val name = template.name.ifBlank { stringResource(R.string.review_icon_template_unnamed) }
                    BgTemplateCard(template, name, index, onIntent, preview)
                }
            }
        } }, confirmButton = { TextButton({ onIntent(BgTextSettingsIntent.SaveTemplate(defaultName)) }, Modifier.testTag("bg-template-save")) { Text(stringResource(R.string.review_icon_template_save)) } },
        dismissButton = { Row {
            TextButton({ onIntent(BgTextSettingsIntent.Editor(BgTextEditorKind.Svg)) }, Modifier.testTag("bg-svg-edit")) { Text(stringResource(R.string.review_icon_svg_edit)) }
            TextButton({ onIntent(BgTextSettingsIntent.CancelEditor) }) { Text(stringResource(R.string.cancel)) }
        } })
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BgTemplateCard(template: BgTextTemplate, name: String, index: Int,
    onIntent: (BgTextSettingsIntent) -> Unit, preview: @Composable (String, Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth().height(88.dp).testTag("bg-template-$index").semantics { contentDescription = name }
        .combinedClickable(onClick = { onIntent(BgTextSettingsIntent.ApplyTemplate(template)) }, onLongClick = { onIntent(BgTextSettingsIntent.DeleteTemplateEditor(template)) }), horizontalAlignment = Alignment.CenterHorizontally) {
        preview(template.svg, Modifier.weight(1f).fillMaxWidth()); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun BgInputDialog(editor: BgTextEditor, working: Boolean, onIntent: (BgTextSettingsIntent) -> Unit) {
    val title = when (editor.kind) {
        BgTextEditorKind.Name -> R.string.style_name; BgTextEditorKind.ReviewScale -> R.string.review_icon_size_title
        BgTextEditorKind.Svg -> R.string.review_icon_svg_title; BgTextEditorKind.ImportUrl -> R.string.bg_text_import_address
        BgTextEditorKind.TemplateName -> R.string.review_icon_template_name
        BgTextEditorKind.Color -> when (editor.color) { BgTextColor.Text -> R.string.text_color; BgTextColor.Background -> R.string.bg_color
            BgTextColor.Accent -> R.string.text_accent_color; BgTextColor.Review -> R.string.review_icon_color_title; else -> R.string.underline_color }
        else -> R.string.edit
    }
    var composing by remember(editor.kind, editor.context) { mutableStateOf(TextFieldValue(editor.text, TextRange(editor.start, editor.end))) }
    val value = if (composing.text == editor.text && composing.selection == TextRange(editor.start, editor.end)) composing else TextFieldValue(editor.text, TextRange(editor.start, editor.end))
    AlertDialog(onDismissRequest = { onIntent(BgTextSettingsIntent.CancelEditor) }, title = { Text(stringResource(title)) },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value, { composing = it; onIntent(BgTextSettingsIntent.Text(it.text, it.selection.start, it.selection.end)) },
                Modifier.fillMaxWidth().testTag("bg-editor-input"), singleLine = editor.kind != BgTextEditorKind.Svg, maxLines = if (editor.kind == BgTextEditorKind.Svg) 8 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (editor.kind == BgTextEditorKind.ReviewScale) KeyboardType.Number else KeyboardType.Text),
                placeholder = { if (editor.kind == BgTextEditorKind.Svg) Text(stringResource(R.string.review_icon_svg_hint)) else if (editor.kind == BgTextEditorKind.ReviewScale) Text(stringResource(R.string.review_icon_size_hint)) },
                isError = editor.error != null)
            editor.error?.let { Text(bgEditorError(it), Modifier.testTag("bg-editor-error"), color = MaterialTheme.colorScheme.error) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (editor.kind == BgTextEditorKind.Color) BgColorChannels(editor, onIntent)
        } }, confirmButton = { TextButton({ onIntent(BgTextSettingsIntent.ConfirmEditor) }, Modifier.testTag("bg-editor-confirm"), enabled = !working) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton({ onIntent(BgTextSettingsIntent.CancelEditor) }, Modifier.testTag("bg-editor-cancel")) { Text(stringResource(R.string.cancel)) } })
}
@Composable private fun BgColorChannels(editor: BgTextEditor, onIntent: (BgTextSettingsIntent) -> Unit) {
    val hex = editor.text.trim().removePrefix("#"); val length = if (editor.color == BgTextColor.Underline) 8 else 6
    val parsed = hex.takeIf { it.length == length }?.toLongOrNull(16)?.toInt()?.let { if (length == 6) it or 0xff000000.toInt() else it }
    if (parsed != null) {
        Box(Modifier.fillMaxWidth().height(36.dp).padding(vertical = 4.dp).background(Color(parsed)).testTag("bg-color-preview"))
        (if (editor.color == BgTextColor.Underline) 0..3 else 1..3).forEach { channel ->
            val label = listOf("A", "R", "G", "B")[channel]
            val description = stringResource(R.string.bg_text_color_channel, label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(24.dp))
                Slider(((parsed ushr ((3 - channel) * 8)) and 255).toFloat(),
                    { onIntent(BgTextSettingsIntent.ColorChannel(channel, it.roundToInt())) }, Modifier.weight(1f).testTag("bg-color-channel-$channel")
                        .semantics { contentDescription = description }, valueRange = 0f..255f, steps = 254)
            }
        }
    }
}
@Composable private fun bgEditorError(error: BgTextError): String = stringResource(when (error) {
    BgTextError.SvgInvalid -> R.string.review_icon_svg_invalid; BgTextError.NoSvg -> R.string.review_icon_template_no_svg
    BgTextError.NameEmpty -> R.string.review_icon_template_name_empty; BgTextError.ScaleInvalid -> R.string.review_icon_size_invalid
    BgTextError.ColorInvalid -> R.string.bg_text_color_invalid
})
