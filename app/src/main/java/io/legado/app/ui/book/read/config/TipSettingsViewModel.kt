package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.TipSetting
import io.legado.app.data.preferences.TipSettingsRepository
import io.legado.app.data.preferences.TipSettingsSnapshot
import io.legado.app.data.preferences.TipTemplateSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TipTemplateEditor(
    val slot: TipTemplateSlot,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

data class TipColorEditor(val setting: TipSetting, val hex: String)

data class TipSettingsUiState(
    val settings: TipSettingsSnapshot,
    val selector: TipSetting? = null,
    val template: TipTemplateEditor? = null,
    val color: TipColorEditor? = null,
)

class TipSettingsViewModel(
    private val repository: TipSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            TipSettingsUiState(
                repository.load(),
                selector =
                    savedState.get<String>("tip.selector")?.let {
                        runCatching { TipSetting.valueOf(it) }.getOrNull()
                    },
                template =
                    savedState.get<String>("tip.template.slot")?.let { slot ->
                        runCatching {
                            val text = savedState.get<String>("tip.template.text").orEmpty()
                            TipTemplateEditor(
                                TipTemplateSlot.valueOf(slot),
                                text,
                                (savedState.get<Int>("tip.template.start") ?: text.length).coerceIn(
                                    0,
                                    text.length,
                                ),
                                (savedState.get<Int>("tip.template.end") ?: text.length).coerceIn(
                                    0,
                                    text.length,
                                ),
                            )
                        }
                            .getOrNull()
                    },
                color =
                    savedState.get<String>("tip.color.setting")?.let {
                        runCatching {
                            TipColorEditor(
                                TipSetting.valueOf(it),
                                savedState.get<String>("tip.color.hex") ?: "000000",
                            )
                        }
                            .getOrNull()
                    },
            )
        )
    val state = mutableState.asStateFlow()

    fun refresh() {
        mutableState.value = state.value.copy(settings = repository.load())
    }

    fun set(setting: TipSetting, value: Int) {
        val bounded =
            when (setting) {
                TipSetting.TitleMode -> value.coerceIn(0, 3)
                TipSetting.TitleSize,
                TipSetting.TitleNumberSize -> value.coerceIn(0, 20)
                TipSetting.TitleLineSpacing -> value.coerceIn(-20, 30)
                TipSetting.TitleNumberSpacing -> value.coerceIn(-50, 100)
                TipSetting.TitleTop,
                TipSetting.TitleBottom -> value.coerceIn(0, 100)
                TipSetting.TitleBold -> value.coerceIn(-1, 2)
                TipSetting.HeaderMode -> value.coerceIn(0, 2)
                TipSetting.FooterMode,
                TipSetting.SplitTitle -> value.coerceIn(0, 1)
                TipSetting.TipSize -> value.coerceIn(5, 50)
                else -> value
            }
        if (state.value.settings[setting] == bounded) return
        repository.set(setting, bounded)
        refresh()
    }

    fun setFont(path: String) {
        if (state.value.settings.titleFont == path && path.isNotEmpty()) return
        repository.setFont(path)
        refresh()
    }

    fun openSelector(setting: TipSetting) {
        dismissEditors()
        mutableState.value = state.value.copy(selector = setting)
        savedState["tip.selector"] = setting.name
    }

    fun select(value: Int) {
        val setting = state.value.selector ?: return
        set(setting, value)
        dismissEditors()
    }

    fun openColor(setting: TipSetting) {
        val settings = state.value.settings
        val current =
            when (setting) {
                TipSetting.TitleColor -> settings.effectiveTitleColor
                TipSetting.TitleNumberColor -> settings.effectiveNumberColor
                else -> settings[setting].takeIf { it != 0 && it != -1 } ?: 0xff000000.toInt()
            }
        dismissEditors()
        mutableState.value =
            state.value.copy(color = TipColorEditor(setting, "%06X".format(current and 0xffffff)))
        saveColor()
    }

    fun editColor(hex: String) {
        val editor = state.value.color ?: return
        mutableState.value =
            state.value.copy(color = editor.copy(hex = hex.removePrefix("#").take(6)))
        saveColor()
    }

    fun setColorChannel(channel: Int, value: Int) {
        if (channel !in 0..2) return
        val color = parsedColor() ?: return
        val shift = (2 - channel) * 8
        val next = (color and (0xff shl shift).inv()) or (value.coerceIn(0, 255) shl shift)
        editColor("%06X".format(next and 0xffffff))
    }

    fun parsedColor(): Int? =
        state.value.color
            ?.hex
            ?.takeIf { it.length == 6 }
            ?.toIntOrNull(16)
            ?.let { it or 0xff000000.toInt() }

    fun confirmColor() {
        val editor = state.value.color ?: return
        val color = parsedColor() ?: return
        set(editor.setting, color)
        dismissEditors()
    }

    private fun saveColor() {
        savedState["tip.color.setting"] = state.value.color?.setting?.name
        savedState["tip.color.hex"] = state.value.color?.hex
    }

    fun openTemplate(slot: TipTemplateSlot) {
        dismissEditors()
        val text = state.value.settings.templates[slot].orEmpty()
        mutableState.value =
            state.value.copy(template = TipTemplateEditor(slot, text, text.length, text.length))
        saveTemplate()
    }

    fun editTemplate(text: String, start: Int, end: Int) {
        val editor = state.value.template ?: return
        mutableState.value =
            state.value.copy(
                template =
                    editor.copy(
                        text = text,
                        selectionStart = start.coerceIn(0, text.length),
                        selectionEnd = end.coerceIn(0, text.length),
                    )
            )
        saveTemplate()
    }

    fun insertPlaceholder(placeholder: String) {
        val editor = state.value.template ?: return
        val start = minOf(editor.selectionStart, editor.selectionEnd)
        val end = maxOf(editor.selectionStart, editor.selectionEnd)
        val text = editor.text.replaceRange(start, end, placeholder)
        val selection = start + placeholder.length
        editTemplate(text, selection, selection)
    }

    fun confirmTemplate() {
        val editor = state.value.template ?: return
        repository.setTemplate(editor.slot, editor.text)
        refresh()
        dismissEditors()
    }

    private fun saveTemplate() {
        val editor = state.value.template
        savedState["tip.template.slot"] = editor?.slot?.name
        savedState["tip.template.text"] = editor?.text
        savedState["tip.template.start"] = editor?.selectionStart
        savedState["tip.template.end"] = editor?.selectionEnd
    }

    fun dismissEditors() {
        mutableState.value = state.value.copy(selector = null, template = null, color = null)
        savedState["tip.selector"] = null as String?
        saveTemplate()
        saveColor()
    }
}
