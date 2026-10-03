package io.legado.app.ui.book.info.detail

import org.junit.Assert.*
import org.junit.Test

class BookDetailIntroDocumentTest {
    @Test
    fun preparedPlainTextIndentsOnlyNonblankParagraphsWithoutExistingWhitespace() {
        val raw = "First\n  Already indented\n\nLast"
        val text = bookDetailIntroDocument(raw).plain!!
        assertEquals(raw, text.text)
        assertEquals(listOf(0 to 6, 26 to 30), text.paragraphStyles.map { it.start to it.end })
        assertTrue(
            text.paragraphStyles.all {
                it.item.textIndent!!.firstLine.value == 28f &&
                    it.item.textIndent!!.restLine.value == 0f
            }
        )
    }

    @Test
    fun plainAndMalformedTagsRemainLiteralAndSignaturesAreStableAcrossRestore() {
        listOf("plain\nsecond", "<usehtml>", "<md>", "<useweb>").forEach { raw ->
            val first = bookDetailIntroDocument(raw)
            assertEquals(BookDetailIntroMode.Plain, first.mode)
            assertEquals(raw, first.content)
            assertEquals(first.signature, bookDetailIntroDocument(raw).signature)
        }
        assertNotEquals(
            bookDetailIntroDocument("A").signature,
            bookDetailIntroDocument("B").signature,
        )
        assertFalse(bookDetailIntroDocument(null).canCollapse)
    }

    @Test
    fun webModePreservesActualScriptMarkupAndHasNoCollapseOrHtmlActionAllowlist() {
        val document =
            bookDetailIntroDocument(
                "<useweb><script>source.getVariable()</script><p>Text</p></useweb>"
            )
        assertEquals(BookDetailIntroMode.Web, document.mode)
        assertTrue(document.content.contains("source.getVariable()"))
        assertFalse(document.canCollapse)
        assertNull(document.rich)
    }

    @Test
    fun htmlActionsUseOnlyParsedLegacyButtonAndImageOptionsAndUnknownUrlsHaveNoAction() {
        val document =
            bookDetailIntroDocument(
                "<usehtml><button>Go@onclick:book.name</button><img src='https://image/path,{\"click\":\"book.author\"}'><a href='https://link'>Link</a><script>default()</script></usehtml>"
            )
        val rich = document.rich!!
        assertEquals(BookDetailIntroMode.Html, document.mode)
        assertFalse(rich.body.contains("<script"))
        assertEquals(
            setOf("book.name", "book.author"),
            rich.actions.values.map { it.script }.toSet(),
        )
        assertNull(rich.action("https://dictionary-action.invalid/unknown"))
        assertTrue(rich.images.values.single().contains("https://image/path"))
        assertTrue(rich.body.contains("https://link"))
    }

    @Test
    fun htmlOnclickAttributesCannotExecuteByDefaultAndMarkupRetainsCopyableText() {
        val document =
            bookDetailIntroDocument(
                "<usehtml><p onclick='unsafe()'>Selectable text</p><button onclick='unsafe()'>No script marker</button></usehtml>"
            )
        assertFalse(document.rich!!.body.contains("onclick"))
        assertTrue(document.rich.actions.isEmpty())
        assertTrue(document.rich.text.contains("Selectable text"))
    }

    @Test
    fun markdownRetainsTablesLinksImagesAndNeverCreatesSourceActions() {
        val document =
            bookDetailIntroDocument(
                "<md>|A|B|\n|---|---|\n|1|2|\n\n[link](https://link)\n\n![image](https://image)\n<button>Go@onclick:unsafe()</button></md>"
            )
        assertEquals(BookDetailIntroMode.Markdown, document.mode)
        assertTrue(document.rich!!.body.contains("<table>"))
        assertTrue(document.rich.body.contains("https://link"))
        assertEquals("https://image", document.rich.images.values.single())
        assertTrue(document.rich.actions.isEmpty())
        assertTrue(document.canCollapse)
    }
}
