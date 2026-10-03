package io.legado.app.model.rss

import io.legado.app.data.repository.RssReaderRequest
import org.junit.Assert.*
import org.junit.Test

class RssReaderImageOwnerTest {
    @Test
    fun differentInlineHtmlCreatesDifferentOwnerEvenWithSameNavigationFields() {
        val first =
            RssReaderRequest("origin", "title", openUrl = "url", startHtml = "<body>first</body>")
        assertNotEquals(
            rssReaderImageOwner(first),
            rssReaderImageOwner(first.copy(startHtml = "<body>second</body>")),
        )
        assertEquals(rssReaderImageOwner(first), rssReaderImageOwner(first.copy()))
    }

    @Test
    fun hugeInputProducesSmallHashWithNullAndFieldBoundariesPreserved() {
        val first = RssReaderRequest("a", startHtml = "H".repeat(2000000))
        assertEquals(64, rssReaderImageOwner(first).length)
        assertNotEquals(
            rssReaderImageOwner(RssReaderRequest("ab", "c")),
            rssReaderImageOwner(RssReaderRequest("a", "bc")),
        )
        assertNotEquals(
            rssReaderImageOwner(RssReaderRequest("origin", startHtml = null)),
            rssReaderImageOwner(RssReaderRequest("origin", startHtml = "")),
        )
    }
}
