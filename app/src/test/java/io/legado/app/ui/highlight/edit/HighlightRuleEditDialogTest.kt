package io.legado.app.ui.highlight.edit

import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyles
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.utils.GSON
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightRuleEditDialogTest {

    @Test
    fun `color picker uses the requested channel presets`() {
        val textConfig =
            HighlightRuleEditDialog.colorPickerConfig(
                HighlightStyleDialog.HL_TEXT,
                initial = 0,
                withAlpha = false,
            )
        val fillConfig =
            HighlightRuleEditDialog.colorPickerConfig(
                HighlightStyleDialog.HL_FILL,
                initial = 0,
                withAlpha = true,
            )

        assertEquals(HighlightColors.text.first(), textConfig.color)
        assertArrayEquals(HighlightColors.text, textConfig.presets)
        assertEquals(HighlightColors.bg.first(), fillConfig.color)
        assertArrayEquals(HighlightColors.bg, fillConfig.presets)
    }

    @Test
    fun `color picker keeps an existing color`() {
        val color = 0xFF123456.toInt()

        val config =
            HighlightRuleEditDialog.colorPickerConfig(
                HighlightStyleDialog.HL_FILL,
                color,
                withAlpha = true,
            )

        assertEquals(color, config.color)
    }

    @Test
    fun `new rules default to the first visible highlight preset`() {
        assertEquals(
            GSON.toJson(HighlightStyles.presets.first()),
            HighlightRuleEditDialog.initialStyle(null),
        )
    }

    @Test
    fun `new rules keep a supplied source highlight style`() {
        val sourceStyle = "{\"fill\":1}"

        assertEquals(sourceStyle, HighlightRuleEditDialog.initialStyle(sourceStyle))
    }
}
