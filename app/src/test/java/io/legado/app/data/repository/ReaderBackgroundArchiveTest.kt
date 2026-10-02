package io.legado.app.data.repository

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.utils.GSON
import java.io.File
import java.io.FilterInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderBackgroundArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun pack(config: ReadBookConfig.Config, first: File?, second: File?, backgrounds: List<File> = emptyList()): File = runBlocking {
        packReaderBackgroundArchive(ReaderBackgroundExportSnapshot(GSON.toJson(config), config.name,
            first?.absolutePath.orEmpty(), second?.absolutePath.orEmpty(), backgrounds.map { it.absolutePath }),
            temporary.newFolder()) { path -> File(path).takeIf { it.exists() }?.let { it.name to it.inputStream() } }
    }
    @Test fun realZipPreservesBothFontStreamsWithCollidingNamesAndAllBackgrounds() {
        val first = File(temporary.newFolder(), "font.ttf").apply { writeBytes(byteArrayOf(1, 2)) }
        val second = File(temporary.newFolder(), "font.ttf").apply { writeBytes(byteArrayOf(3, 4)) }
        val day = temporary.newFile("day.png").apply { writeBytes(byteArrayOf(5, 6)) }
        val night = temporary.newFile("night.png").apply { writeBytes(byteArrayOf(7, 8)) }
        val config = ReadBookConfig.Config().copy(name = "test", textSize = 31, paragraphSpacing = 16,
            bgType = 2, bgStr = day.absolutePath, bgTypeNight = 2, bgStrNight = night.absolutePath)
        val before = GSON.toJson(config)
        ZipFile(pack(config, first, second, listOf(day, night, day))).use { zip ->
            val value = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals("font.ttf", value.textFont); assertEquals("title_font.ttf", value.titleFont)
            assertEquals(31, value.textSize); assertEquals(16, value.paragraphSpacing)
            assertEquals(day.absolutePath, value.bgStr); assertEquals(night.absolutePath, value.bgStrNight)
            assertArrayEquals(first.readBytes(), zip.getInputStream(zip.getEntry(value.textFont)).use { it.readBytes() })
            assertArrayEquals(second.readBytes(), zip.getInputStream(zip.getEntry(value.titleFont)).use { it.readBytes() })
            assertArrayEquals(day.readBytes(), zip.getInputStream(zip.getEntry(day.name)).use { it.readBytes() })
            assertArrayEquals(night.readBytes(), zip.getInputStream(zip.getEntry(night.name)).use { it.readBytes() })
            assertEquals(5, zip.size())
        }
        assertEquals(before, GSON.toJson(config))
    }
    @Test fun identicalFontHasOneArchiveEntryAndUnavailableFontIsCleared() {
        val font = temporary.newFile("same.ttf").apply { writeBytes(byteArrayOf(9)) }
        ZipFile(pack(ReadBookConfig.Config(), font, font)).use { zip ->
            val value = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals("same.ttf", value.textFont); assertEquals(value.textFont, value.titleFont); assertEquals(2, zip.size())
        }
        ZipFile(pack(ReadBookConfig.Config(), File(temporary.root, "missing.ttf"), null)).use { zip ->
            val value = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals("", value.textFont); assertEquals("", value.titleFont); assertEquals(1, zip.size())
        }
    }
    @Test fun openedFontStreamIsClosedAfterPacking() = runBlocking {
        val font = temporary.newFile("resource.ttf").apply { writeText("font") }
        var closed = false
        packReaderBackgroundArchive(ReaderBackgroundExportSnapshot(GSON.toJson(ReadBookConfig.Config()), "", font.path, "", emptyList()),
            temporary.newFolder()) {
            "resource.ttf" to object : FilterInputStream(font.inputStream()) {
                override fun close() { closed = true; super.close() }
            }
        }
        assertTrue(closed)
    }
}
