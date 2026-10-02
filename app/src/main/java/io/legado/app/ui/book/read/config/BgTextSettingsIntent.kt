package io.legado.app.ui.book.read.config

import io.legado.app.data.preferences.BgTextColor
import io.legado.app.data.preferences.BgTextTemplate

internal sealed interface BgTextSettingsIntent {
    data class Slider(val slider: BgTextSlider, val value: Int) : BgTextSettingsIntent
    data object AlphaFinished : BgTextSettingsIntent
    data class DarkStatus(val value: Boolean) : BgTextSettingsIntent
    data class UnderlineBody(val value: Boolean) : BgTextSettingsIntent
    data class UnderlineTitle(val value: Boolean) : BgTextSettingsIntent
    data class UnderlineMode(val value: Int) : BgTextSettingsIntent
    data class Asset(val name: String) : BgTextSettingsIntent
    data class Picker(val action: BgTextAction) : BgTextSettingsIntent
    data class Editor(val kind: BgTextEditorKind) : BgTextSettingsIntent
    data object CancelEditor : BgTextSettingsIntent
    data object ConfirmEditor : BgTextSettingsIntent
    data class Text(val text: String, val start: Int, val end: Int) : BgTextSettingsIntent
    data class Default(val index: Int) : BgTextSettingsIntent
    data object DeletePreset : BgTextSettingsIntent
    data class Color(val color: BgTextColor) : BgTextSettingsIntent
    data class ColorChannel(val channel: Int, val value: Int) : BgTextSettingsIntent
    data object ResetReviewColor : BgTextSettingsIntent
    data class SaveTemplate(val defaultName: String, val template: BgTextTemplate? = null) : BgTextSettingsIntent
    data class ApplyTemplate(val template: BgTextTemplate) : BgTextSettingsIntent
    data class DeleteTemplateEditor(val template: BgTextTemplate) : BgTextSettingsIntent
    data object DeleteTemplate : BgTextSettingsIntent
}
