package io.legado.app.ui.book.read.config

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.TipSetting
import io.legado.app.data.preferences.TipTemplateSlot
import io.legado.app.help.config.ReaderInfoTemplate
import kotlin.math.roundToInt

@Composable
fun TipSettingsScreen(
    state: TipSettingsUiState,
    onSetting: (TipSetting, Int) -> Unit,
    onFont: () -> Unit,
    onSelector: (TipSetting) -> Unit,
    onTemplate: (TipTemplateSlot) -> Unit,
    onSelect: (Int) -> Unit,
    onColor: (TipSetting) -> Unit,
    onColorText: (String) -> Unit,
    onColorChannel: (Int, Int) -> Unit,
    onConfirmColor: () -> Unit,
    onTemplateText: (String, Int, Int) -> Unit,
    onPlaceholder: (String) -> Unit,
    onConfirmTemplate: () -> Unit,
    onDismissEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val weights = listOf(stringResource(R.string.title_font_weight_follow)) + stringArrayResource(R.array.text_font_weight)
    val headers = listOf(stringResource(R.string.hide_when_status_bar_show), stringResource(R.string.show), stringResource(R.string.hide))
    val footers = listOf(stringResource(R.string.show), stringResource(R.string.hide))
    val colors = stringArrayResource(R.array.tip_color).toList()
    val dividerColors = stringArrayResource(R.array.tip_divider_color).toList()
    fun colorLabel(setting: TipSetting): String = when (val value = settings[setting]) {
        0 -> if (setting == TipSetting.DividerColor) dividerColors[1] else colors.first()
        -1 -> if (setting == TipSetting.DividerColor) dividerColors.first() else "#%06X".format(value and 0xffffff)
        else -> "#%06X".format(value and 0xffffff)
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            TipSection(R.string.body_title)
            Column(Modifier.selectableGroup()) {
                listOf(0 to R.string.title_left, 1 to R.string.title_center, 3 to R.string.right, 2 to R.string.title_hide).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tip-title-mode-$value")
                        .selectable(settings[TipSetting.TitleMode] == value, role = Role.RadioButton) { onSetting(TipSetting.TitleMode, value) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings[TipSetting.TitleMode] == value, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
            }
            TipSlider(R.string.title_font_size, TipSetting.TitleSize, settings[TipSetting.TitleSize], 0, 20, onSetting)
            TipSlider(R.string.title_line_spacing, TipSetting.TitleLineSpacing, settings[TipSetting.TitleLineSpacing], -20, 30, onSetting,
                (settings[TipSetting.TitleLineSpacing] / 10f).toString())
            val font = settings.titleFont.takeIf { it.isNotEmpty() }?.let { Uri.decode(it).substringAfterLast('/').substringAfterLast('\\').ifBlank { it } }
                ?: stringResource(R.string.follow_text_font)
            TipRow("tip-title-font", R.string.title_font, font, onFont)
            TipRow("tip-title-weight", R.string.title_font_weight, weights[(settings[TipSetting.TitleBold] + 1).coerceIn(weights.indices)]) { onSelector(TipSetting.TitleBold) }
            TipRow("tip-title-color", R.string.title_color, colorLabel(TipSetting.TitleColor)) { onSelector(TipSetting.TitleColor) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tip-split-title")
                .toggleable(settings[TipSetting.SplitTitle] != 0, role = Role.Switch) { onSetting(TipSetting.SplitTitle, if (it) 1 else 0) },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.split_chapter_title), Modifier.weight(1f))
                Switch(settings[TipSetting.SplitTitle] != 0, onCheckedChange = null)
            }
            if (settings[TipSetting.SplitTitle] != 0) {
                TipSlider(R.string.title_number_font_size, TipSetting.TitleNumberSize, settings[TipSetting.TitleNumberSize], 0, 20, onSetting)
                TipSlider(R.string.title_number_spacing, TipSetting.TitleNumberSpacing, settings[TipSetting.TitleNumberSpacing], -50, 100, onSetting)
                TipRow("tip-number-color", R.string.title_number_color, colorLabel(TipSetting.TitleNumberColor)) { onSelector(TipSetting.TitleNumberColor) }
            }
            TipSlider(R.string.title_margin_top, TipSetting.TitleTop, settings[TipSetting.TitleTop], 0, 100, onSetting)
            TipSlider(R.string.title_margin_bottom, TipSetting.TitleBottom, settings[TipSetting.TitleBottom], 0, 100, onSetting)
            TipSection(R.string.header)
            TipRow("tip-header-mode", R.string.show_hide, headers[settings[TipSetting.HeaderMode].coerceIn(headers.indices)]) { onSelector(TipSetting.HeaderMode) }
            listOf(TipTemplateSlot.HeaderLeft to R.string.left, TipTemplateSlot.HeaderMiddle to R.string.middle, TipTemplateSlot.HeaderRight to R.string.right).forEach { (slot, label) ->
                TipRow("tip-template-${slot.name}", label, settings.templates[slot].orEmpty()) { onTemplate(slot) }
            }
            TipSection(R.string.footer)
            TipRow("tip-footer-mode", R.string.show_hide, footers[settings[TipSetting.FooterMode].coerceIn(footers.indices)]) { onSelector(TipSetting.FooterMode) }
            listOf(TipTemplateSlot.FooterLeft to R.string.left, TipTemplateSlot.FooterMiddle to R.string.middle, TipTemplateSlot.FooterRight to R.string.right).forEach { (slot, label) ->
                TipRow("tip-template-${slot.name}", label, settings.templates[slot].orEmpty()) { onTemplate(slot) }
            }
            TipSection(R.string.header_footer)
            TipSlider(R.string.text_size, TipSetting.TipSize, settings[TipSetting.TipSize], 5, 50, onSetting)
            TipRow("tip-text-color", R.string.text_color, colorLabel(TipSetting.TipColor)) { onSelector(TipSetting.TipColor) }
            TipRow("tip-divider-color", R.string.tip_divider_color, colorLabel(TipSetting.DividerColor)) { onSelector(TipSetting.DividerColor) }
        }
    }
    state.selector?.let { setting ->
        val options = when (setting) {
            TipSetting.TitleBold -> weights.mapIndexed { index, label -> index - 1 to label }
            TipSetting.HeaderMode -> headers.mapIndexed { index, label -> index to label }
            TipSetting.FooterMode -> footers.mapIndexed { index, label -> index to label }
            TipSetting.DividerColor -> dividerColors.mapIndexed { index, label -> index - 1 to label }
            else -> colors.mapIndexed { index, label -> index to label }
        }
        AlertDialog(onDismissRequest = onDismissEditor,
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, (value, label) ->
                    Text(label, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tip-select-$index")
                        .clickable {
                            val customColor = setting in listOf(TipSetting.TitleColor, TipSetting.TitleNumberColor, TipSetting.TipColor, TipSetting.DividerColor) && index == options.lastIndex
                            if (customColor) onColor(setting) else onSelect(value)
                        }.padding(12.dp))
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onDismissEditor) { Text(stringResource(R.string.cancel)) } })
    }
    state.template?.let { editor ->
        TipTemplateEditorDialog(editor, onTemplateText, onPlaceholder, onConfirmTemplate, onDismissEditor)
    }
    state.color?.let { editor ->
        TipColorEditorDialog(editor, onColorText, onColorChannel, onConfirmColor, onDismissEditor)
    }
}

