package io.legado.app.model.rss

import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import org.junit.Test
import org.junit.Assert.*

class RssReaderHtmlTest {
    @Test fun preloadScriptUsesExistingHeadOrAddsAHeadWithoutDroppingContent() {
        val html = "<html><head>Existing</head><body>Body</body></html>"
        val value = rssReaderHtml(html, "body{color:red}", true)
        assertTrue(value.contains("<head>$JS_URL")); assertTrue(value.contains("Existing<style>body{color:red}</style></head>"))
        assertTrue(value.contains("<body>Body</body>"))
        assertTrue(rssReaderHtml("Body", "Custom", true).startsWith("<head>$JS_URL<style>Custom</style></head>"))
    }
    @Test fun existingStyleWithoutOverrideIsPreservedAndOverrideIsInsertedAfterItsClosingTag() {
        val html = "<style>Original</style><body>Body</body>"
        assertEquals(html, rssReaderHtml(html, null, false))
        assertEquals("<style>Original</style><style>Override</style><body>Body</body>", rssReaderHtml(html, "Override", false))
    }
    @Test fun missingStyleUsesResponsiveDefaultsWhileBlankOverrideKeepsDefaultBehavior() {
        val value = rssReaderHtml("<body>Body</body>", " ", false)
        assertTrue(value.contains("img{max-width:100%")); assertTrue(value.contains("video{object-fit:fill"))
        assertTrue(value.endsWith("<body>Body</body>")); assertFalse(value.contains(JS_URL))
    }
    @Test fun startScriptIsInsertedBeforeClosingBodyOrWrappedWithoutAlteringInput() {
        assertEquals("<style>CSS</style><body>Body<script>JS</script></body>", rssReaderStartHtml("<body>Body</body>", "JS", "CSS", false))
        assertEquals("<style>CSS</style><body>Body<script>JS</script></body>", rssReaderStartHtml("Body", "JS", "CSS", false))
        assertEquals("<style>CSS</style>Body", rssReaderStartHtml("Body", null, "CSS", false))
    }
}
