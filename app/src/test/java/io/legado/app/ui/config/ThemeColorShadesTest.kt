package io.legado.app.ui.config

import io.legado.app.model.theme.themeColorShades
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorShadesTest {
    @Test
    fun twelveLegacyShadePercentagesPreserveAlphaAndRoundChannelsIndependently() {
        val shades = themeColorShades(0xff123456.toInt())
        assertEquals(12, shades.size)
        assertEquals(0xffe7ebee.toInt(), shades.first())
        assertEquals(0xff040c13.toInt(), shades.last())
        assertEquals(List(7) { 0xff000000.toInt() }, themeColorShades(0xff000000.toInt()).drop(5))
        assertEquals(0x80e7ebee.toInt(), themeColorShades(0x80123456.toInt()).first())
    }
}
