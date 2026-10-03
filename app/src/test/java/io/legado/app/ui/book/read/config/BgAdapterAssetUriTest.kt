package io.legado.app.ui.book.read.config

import io.legado.app.data.preferences.BgTextSetting
import io.legado.app.data.preferences.bgTextUpdate
import io.legado.app.help.config.parseReadConfigObject
import org.junit.Assert.*
import org.junit.Test

class BgAdapterAssetUriTest {
    @Test
    fun `Compose preview descriptor keeps the same asset in all theme modes`() {
        val config =
            parseReadConfigObject(backgroundAssetConfiguration("paper.nine.png")).getOrThrow()
        assertEquals("paper.nine.png", config.bgStr)
        assertEquals("paper.nine.png", config.bgStrNight)
        assertEquals("paper.nine.png", config.bgStrEInk)
        assertEquals(1, config.bgType)
        assertEquals(1, config.bgTypeNight)
        assertEquals(1, config.bgTypeEInk)
    }

    @Test
    fun `asset selection keeps the background-only refresh payload`() {
        val update = bgTextUpdate(BgTextSetting.AssetBackground)
        assertEquals(listOf(1), update.codes)
        assertFalse(update.actionBar)
        assertFalse(update.reviewCache)
        assertFalse(update.systemUi)
    }
}
