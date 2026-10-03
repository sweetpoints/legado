package io.legado.app.model.rss

import org.junit.Test
import org.junit.Assert.*

class RssReaderSpeechTextTest {
    @Test fun javascriptJsonEscapingIsDecodedBeforeExtractingText() {
        val result = rssReaderSpeechText("\"<html><body><p>First &amp; second</p><p>\\u4e2d\\u6587</p></body></html>\"")
        assertTrue(result.contains("First & second")); assertTrue(result.contains("中文"))
        assertFalse(result.contains("<p>")); assertFalse(result.contains("\\u4e2d"))
    }
    @Test fun scriptAndStyleContentDoNotBecomeSpokenTextAndParagraphsRemainSeparate() {
        val result = rssReaderSpeechText("\"<html><head><script>window.privateScript()</script><style>body{color:red}</style></head><body><p>One</p><p>Two</p></body></html>\"")
        assertTrue(result.contains("One")); assertTrue(result.contains("Two")); assertTrue(result.contains("\n"))
        assertFalse(result.contains("privateScript")); assertFalse(result.contains("color:red"))
    }
}
