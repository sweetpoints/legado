package io.legado.app.data.repository

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class ReaderBackgroundFilesRepositoryTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun files(block: suspend (File, AppReaderBackgroundFilesRepository) -> Unit) = runBlocking {
        val root = File(context.cacheDir, "reader-files-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root, AppReaderBackgroundFilesRepository(context)) }
        finally { root.deleteRecursively() }
    }
    private fun snapshot(config: ReadBookConfig.Config, fonts: Pair<String, String> = "" to "", backgrounds: List<String> = emptyList()) =
        ReaderBackgroundExportSnapshot(GSON.toJson(config), config.name, fonts.first, fonts.second, backgrounds)

    @Test fun archiveUsesDistinctFontNamesAndPreservesFullPresetAndBackgrounds() = files { root, repo ->
        val name = UUID.randomUUID().toString()
        val first = File(root, "first/$name.ttf").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val second = File(root, "second/$name.ttf").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        val day = File(root, "$name-day.png").apply { writeBytes(byteArrayOf(7, 8)) }
        val night = File(root, "$name-night.png").apply { writeBytes(byteArrayOf(9, 10)) }
        val output = File(root, "output").apply { mkdirs() }
        val config = ReadBookConfig.Config().copy(name = "Preset", textSize = 29, paragraphSpacing = 14,
            bgType = 2, bgStr = day.absolutePath, bgTypeNight = 2, bgStrNight = night.absolutePath)
        val jsonBefore = GSON.toJson(config)
        assertEquals("Preset.zip", repo.export(snapshot(config, first.absolutePath to second.absolutePath,
            listOf(day.absolutePath, night.absolutePath, day.absolutePath)), output.absolutePath))
        ZipFile(File(output, "Preset.zip")).use { zip ->
            val archived = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals("$name.ttf", archived.textFont); assertEquals("title_$name.ttf", archived.titleFont)
            assertEquals(29, archived.textSize); assertEquals(14, archived.paragraphSpacing)
            assertArrayEquals(first.readBytes(), zip.getInputStream(zip.getEntry(archived.textFont)).use { it.readBytes() })
            assertArrayEquals(second.readBytes(), zip.getInputStream(zip.getEntry(archived.titleFont)).use { it.readBytes() })
            assertArrayEquals(day.readBytes(), zip.getInputStream(zip.getEntry(day.name)).use { it.readBytes() })
            assertArrayEquals(night.readBytes(), zip.getInputStream(zip.getEntry(night.name)).use { it.readBytes() })
            assertEquals(5, zip.size())
        }
        assertEquals(jsonBefore, GSON.toJson(config))
        val activeBefore = GSON.toJson(ReadBookConfig.durConfig)
        val imported = parseReadConfigObject(repo.importFile(File(output, "Preset.zip").toURI().toString())).getOrThrow()
        try {
            assertEquals(29, imported.textSize); assertEquals(14, imported.paragraphSpacing)
            assertArrayEquals(first.readBytes(), File(imported.textFont).readBytes())
            assertArrayEquals(second.readBytes(), File(imported.titleFont).readBytes())
            assertArrayEquals(day.readBytes(), File(imported.bgStr).readBytes())
            assertEquals(activeBefore, GSON.toJson(ReadBookConfig.durConfig))
        } finally {
            listOf(imported.textFont, imported.titleFont, imported.bgStr, imported.bgStrNight).forEach { File(it).delete() }
        }
    }
    @Test fun identicalFontIsArchivedOnceAndMissingFontsBecomeEmpty() = files { root, repo ->
        val font = File(root, "${UUID.randomUUID()}.ttf").apply { writeBytes(byteArrayOf(11)) }
        val output = File(root, "output").apply { mkdirs() }
        val config = ReadBookConfig.Config().copy(name = "")
        repo.export(snapshot(config, font.absolutePath to font.absolutePath), output.absolutePath)
        ZipFile(File(output, "readConfig.zip")).use { zip ->
            val value = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals(font.name, value.textFont); assertEquals(value.textFont, value.titleFont); assertEquals(2, zip.size())
        }
        repo.export(snapshot(config, File(root, "missing.ttf").absolutePath to ""), output.absolutePath)
        ZipFile(File(output, "readConfig.zip")).use { zip ->
            val value = parseReadConfigObject(zip.getInputStream(zip.getEntry("readConfig.json")).bufferedReader().use { it.readText() }).getOrThrow()
            assertEquals("", value.textFont); assertEquals("", value.titleFont); assertEquals(1, zip.size())
        }
    }
    @Test fun localBackgroundKeepsNinePatchSuffixAndNeverAppliesThePreset() = files { root, repo ->
        val image = File(root, "example.9.png").apply { writeBytes(UUID.randomUUID().toString().toByteArray()) }
        val activeBefore = GSON.toJson(ReadBookConfig.durConfig)
        val filename = repo.storeBackground(image.toURI().toString())
        val stored = File(context.externalFiles, "bg/$filename")
        try {
            assertTrue(filename.endsWith(".9.png")); assertArrayEquals(image.readBytes(), stored.readBytes())
            assertEquals(filename, repo.storeBackground(image.toURI().toString()))
            assertEquals(activeBefore, GSON.toJson(ReadBookConfig.durConfig))
            assertFalse(File(context.externalFiles, "bg").listFiles().orEmpty().any { it.name.startsWith("background-") && it.name.endsWith(".part") })
        } finally { stored.delete() }
    }
}
