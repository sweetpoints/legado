package io.legado.app.ui.dict

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DictionaryResultDocumentTest {
    @Test fun markdownTablesLinksAndEmbeddedHtmlRender() {
        val document = dictionaryResultDocument("<md>## Heading\n\n| A | B |\n|---|---|\n| one | two |\n\n[link](https://example.org?q=1&lang=zh)\n\n<b>bold</b></md>")
        val html = Jsoup.parseBodyFragment(document.body)
        assertEquals("Heading", html.selectFirst("h2")?.text()); assertEquals(1, html.select("table").size)
        assertEquals("https://example.org?q=1&lang=zh", html.selectFirst("a")?.attr("href")); assertEquals("bold", html.selectFirst("b")?.text())
    }
    @Test fun malformedMarkdownWrapperKeepsLiteralText() {
        val document = dictionaryResultDocument("<md>unfinished")
        assertEquals("<md>unfinished", document.text); assertTrue(document.actions.isEmpty())
    }
    @Test fun htmlButtonsOnlyDispatchLegacyDelimiterAndStripBrowserHandlers() {
        val document = dictionaryResultDocument("<button onclick='browser()'>译@onclick:java.toast('x')</button><button>plain</button><script>unsafe()</script>")
        val html = Jsoup.parseBodyFragment(document.body)
        assertEquals(DictionaryResultAction("button 译", "java.toast('x')"), document.actions.values.single())
        assertEquals("译", html.selectFirst("a")?.text()); assertTrue(html.select("[onclick],script").isEmpty())
        assertNull(document.action("https://dictionary-action.invalid/unrecognized"))
    }
    @Test fun imageKeepsOriginalSourceForPhotoAndCustomSizeStyleClick() {
        val source = "https://example.org/x.gif,{\"width\":\"80%\",\"style\":\"center\",\"click\":\"java.toast(2)\"}"
        val document = dictionaryResultDocument("<img src='$source'>")
        val image = Jsoup.parseBodyFragment(document.body).selectFirst("img")!!
        assertEquals(source, document.image(image.attr("src"))); assertTrue(image.attr("style").contains("width:80%")); assertTrue(image.attr("style").contains("margin-right:auto"))
        assertEquals(DictionaryResultAction("image", "java.toast(2)"), document.actions.values.single())
    }
    @Test fun markdownImagesDoNotGainLegacyHtmlScriptActions() {
        val document = dictionaryResultDocument("<md><button>go@onclick:script()</button><img src='https://example.org/x.gif,{\"click\":\"script()\"}'></md>")
        assertTrue(document.actions.isEmpty()); assertEquals(1, document.images.size)
    }
}
