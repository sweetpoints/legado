package io.legado.app.ui.book.info

import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.info.detail.BookDetailIntroMode
import io.legado.app.ui.book.info.detail.bookDetailIntroDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIntroIndentTest {

    @Test
    fun `indents every non-empty unindented paragraph`() {
        val intro = "第一段\n第二段\n\n第三段"

        assertEquals(
            listOf("第一段\n", "第二段\n", "第三段"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `keeps manual indentation and blank paragraphs unchanged`() {
        val intro = "　　全角缩进\n 普通空格\n\n未缩进"

        assertEquals(
            listOf("未缩进"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `plain intro document indents each paragraph while rich modes use the renderer`() {
        val plain = bookDetailIntroDocument("First paragraph\nSecond paragraph")
        assertEquals(BookDetailIntroMode.Plain, plain.mode)
        val paragraphStyles = checkNotNull(plain.plain).paragraphStyles
        assertEquals(2, paragraphStyles.size)
        assertTrue(
            paragraphStyles.all {
                it.item.textIndent == TextIndent(firstLine = 28.sp, restLine = 0.sp)
            }
        )
        for (raw in
            listOf(
                "<usehtml><p>Text</p></usehtml>",
                "<md>**Text**</md>",
                "<useweb>https://example.com</useweb>",
            )) {
            val rich = bookDetailIntroDocument(raw)
            assertNull(rich.plain)
            assertTrue(rich.mode != BookDetailIntroMode.Plain)
        }
    }
}
