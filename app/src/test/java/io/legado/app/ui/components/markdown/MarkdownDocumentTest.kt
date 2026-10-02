package io.legado.app.ui.components.markdown

import org.junit.Test
import org.junit.Assert.*

class MarkdownDocumentTest {
    @Test fun nestedStylesLinksAndBreaksKeepTheirExactTextAndTargets() {
        val paragraph = parseMarkdownDocument("**bold _both_** [Open](https://example.com) `code`\nnext").single() as MarkdownBlock.Paragraph
        val runs = paragraph.content.filterIsInstance<MarkdownInline.Text>()
        assertTrue(runs.any { it.text == "both" && it.bold && it.italic }); assertTrue(runs.any { it.text == "code" && it.code })
        assertEquals("https://example.com", runs.single { it.text == "Open" }.link)
        assertEquals("bold both Open code\nnext", runs.joinToString("") { it.text })
    }
    @Test fun orderedNestedListsQuotesAndCodeRemainStructuralBlocks() {
        val blocks = parseMarkdownDocument("3. Three\n4. Four\n\n> # Quote\n\n```kotlin\nval x = 1\n```")
        val list = blocks[0] as MarkdownBlock.ListBlock; assertEquals(3, list.start); assertEquals(2, list.items.size)
        assertEquals(1, ((blocks[1] as MarkdownBlock.Quote).blocks.single() as MarkdownBlock.Paragraph).heading)
        assertEquals("kotlin", (blocks[2] as MarkdownBlock.Code).language); assertEquals("val x = 1\n", (blocks[2] as MarkdownBlock.Code).content)
    }
    @Test fun gfmTablesKeepHeaderAlignmentAndStyledCellContent() {
        val table = parseMarkdownDocument("| Left | Right |\n| :--- | ---: |\n| **A** | [B](https://example.com/b) |").single() as MarkdownBlock.Table
        assertEquals(2, table.rows.size); assertTrue(table.rows[0].all { it.header }); assertFalse(table.rows[1].any { it.header })
        assertEquals("LEFT", table.rows[1][0].alignment); assertEquals("RIGHT", table.rows[1][1].alignment)
        assertTrue((table.rows[1][0].content.single() as MarkdownInline.Text).bold)
        assertEquals("https://example.com/b", (table.rows[1][1].content.single() as MarkdownInline.Text).link)
    }
    @Test fun inlineAndLinkedImagesRetainAltSourceAndSurroundingText() {
        val paragraph = parseMarkdownDocument("Before [![Picture](file:///image.png)](https://example.com) after").single() as MarkdownBlock.Paragraph
        assertEquals(MarkdownInline.Image("file:///image.png", "Picture", "https://example.com"), paragraph.content[1])
        assertEquals("Before ", (paragraph.content.first() as MarkdownInline.Text).text); assertEquals(" after", (paragraph.content.last() as MarkdownInline.Text).text)
    }
    @Test fun emptyAndMalformedMarkdownDoNotDropLiteralUnicode() {
        assertTrue(parseMarkdownDocument("").isEmpty())
        val paragraph = parseMarkdownDocument("未闭合 [链接 **文字").single() as MarkdownBlock.Paragraph
        assertEquals("未闭合 [链接 **文字", paragraph.content.filterIsInstance<MarkdownInline.Text>().joinToString("") { it.text })
    }
}
