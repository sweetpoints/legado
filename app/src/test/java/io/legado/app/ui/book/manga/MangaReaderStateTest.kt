package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MangaReaderStateTest {
    @Test
    fun engineMutationDoesNotChangePublishedPageAndDuplicateUrlsKeepChapterIdentity() {
        val page =
            MangaPage(
                chapterIndex = 4,
                chapterSize = 8,
                mImageUrl = "same",
                index = 2,
                imageCount = 10,
                mChapterName = "four",
            )
        val snapshot = snapshotMangaItems(listOf(page, page.copy(chapterIndex = 5)))
        page.imageCount = 30
        assertEquals(10, (snapshot.first() as MangaReaderItem.Page).imageCount)
        assertNotEquals(snapshot.first(), snapshot.last())
    }

    @Test
    fun boundaryProgressFollowsPreviousImageAndClampsEmptyChapter() {
        val snapshot =
            snapshotMangaItems(listOf(ReaderLoading(index = 0), ReaderLoading(index = 7)))
        assertEquals(0, snapshot[0].readingPage())
        assertEquals(6, snapshot[1].readingPage())
    }

    @Test
    fun tapRectanglesAndRtlMatchExistingReader() {
        assertEquals(MangaTapAction.Menu, mangaTapAction(.5f, .5f, true, true))
        assertEquals(MangaTapAction.None, mangaTapAction(.1f, .1f, false, false))
        assertEquals(MangaTapAction.None, mangaTapAction(.1f, .9f, false, true))
        assertEquals(MangaTapAction.Previous, mangaTapAction(.1f, .9f, false, false))
        assertEquals(MangaTapAction.Next, mangaTapAction(.1f, .9f, true, false))
        assertEquals(MangaTapAction.Previous, mangaTapAction(.9f, .9f, true, false))
        assertEquals(MangaTapAction.None, mangaTapAction(1f, .9f, false, false))
    }
}
