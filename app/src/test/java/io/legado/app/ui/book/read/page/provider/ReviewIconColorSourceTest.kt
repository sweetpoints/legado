package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.preferences.BgTextSetting
import io.legado.app.data.preferences.bgTextUpdate
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewIconColorSourceTest {

    @Test
    fun `review icon color is persisted and exported`() {
        val source =
            projectFile("src/main/java/io/legado/app/help/config/ReadBookConfig.kt")
                .readText()
                .normalizeLines()

        assertTrue(source.contains("var reviewIconColor: Int = 0"))
        assertTrue(source.contains("get() = config.reviewIconColor"))
        assertTrue(source.contains("exportConfig.reviewIconColor = shareConfig.reviewIconColor"))
        assertTrue(source.contains("\"reviewIconColor\" to reviewIconColor"))
    }

    @Test
    fun `provider prefers configured color and keeps theme fallback`() {
        val source =
            projectFile("src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt")
                .readText()
                .normalizeLines()

        assertTrue(
            Regex("""reviewPaint\.color = ReadBookConfig\.reviewIconColor\.takeIf \{ it != 0 \}""")
                .containsMatchIn(source)
        )
        assertTrue(source.contains("ColorUtils.lightenColor(contentPaint.color)"))
        assertTrue(source.contains("ColorUtils.darkenColor(contentPaint.color)"))
    }

    @Test
    fun `color picker ids remain unique and review color has the exact refresh payload`() {
        assertEquals(124, BgTextConfigDialog.REVIEW_ICON_COLOR)
        assertEquals(
            5,
            setOf(
                    BgTextConfigDialog.TEXT_COLOR,
                    BgTextConfigDialog.BG_COLOR,
                    BgTextConfigDialog.TEXT_ACCENT_COLOR,
                    BgTextConfigDialog.REVIEW_ICON_COLOR,
                    BgTextConfigDialog.UNDERLINE_COLOR,
                )
                .size,
        )
        assertEquals(listOf(8, 9, 11), bgTextUpdate(BgTextSetting.ReviewColor).codes)
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()
        assertTrue(activity.contains("REVIEW_ICON_COLOR ->"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
