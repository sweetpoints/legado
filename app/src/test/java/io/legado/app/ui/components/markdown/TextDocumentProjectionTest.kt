package io.legado.app.ui.components.markdown

import org.junit.Test
import org.junit.Assert.*

class TextDocumentProjectionTest {
    @Test fun htmlLinksNestedEmphasisEntitiesAndColorsProduceRichRunsInsteadOfTags() {
        val document = projectTextDocument("<p><a href='https://example.com'><b>Strong &amp; <i>both</i></b></a> <span style='color:#f00;background-color:blue;text-decoration:underline line-through'>Colored</span></p>", "HTML")
        val runs = document.leaves.single().content.filterIsInstance<RichInline.Text>()
        assertTrue(runs.any { it.text == "both" && it.style.bold && it.style.italic && it.style.link == "https://example.com" })
        val colored = runs.single { it.text == "Colored" }; assertEquals(0xffff0000.toInt(), colored.style.color); assertEquals(0xff0000ff.toInt(), colored.style.background)
        assertTrue(colored.style.underline && colored.style.strike); assertEquals("Strong & both Colored", document.text)
    }
    @Test fun markdownHtmlPluginCapabilitiesAreProjectedAlongWithGfmTables() {
        val document = projectTextDocument("# Heading\n\n<b>Bold</b> **strong**\n\n| H |\n| --- |\n| C |", "MD")
        assertEquals(1, document.leaves.first().heading); assertTrue(document.leaves[1].content.filterIsInstance<RichInline.Text>().any { it.text == "Bold" && it.style.bold })
        val table = document.blocks.last() as RichBlock.Table; assertTrue(table.rows.first().single().header); assertEquals("C", table.rows.last().single().text)
    }
    @Test fun htmlImagesListsQuotesAndCodeRetainStructureAndSource() {
        val document = projectTextDocument("<blockquote><p>Quote<img src='content://image' alt='Picture'></p></blockquote><ol start='4'><li>Four</li><li>Five</li></ol><pre><code>x  y\nz</code></pre>", "HTML")
        val quote = document.blocks[0] as RichBlock.Quote; val image = (quote.children.single() as RichBlock.Leaf).content.filterIsInstance<RichInline.Image>().single()
        assertEquals("content://image", image.source); assertEquals("Picture", image.description)
        assertEquals(4, (document.blocks[1] as RichBlock.ListBlock).start); assertEquals("x  y\nz", document.leaves.last().text)
    }
    @Test fun scriptsAreNotRenderedWhileMalformedHtmlAndPlainTextStayReadable() {
        val document = projectTextDocument("<b>Open<script>alert(1)</script><style>body{}</style>", "HTML")
        assertEquals("Open", document.text); assertTrue((document.leaves.single().content.single() as RichInline.Text).style.bold)
        assertEquals("<b>literal</b>", projectTextDocument("<b>literal</b>", "TEXT").text)
    }
    @Test fun plainDisplayTruncatesAtLegacyLimitAndRichModesKeepFullContent() {
        val text = "x".repeat(40000)
        assertTrue(projectTextDocument(text, "TEXT").text.startsWith(text.take(32768))); assertTrue(projectTextDocument(text, "TEXT").text.endsWith("数据太大，无法全部显示…"))
        assertEquals(text, projectTextDocument(text, "MD").text)
    }
    @Test fun cssFunctionalAndNamedColorsProjectActualStylesAndRejectInvalidValues() {
        val document = projectTextDocument("<p><span style='color:rgb(100%,0%,0%);background-color:rgba(0,0,255,0.5)'>RGB</span><font color='cyan'>Named</font></p>", "HTML")
        val runs = document.leaves.single().content.filterIsInstance<RichInline.Text>()
        assertEquals(0xffff0000.toInt(), runs[0].style.color); assertEquals(0x800000ff.toInt(), runs[0].style.background)
        assertEquals(0xff00ffff.toInt(), runs[1].style.color)
        assertEquals(0x80ff0000.toInt(), richColor("rgba(255,0,0,50%)"))
        assertEquals(0xff0080ff.toInt(), richColor("rgb(-3,128,300)"))
        listOf("rgb(1,2)", "rgba(1,2,3)", "rgb(NaN,2,3)", "rgba(1,2,3,Infinity)", "unknown").forEach { assertNull(richColor(it)) }
    }

}
