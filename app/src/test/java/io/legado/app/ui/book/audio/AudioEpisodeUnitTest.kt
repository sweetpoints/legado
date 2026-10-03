package io.legado.app.ui.book.audio

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class AudioEpisodeUnitTest {

    @Test
    fun audioAndReadAloudKeepSeparateChineseCountUnits() {
        assertEquals("正在朗读（还剩 %d 章）", chineseString("read_aloud_timer_chapter"))
        assertEquals("正在播放（还剩 %d 集）", chineseString("playing_timer_chapter"))
        assertEquals("%d 章", chineseString("sleep_timer_chapters"))
        assertEquals("听完 %d 集", chineseString("audio_stop_chapters"))
        assertEquals("按集数", chineseString("sleep_timer_by_episode"))
        assertEquals("Chapters", defaultString("sleep_timer_by_episode"))
        assertEquals("第 %1\$d 集 · 共 %2\$d 集", chineseString("audio_chapter_progress"))
        assertEquals("Chapter %1\$d / %2\$d", defaultString("audio_chapter_progress"))
    }

    @Test
    fun lyricParserIgnoresMetadataAndExpandsMultipleTimestamps() {
        assertEquals(emptyList<AudioLyric>(), parseAudioLyrics("[ar:Artist]\n[ti:Title]"))
        assertEquals(
            listOf(AudioLyric(1250, "line"), AudioLyric(2500, "line")),
            parseAudioLyrics("[00:02.50][00:01.25]line"),
        )
    }

    @Test
    fun lyricParserRejectsOverflowAndKeepsMillisecondPrecision() {
        assertEquals(listOf(AudioLyric(62345, "line")), parseAudioLyrics("[01:02.345]line"))
        assertEquals(emptyList<AudioLyric>(), parseAudioLyrics("[999999999999999999:00]bad"))
    }

    private fun chineseString(name: String) = stringValue("values-zh", name)

    private fun defaultString(name: String) = stringValue("values", name)

    private fun stringValue(directory: String, name: String): String {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(projectFile("src/main/res/$directory/strings.xml"))
        return document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("name") == name }
                .textContent
        }
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
