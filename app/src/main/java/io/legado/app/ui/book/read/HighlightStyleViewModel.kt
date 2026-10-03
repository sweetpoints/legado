package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.data.repository.HighlightStyleRepository
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HighlightStyleAction {
    Apply,
    Color,
    Font,
    Shadow,
    Underline,
}

internal enum class HighlightNumber {
    FontSize,
    LetterSpacing,
    PillPadding,
}

internal data class HighlightStyleEffect(
    val id: Long,
    val action: HighlightStyleAction,
    val style: HighlightStyle,
    val channel: HighlightChannel? = null,
    val fontChanged: Boolean = false,
)

internal data class HighlightNumberDraft(
    val setting: HighlightNumber,
    val value: Int,
    val text: String = value.toString(),
)

internal data class HighlightStyleState(
    val style: HighlightStyle = HighlightStyle(),
    val initialized: Boolean = false,
    val effects: List<HighlightStyleEffect> = emptyList(),
    val number: HighlightNumberDraft? = null,
)

/** The state owns values only. Live reader/editor callbacks stay on the resumed Compose host. */
internal class HighlightStyleViewModel(
    private val saved: SavedStateHandle,
    private val repository: HighlightStyleRepository = HighlightStyleRepository(),
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            HighlightStyleState(
                saved.get<String>("style")?.let {
                    GSON.fromJsonObject<HighlightStyle>(it).getOrNull()
                } ?: HighlightStyle(),
                saved["initialized"] ?: false,
                saved
                    .get<String>("effects")
                    ?.let { GSON.fromJsonArray<HighlightStyleEffect>(it).getOrNull() }
                    .orEmpty(),
                saved.get<String>("number")?.let {
                    GSON.fromJsonObject<HighlightNumberDraft>(it).getOrNull()
                },
            )
        )
    val state = mutable.asStateFlow()
    private var effectId = saved.get<Long>("effectId") ?: 0L
    val presets
        get() = repository.presets

    fun attach(style: HighlightStyle) {
        if (
            !state.value.initialized ||
                state.value.effects.none { it.action == HighlightStyleAction.Apply }
        )
            acceptHost(style)
    }

    fun acceptHost(style: HighlightStyle) {
        if (state.value.effects.any { it.action == HighlightStyleAction.Apply }) return
        update(state.value.copy(style = style, initialized = true))
    }

    /** Public refresh is an authoritative external edit (e.g. the host color picker result). */
    fun refresh(style: HighlightStyle) {
        update(
            state.value.copy(
                style = style,
                initialized = true,
                effects = state.value.effects.filterNot { it.action == HighlightStyleAction.Apply },
            )
        )
    }

    private fun update(value: HighlightStyleState) {
        mutable.value = value
        saved["style"] = GSON.toJson(value.style)
        saved["initialized"] = value.initialized
        saved["effects"] = GSON.toJson(value.effects)
        saved["number"] = value.number?.let { GSON.toJson(it) }
    }

    private fun effect(
        action: HighlightStyleAction,
        channel: HighlightChannel? = null,
        fontChanged: Boolean = false,
    ) {
        effectId++
        saved["effectId"] = effectId
        update(
            state.value.copy(
                effects =
                    state.value.effects +
                        HighlightStyleEffect(
                            effectId,
                            action,
                            state.value.style,
                            channel,
                            fontChanged,
                        )
            )
        )
    }

    private fun apply(style: HighlightStyle, fontChanged: Boolean = false) {
        update(state.value.copy(style = style, initialized = true))
        effect(HighlightStyleAction.Apply, fontChanged = fontChanged)
    }

    fun consume(id: Long) {
        update(state.value.copy(effects = state.value.effects.filterNot { it.id == id }))
    }

    fun preset(index: Int) {
        presets.getOrNull(index)?.let { apply(it) }
    }

    fun toggle(channel: HighlightChannel, enabled: Boolean) {
        val previous = state.value.style
        val next = repository.toggle(previous, channel, enabled)
        apply(next)
        if (channel == HighlightChannel.Shadow && previous.shadow == null && next.shadow != null)
            effect(HighlightStyleAction.Shadow)
    }

    fun color(channel: HighlightChannel) {
        if (
            channel != HighlightChannel.Bold &&
                channel != HighlightChannel.Italic &&
                repository.enabled(state.value.style, channel)
        )
            effect(HighlightStyleAction.Color, channel)
    }

    fun extra(channel: HighlightChannel) {
        if (!repository.enabled(state.value.style, channel)) return
        if (channel == HighlightChannel.Shadow) effect(HighlightStyleAction.Shadow)
        else if (channel == HighlightChannel.Fill || channel == HighlightChannel.Underline)
            apply(repository.cycle(state.value.style, channel))
    }

    fun tune(channel: HighlightChannel) {
        if (!repository.enabled(state.value.style, channel)) return
        when (channel) {
            HighlightChannel.Underline -> effect(HighlightStyleAction.Underline)
            HighlightChannel.Fill ->
                if (state.value.style.resolvedFillShape == HighlightStyle.FillShape.PILL)
                    number(HighlightNumber.PillPadding, 100)
            else -> Unit
        }
    }

    fun font() {
        effect(HighlightStyleAction.Font)
    }

    fun selectFont(path: String) {
        apply(state.value.style.copy(fontPath = path), fontChanged = true)
    }

    fun shadow(value: HighlightStyle.Shadow) {
        apply(state.value.style.copy(shadow = value))
    }

    fun underline(value: HighlightStyle.Underline) {
        apply(state.value.style.copy(underline = value))
    }

    fun number(setting: HighlightNumber, fallback: Int) {
        val style = state.value.style
        val value =
            when (setting) {
                HighlightNumber.FontSize ->
                    style.resolvedFontSize?.let { it.roundToInt() } ?: fallback
                HighlightNumber.LetterSpacing ->
                    style.resolvedLetterSpacing?.let { (it * 100).roundToInt() } ?: fallback
                HighlightNumber.PillPadding -> (style.resolvedPillPaddingScale * 100).roundToInt()
            }.coerceIn(range(setting))
        update(state.value.copy(number = HighlightNumberDraft(setting, value)))
    }

    fun numberText(text: String) {
        val draft = state.value.number ?: return
        if (text.isEmpty() || text == "-" && range(draft.setting).first < 0) {
            update(state.value.copy(number = draft.copy(text = text)))
            return
        }
        val number = text.toIntOrNull() ?: return
        val value = number.coerceIn(range(draft.setting))
        update(state.value.copy(number = draft.copy(value = value, text = value.toString())))
    }

    fun numberValue(value: Int) {
        val draft = state.value.number ?: return
        val number = value.coerceIn(range(draft.setting))
        update(state.value.copy(number = draft.copy(value = number, text = number.toString())))
    }

    fun dismissNumber() {
        update(state.value.copy(number = null))
    }

    fun saveNumber(inherit: Boolean = false) {
        val draft = state.value.number ?: return
        if (!inherit && draft.text.toIntOrNull() == null) return
        val value = if (inherit) null else draft.value
        val next =
            when (draft.setting) {
                HighlightNumber.FontSize -> repository.fontSize(state.value.style, value)
                HighlightNumber.LetterSpacing -> repository.letterSpacing(state.value.style, value)
                HighlightNumber.PillPadding -> repository.pillPadding(state.value.style, value)
            }
        dismissNumber()
        apply(next)
    }

    companion object {
        fun range(setting: HighlightNumber) =
            when (setting) {
                HighlightNumber.FontSize -> 5..100
                HighlightNumber.LetterSpacing -> -50..100
                HighlightNumber.PillPadding -> 25..200
            }
    }
}
