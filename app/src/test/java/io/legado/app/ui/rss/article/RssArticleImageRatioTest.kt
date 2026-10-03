package io.legado.app.ui.rss.article

import io.legado.app.data.repository.rssArticleImageRatio
import org.junit.Assert.*
import org.junit.Test

class RssArticleImageRatioTest {
    @Test
    fun portraitAndLandscapeUseHeightDividedByWidth() {
        assertEquals(2f, rssArticleImageRatio(100, 200)!!, 0f)
        assertEquals(0.5f, rssArticleImageRatio(200, 100)!!, 0f)
        assertEquals(1f, rssArticleImageRatio(100, 100)!!, 0f)
    }

    @Test
    fun invalidDrawableDimensionsNeverProduceAspectConstraints() {
        assertNull(rssArticleImageRatio(0, 100))
        assertNull(rssArticleImageRatio(100, 0))
        assertNull(rssArticleImageRatio(-1, 100))
        assertNull(rssArticleImageRatio(100, -1))
    }
}
