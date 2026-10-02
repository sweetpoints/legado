package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TitleFontTest {

    @Test
    fun `legacy config inherits the text font`() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>(
            """{"textFont":"body.ttf"}"""
        ).getOrThrow()

        assertEquals("", config.titleFont)
        assertEquals("", config.toMap()["titleFont"])
    }

    @Test
    fun `title font survives json round trip`() {
        val config = ReadBookConfig.Config(
            textFont = "body.ttf",
            titleFont = "title.ttf",
        )
        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(
            GSON.toJson(config)
        ).getOrThrow()

        assertEquals("title.ttf", restored.titleFont)
        assertEquals("title.ttf", restored.toMap()["titleFont"])
    }

    @Test
    fun `title settings restore font inheritance without changing body selection`() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.setFont("title.ttf")
        assertEquals("title.ttf", model.state.value.settings.titleFont)
        model.setFont("")
        assertEquals("", model.state.value.settings.titleFont)
        assertEquals(listOf("title.ttf", ""), repository.fontsWritten)
        assertEquals(emptyList<Pair<TipSetting, Int>>(), repository.settingsWritten)
    }

    @Test
    fun `renderer and config transfer keep title and body fonts separate`() {
        val config = readProjectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        )
        val provider = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        )
        val configDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgTextConfigDialog.kt"
        )

        assertTrue(config.contains("config.titleFont.ifEmpty { config.textFont }"))
        assertTrue(config.contains("exportConfig.titleFont = shareConfig.titleFont"))
        assertTrue(config.contains("config.titleFont = importFont(config.titleFont)"))
        assertTrue(provider.contains("getPaints(titleTypeface, typeface)"))
        assertTrue(provider.contains("ReadBookConfig.titleFont = \"\""))
        assertTrue(configDialog.contains("val titleFontPath = ReadBookConfig.titleFont"))
        assertTrue(configDialog.contains("config.titleFont = if (titleFontPath == textFontPath"))
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
