package io.legado.app.data.repository

import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.*
import io.legado.app.help.HighlightStyles

/** Pure style operations. The dialog's StyleHost owns live reader/editor persistence. */
internal enum class HighlightChannel { Fill, Text, Bold, Italic, Underline, Strike, Box, Emphasis, Shadow }
internal class HighlightStyleRepository {
    val presets: List<HighlightStyle> get() = HighlightStyles.presets.toList()
    fun enabled(style: HighlightStyle, channel: HighlightChannel): Boolean = when (channel) {
        HighlightChannel.Fill -> style.fill != 0
        HighlightChannel.Text -> style.textColor != 0
        HighlightChannel.Bold -> style.bold
        HighlightChannel.Italic -> style.italic
        HighlightChannel.Underline -> style.underline != null
        HighlightChannel.Strike -> style.strike != null
        HighlightChannel.Box -> style.box != null
        HighlightChannel.Emphasis -> style.emphasis != null
        HighlightChannel.Shadow -> style.shadow != null
    }
    fun color(style: HighlightStyle, channel: HighlightChannel): Int = when (channel) {
        HighlightChannel.Fill -> style.fill
        HighlightChannel.Text -> style.textColor
        HighlightChannel.Underline -> style.underline?.color ?: 0
        HighlightChannel.Strike -> style.strike?.color ?: 0
        HighlightChannel.Box -> style.box?.color ?: 0
        HighlightChannel.Emphasis -> style.emphasis?.color ?: 0
        HighlightChannel.Shadow -> style.shadow?.color ?: 0
        else -> 0
    }
    fun toggle(style: HighlightStyle, channel: HighlightChannel, enabled: Boolean): HighlightStyle = when (channel) {
        HighlightChannel.Fill -> style.copy(fill = if (enabled) style.fill.takeIf { it != 0 } ?: DEFAULT_FILL else 0)
        HighlightChannel.Text -> style.copy(textColor = if (enabled) style.textColor.takeIf { it != 0 } ?: DEFAULT_TEXT else 0)
        HighlightChannel.Bold -> style.copy(bold = enabled)
        HighlightChannel.Italic -> style.copy(italic = enabled)
        HighlightChannel.Underline -> style.copy(underline = if (enabled) style.underline ?: Underline() else null)
        HighlightChannel.Strike -> style.copy(strike = if (enabled) style.strike ?: Deco() else null)
        HighlightChannel.Box -> style.copy(box = if (enabled) style.box ?: Deco() else null)
        HighlightChannel.Emphasis -> style.copy(emphasis = if (enabled) style.emphasis ?: Deco() else null)
        HighlightChannel.Shadow -> style.copy(shadow = if (enabled) style.shadow ?: Shadow() else null)
    }
    fun applyColor(style: HighlightStyle, channel: HighlightChannel, color: Int): HighlightStyle = when (channel) {
        HighlightChannel.Fill -> style.copy(fill = color)
        HighlightChannel.Text -> style.copy(textColor = color)
        HighlightChannel.Underline -> style.copy(underline = (style.underline ?: Underline()).copy(color = color))
        HighlightChannel.Strike -> style.copy(strike = Deco(color))
        HighlightChannel.Box -> style.copy(box = Deco(color))
        HighlightChannel.Emphasis -> style.copy(emphasis = Deco(color))
        HighlightChannel.Shadow -> style.copy(shadow = (style.shadow ?: Shadow()).copy(color = color))
        else -> style
    }
    fun cycle(style: HighlightStyle, channel: HighlightChannel): HighlightStyle = when (channel) {
        HighlightChannel.Fill -> style.copy(fillShape = FillShape.entries.let { it[(it.indexOf(style.resolvedFillShape) + 1) % it.size] })
        HighlightChannel.Underline -> (style.underline ?: Underline()).let { current ->
            style.copy(underline = current.copy(kind = Kind.entries.let { it[(it.indexOf(current.kind) + 1) % it.size] }))
        }
        else -> style
    }
    fun swatch(style: HighlightStyle): Int = style.fill.takeIf { it != 0 }
        ?: style.textColor.takeIf { it != 0 } ?: style.underline?.color?.takeIf { it != 0 }
        ?: style.strike?.color?.takeIf { it != 0 } ?: style.box?.color?.takeIf { it != 0 }
        ?: style.emphasis?.color?.takeIf { it != 0 } ?: style.shadow?.color?.takeIf { it != 0 } ?: DEFAULT_SWATCH
    fun fontSize(style: HighlightStyle, value: Int?): HighlightStyle = style.copy(fontSize = value?.coerceIn(5, 100)?.toFloat())
    fun letterSpacing(style: HighlightStyle, percent: Int?): HighlightStyle = style.copy(letterSpacing = percent?.coerceIn(-50, 100)?.div(100f))
    fun pillPadding(style: HighlightStyle, percent: Int?): HighlightStyle = style.copy(pillPaddingScale = percent?.coerceIn(25, 200)?.div(100f))
    companion object {
        val DEFAULT_FILL = 0x80FFF176.toInt()
        val DEFAULT_TEXT = 0xFFE53935.toInt()
        val DEFAULT_SWATCH = 0xFF888888.toInt()
    }
}
