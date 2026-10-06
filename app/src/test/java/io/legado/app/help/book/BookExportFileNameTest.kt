package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookExportFileNameTest {

    @Test
    fun `normalizes reserved characters in ordinary default export name`() {
        assertEquals(
            "书_名 作者：作_者.txt",
            normalizeExportFileName("书/名 作者：作|者", "txt"),
        )
    }

    @Test
    fun `normalizes reserved characters in custom script export name`() {
        assertEquals(
            "分类_书_名_.epub",
            normalizeExportFileName("分类:书*名?", "epub"),
        )
    }

    @Test
    fun `normalizes reserved characters in split default export name`() {
        assertEquals(
            "书_名 作者：作_者 [2].epub",
            normalizeExportFileName("书/名 作者：作|者 [2]", "epub"),
        )
    }

    @Test
    fun `rejects null and blank results`() {
        assertNull(parseExportFileNameResult(null))
        assertNull(parseExportFileNameResult(""))
        assertNull(parseExportFileNameResult("   "))
    }

    @Test
    fun `keeps non-blank script result for normalization`() {
        assertEquals("分类:书名", parseExportFileNameResult("分类:书名"))
        assertEquals("0", parseExportFileNameResult(0))
        assertEquals("false", parseExportFileNameResult(false))
    }

}
