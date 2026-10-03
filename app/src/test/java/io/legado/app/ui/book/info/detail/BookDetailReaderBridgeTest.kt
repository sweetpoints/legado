package io.legado.app.ui.book.info.detail

import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookDetailMutationKind
import org.junit.Assert.*
import org.junit.Test

class BookDetailReaderBridgeTest {
    @Test
    fun committedMetadataChangesWhileLiveProgressConfigurationAndSyncTimeRemainFresh() {
        val config =
            Book.ReadConfig(pageAnim = 3, manualReplaceRuleIds = listOf(8), playSpeed = 1.7f)
        val live =
            Book(
                bookUrl = "old",
                name = "Old",
                author = "Old",
                durChapterIndex = 15,
                durChapterPos = 88,
                durChapterTitle = "Live",
                durVolumeIndex = 3,
                chapterInVolumeIndex = 4,
                durChapterTime = 123,
                readConfig = config,
                syncTime = 99,
            )
        val committed =
            live.copy(
                bookUrl = "new",
                name = "New",
                author = "New",
                customCoverUrl = "cover",
                group = 7,
                durChapterIndex = 0,
                durChapterPos = 0,
                readConfig = null,
                syncTime = 0,
            )
        val result = mergeBookDetailLiveReaderBook(committed, live)
        assertEquals("new", result.bookUrl)
        assertEquals("New", result.name)
        assertEquals("New", result.author)
        assertEquals("cover", result.customCoverUrl)
        assertEquals(7L, result.group)
        assertEquals(15, result.durChapterIndex)
        assertEquals(88, result.durChapterPos)
        assertEquals("Live", result.durChapterTitle)
        assertEquals(3, result.durVolumeIndex)
        assertEquals(4, result.chapterInVolumeIndex)
        assertEquals(123L, result.durChapterTime)
        assertSame(config, result.readConfig)
        assertEquals(99L, result.syncTime)
        assertEquals("Old", live.name)
        assertEquals(0, committed.durChapterIndex)
    }

    @Test
    fun splitLongMutationUpdatesOnlyItsRequestedConfigFieldAndKeepsOtherLiveReaderOptions() {
        val config =
            Book.ReadConfig(
                pageAnim = 3,
                splitLongChapter = true,
                playSpeed = 1.8f,
                manualReplaceRuleIds = listOf(9L),
            )
        val live = Book(bookUrl = "book", durChapterIndex = 12, readConfig = config)
        val committed =
            live.copy(
                readConfig = Book.ReadConfig(splitLongChapter = false, pageAnim = 0, playSpeed = 1f)
            )
        val merged =
            mergeBookDetailLiveReaderBook(committed, live, BookDetailMutationKind.SplitLong)
        assertFalse(merged.readConfig!!.splitLongChapter)
        assertEquals(3, merged.readConfig!!.pageAnim)
        assertEquals(1.8f, merged.readConfig!!.playSpeed, 0f)
        assertEquals(listOf(9L), merged.readConfig!!.manualReplaceRuleIds)
        assertEquals(12, merged.durChapterIndex)
        assertTrue(config.splitLongChapter)
    }

    @Test
    fun cachedEngineHtmlAndDownloadUrlsSurviveDetachedReaderCopy() {
        val committed =
            Book(bookUrl = "book").apply {
                infoHtml = "info"
                tocHtml = "toc"
                downloadUrls = listOf("download")
            }
        val merged =
            mergeBookDetailLiveReaderBook(committed, Book(bookUrl = "book", durChapterIndex = 8))
        assertEquals("info", merged.infoHtml)
        assertEquals("toc", merged.tocHtml)
        assertEquals(listOf("download"), merged.downloadUrls)
        assertEquals(8, merged.durChapterIndex)
    }
}