@Composable private fun TipSection(label: Int) {
    Text(stringResource(label), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
}

@Composable private fun TipRow(tag: String, label: Int, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Text(value, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable private fun TipSlider(label: Int, setting: TipSetting, value: Int, min: Int, max: Int,
    onChange: (TipSetting, Int) -> Unit, displayValue: String = value.toString()) {
    val labelText = stringResource(label)
    val plusDescription = "$labelText ${stringResource(R.string.plus)}"
    val minusDescription = "$labelText ${stringResource(R.string.reduce)}"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row { Text(labelText, Modifier.weight(1f)); Text(displayValue, Modifier.testTag("tip-value-${setting.name}")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onChange(setting, (value - 1).coerceIn(min, max)) },
                Modifier.testTag("tip-minus-${setting.name}"), enabled = value > min) {
                Icon(painterResource(R.drawable.ic_reduce), contentDescription = minusDescription)
            }
            Slider(value.coerceIn(min, max).toFloat(), { onChange(setting, it.roundToInt()) },
                modifier = Modifier.weight(1f).testTag("tip-slider-${setting.name}")
                    .semantics { contentDescription = labelText },
                valueRange = min.toFloat()..max.toFloat(), steps = max - min - 1)
            IconButton({ onChange(setting, (value + 1).coerceIn(min, max)) },
                Modifier.testTag("tip-plus-${setting.name}"), enabled = value < max) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = plusDescription)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TipTemplateEditorDialog(editor: TipTemplateEditor,
    onEdit: (String, Int, Int) -> Unit, onPlaceholder: (String) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Keep IME composing spans in memory while persisting only text/selection for recreation.
    var composingValue by remember(editor.slot) { mutableStateOf(TextFieldValue(editor.text,
        TextRange(editor.selectionStart, editor.selectionEnd))) }
    val editorValue = if (composingValue.text == editor.text &&
        composingValue.selection == TextRange(editor.selectionStart, editor.selectionEnd)) composingValue
        else TextFieldValue(editor.text, TextRange(editor.selectionStart, editor.selectionEnd))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reader_info_template)) },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(editorValue,
                    { composingValue = it; onEdit(it.text, it.selection.start, it.selection.end) },
                    modifier = Modifier.fillMaxWidth().testTag("tip-template-editor"), singleLine = true,
                    placeholder = { Text(stringResource(R.string.reader_info_template_hint)) })
                Text(stringResource(R.string.reader_info_placeholders), Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReaderInfoTemplate.placeholders.forEach { placeholder ->
                        AssistChip(onClick = { onPlaceholder(placeholder) }, label = { Text(placeholder) },
                            modifier = Modifier.testTag("tip-placeholder-$placeholder"))
                    }
                }
            }
        }, confirmButton = { TextButton(onConfirm, Modifier.testTag("tip-template-confirm")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("tip-template-cancel")) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun TipColorEditorDialog(editor: TipColorEditor, onHex: (String) -> Unit,
    onChannel: (Int, Int) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val parsed = editor.hex.takeIf { it.length == 6 }?.toIntOrNull(16)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.text_color)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(32.dp).background(Color((parsed ?: 0) or 0xff000000.toInt())))
            OutlinedTextField(editor.hex, onHex, Modifier.fillMaxWidth().testTag("tip-color-hex"),
                singleLine = true, label = { Text("#RRGGBB") }, isError = parsed == null)
            listOf("R", "G", "B").forEachIndexed { index, label ->
                val value = ((parsed ?: 0) shr ((2 - index) * 8)) and 0xff
                Row { Text(label, Modifier.weight(1f)); Text(value.toString()) }
                Slider(value.toFloat(), { onChannel(index, it.roundToInt()) },
                    Modifier.testTag("tip-color-$label").semantics { contentDescription = label }, enabled = parsed != null, valueRange = 0f..255f, steps = 254)
            }
        } }, confirmButton = { TextButton(onConfirm, Modifier.testTag("tip-color-confirm"), enabled = parsed != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("tip-color-cancel")) { Text(stringResource(R.string.cancel)) } })
}
