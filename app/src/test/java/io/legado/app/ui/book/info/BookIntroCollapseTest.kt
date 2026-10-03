package io.legado.app.ui.book.info

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIntroCollapseTest {

    @Test
    fun `short and exact four line introductions stay expanded`() {
        assertFalse(hasOverflow(lineCount = 3, lastLineEnd = 30, textLength = 30))
        assertFalse(hasOverflow(lineCount = 4, lastLineEnd = 40, textLength = 40))
    }

    @Test
    fun `ellipsis and hidden text expose the toggle`() {
        assertTrue(
            hasOverflow(
                lineCount = 4,
                lastLineEllipsisCount = 2,
                lastLineEnd = 38,
                textLength = 40,
            )
        )
        assertTrue(hasOverflow(lineCount = 4, lastLineEnd = 36, textLength = 40))
    }

    @Test
    fun `expanded content remains collapsible only when it exceeds the limit`() {
        assertTrue(hasOverflow(expanded = true, lineCount = 5, lastLineEnd = 50, textLength = 50))
        assertFalse(hasOverflow(expanded = true, lineCount = 4, lastLineEnd = 40, textLength = 40))
    }

    @Test
    fun `large inline content is detected by rendered height`() {
        assertTrue(
            hasOverflow(
                expanded = true,
                lineCount = 1,
                lastLineEnd = 1,
                textLength = 1,
                contentHeight = 240,
                collapsedContentHeight = 80,
            )
        )
    }

    @Test
    fun `legacy scroll text retains height limiting and internal scrolling`() {
        val scrollTextView =
            readProjectFile("src/main/java/io/legado/app/ui/widget/text/ScrollTextView.kt")

        assertTrue(scrollTextView.contains("var maxMeasuredHeight: Int? = null"))
        assertTrue(scrollTextView.contains("var isMeasuredHeightLimited = false"))
        assertTrue(scrollTextView.contains("setMeasuredDimension(measuredWidth, heightLimit)"))
        assertTrue(
            scrollTextView.contains("if (internalScrollEnabled) min(y, mOffsetHeight) else 0")
        )
    }

    private fun hasOverflow(
        expanded: Boolean = false,
        lineCount: Int,
        lastLineEllipsisCount: Int = 0,
        lastLineEnd: Int,
        textLength: Int,
        contentHeight: Int = 80,
        collapsedContentHeight: Int = 80,
    ) =
        BookIntroCollapse.hasOverflow(
            expanded = expanded,
            lineCount = lineCount,
            lastLineEllipsisCount = lastLineEllipsisCount,
            lastLineEnd = lastLineEnd,
            textLength = textLength,
            contentHeight = contentHeight,
            collapsedContentHeight = collapsedContentHeight,
        )

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
