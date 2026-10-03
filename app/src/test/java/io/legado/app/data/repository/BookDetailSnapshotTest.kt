package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import org.junit.Assert.*
import org.junit.Test

class BookDetailSnapshotTest {
    @Test
    fun bookProjectionAndFreshNativePayloadKeepEveryOpaqueMetadataFieldWithoutSharingMutableOwner() {
        val owner =
            Book(
                bookUrl = "book",
                name = "Original",
                author = "Author",
                origin = "source",
                coverUrl = "source-cover",
                customCoverUrl = "custom-cover",
                persistedCoverUrl = "cached-cover",
                customIntro = "<md>Intro</md>",
                durChapterIndex = 3,
                durChapterPos = 10,
                totalChapterNum = 11,
                group = 9,
                variable = "{\"opaque\":\"preserved\"}",
                type = BookType.image or BookType.webFile,
            )
        owner.infoHtml = "Large opaque HTML"
        owner.setSplitLongChapter(false)
        val snapshot = BookDetailBook.from(owner)
        owner.name = "External"
        owner.variable = "Changed"
        owner.setSplitLongChapter(true)
        assertEquals("Original", snapshot.name)
        assertEquals("cached-cover", snapshot.cover.path)
        assertEquals("<md>Intro</md>", snapshot.intro)
        assertTrue(snapshot.isImage)
        assertTrue(snapshot.isWebFile)
        assertFalse(snapshot.splitLongChapter)
        val payload = snapshot.materializeBook()
        assertEquals("{\"opaque\":\"preserved\"}", payload.variable)
        assertEquals("Large opaque HTML", payload.infoHtml)
        assertEquals(9L, payload.group)
        assertEquals(3, payload.durChapterIndex)
        payload.name = "Native consumer changed"
        assertEquals("Original", snapshot.materializeBook().name)
    }

    @Test
    fun chapterProjectionPreservesVolumeVipAudioAndOpaqueVariablesAndDoesNotShareNativeCopies() {
        val chapter =
            BookChapter(
                bookUrl = "book",
                url = "chapter",
                index = 5,
                title = "Title\nText",
                isVolume = true,
                isVip = true,
                resourceUrl = "audio",
                variable = "chapter variable",
                start = 4,
                end = 20,
            )
        val snapshot = BookDetailChapter.from(chapter)
        chapter.title = "Changed"
        assertEquals("TitleText", snapshot.title)
        assertTrue(snapshot.isVolume)
        val payload = snapshot.materializeChapter()
        assertEquals("Title\nText", payload.title)
        assertTrue(payload.isVip)
        assertEquals("audio", payload.resourceUrl)
        assertEquals("chapter variable", payload.variable)
        assertEquals(4L, payload.start)
    }

    @Test
    fun sourceLoginUiAndRulesRemainIntactAndSnapshotButtonDoesNotFollowExternalMutation() {
        val source =
            BookSource(
                bookSourceUrl = "source",
                bookSourceName = "Original source",
                loginUi = "[{\"name\":\"Login\"}]",
                customButton = true,
                variableComment = "Comment",
            )
        val snapshot = BookDetailSource.from(source)
        source.customButton = false
        source.bookSourceName = "Changed"
        assertTrue(snapshot.hasLogin)
        assertTrue(snapshot.customButton)
        assertEquals("Original source", snapshot.name)
        assertEquals("Comment", snapshot.materializeSource().variableComment)
    }

    @Test
    fun readProgressRetainsLegacyNoProgressAndSingleChapterVisibilityAndClampsPercent() {
        val book =
            Book(
                bookUrl = "book",
                origin = "source",
                type = BookType.text,
                totalChapterNum = 11,
                durChapterIndex = 0,
                durChapterPos = 0,
            )
        assertNull(BookDetailBook.from(book).readPercent)
        book.durChapterIndex = 3
        assertEquals(30, BookDetailBook.from(book).readPercent)
        book.durChapterIndex = 999
        assertEquals(100, BookDetailBook.from(book).readPercent)
        book.totalChapterNum = 1
        assertNull(BookDetailBook.from(book).readPercent)
    }
}
