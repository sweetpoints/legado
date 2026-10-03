package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TipTextSizeTest {

    @Test
    fun `legacy config keeps the previous twelve sp size`() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>("{}").getOrThrow()

        assertEquals(12, config.tipTextSize)
    }

    @Test
    fun `text size survives json and map export`() {
        val config = ReadBookConfig.Config(tipTextSize = 24)
        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(GSON.toJson(config)).getOrThrow()

        assertEquals(24, restored.tipTextSize)
        assertEquals(24, config.toMap()["tipTextSize"])
    }

    @Test
    fun `text size maps to seek bar progress`() {
        assertEquals(0, tipTextSizeToProgress(5))
        assertEquals(7, tipTextSizeToProgress(12))
        assertEquals(45, tipTextSizeToProgress(50))
        assertEquals(0, tipTextSizeToProgress(4))
        assertEquals(45, tipTextSizeToProgress(51))
    }

    @Test
    fun `seek bar progress maps to text size`() {
        assertEquals(5, tipTextSizeFromProgress(0))
        assertEquals(12, tipTextSizeFromProgress(7))
        assertEquals(50, tipTextSizeFromProgress(45))
        assertEquals(5, tipTextSizeFromProgress(-1))
        assertEquals(50, tipTextSizeFromProgress(46))
    }

    @Test
    fun `shared layout and reader info use text size`() {
        val config = readProjectFile("src/main/java/io/legado/app/help/config/ReadBookConfig.kt")
        val pageView = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/PageView.kt")

        assertTrue(config.contains("exportConfig.tipTextSize = shareConfig.tipTextSize"))
        assertTrue(pageView.contains("tipTextSize = ReadTipConfig.tipTextSize"))
        assertTrue(pageView.contains("textSizeSp = tipTextSize"))
        val readerInfo =
            readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ComposeReaderInfo.kt")
        assertTrue(readerInfo.contains("with(density) { textSizeSp.sp.toPx() }"))
        assertTrue(readerInfo.contains("textSize = textSizePx"))
    }

    @Test
    fun `text size control persists its actual sp range`() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.set(TipSetting.TipSize, 4)
        assertEquals(5, repository.snapshot[TipSetting.TipSize])
        model.set(TipSetting.TipSize, 12)
        assertEquals(12, repository.snapshot[TipSetting.TipSize])
        model.set(TipSetting.TipSize, 51)
        assertEquals(50, repository.snapshot[TipSetting.TipSize])
    }

    private fun readProjectFile(pathInApp: String): String {
        return projectFile(pathInApp).readText()
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
