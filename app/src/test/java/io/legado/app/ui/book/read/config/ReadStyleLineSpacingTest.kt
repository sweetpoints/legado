package io.legado.app.ui.book.read.config

import io.legado.app.data.preferences.ReadStyleSlider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadStyleLineSpacingTest {

    @Test
    fun `Compose slider exposes the requested range`() {
        assertEquals(50, ReadStyleSlider.LineSpacing.maximum)
        assertEquals("-2.0", ReadStyleSlider.LineSpacing.display(0))
        assertEquals(
            "3.0",
            ReadStyleSlider.LineSpacing.display(ReadStyleSlider.LineSpacing.maximum),
        )
    }

    @Test
    fun `legacy values keep their effective line spacing`() {
        assertEquals(10, lineSpacingToProgress(0))
        assertEquals(22, lineSpacingToProgress(12))
        assertEquals(30, lineSpacingToProgress(20))
        assertEquals(12, lineSpacingFromProgress(lineSpacingToProgress(12)))
        assertEquals("0.2", lineSpacingDisplayValue(lineSpacingToProgress(12)))
    }

    @Test
    fun `new endpoints map to minus two through plus three`() {
        assertEquals(-10, lineSpacingFromProgress(0))
        assertEquals(40, lineSpacingFromProgress(50))
        assertEquals("-2.0", lineSpacingDisplayValue(0))
        assertEquals("3.0", lineSpacingDisplayValue(50))
    }

    @Test
    fun `progress and config values are clamped`() {
        assertEquals(0, lineSpacingToProgress(-100))
        assertEquals(50, lineSpacingToProgress(100))
        assertEquals(-10, lineSpacingFromProgress(-1))
        assertEquals(40, lineSpacingFromProgress(51))
    }

    @Test
    fun `pagination still consumes the stored multiplier`() {
        val provider =
            projectFile("src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt")
                .readText()
        val layout =
            projectFile(
                    "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
                )
                .readText()

        assertTrue(provider.contains("lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f"))
        assertTrue(layout.contains("durY += lineHeight * lineSpacingExtra"))
        assertTrue(layout.contains("val lineHeight = lineHeights[lineIndex]"))
        assertTrue(layout.contains("durY += lineHeight * lineSpacing"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
