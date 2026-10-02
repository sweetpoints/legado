package io.legado.app.ui.main.bookshelf.components

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.config.BookshelfReadProgressMode
import org.junit.Assert.*
import org.junit.Test

class BookshelfCardModelsTest {
    @Test fun immutableSnapshotPreservesTitleUnreadAndProgressAfterRoomObjectMutates() {
        val book = Book(bookUrl = "url", name = "Title", author = "Author", totalChapterNum = 11,
            durChapterIndex = 5, durChapterTitle = "Chapter 5", latestChapterTitle = "Chapter 10", lastCheckCount = 3)
        val card = book.toBookshelfCardModel(true, BookshelfReadProgressMode.ENHANCED, false, "one minute")
        book.name = "Changed"; book.durChapterIndex = 10; book.lastCheckCount = 0
        assertEquals("Title", card.name)
        assertEquals("Chapter 5", card.currentChapter)
        assertEquals(5, card.unreadCount)
        assertTrue(card.highlightUnread)
        assertEquals("50%", card.progressPercent)
        assertEquals(4, card.progressThicknessDp)
        assertEquals("one minute", card.latestUpdateLabel)
    }
    @Test fun hiddenProgressAndUnreadDoNotEraseOtherDisplayState() {
        val book = Book(totalChapterNum = 11, durChapterIndex = 5)
        val card = book.toBookshelfCardModel(false, BookshelfReadProgressMode.HIDDEN, true)
        assertEquals(0, card.unreadCount)
        assertNull(card.readProgress)
        assertNull(card.progressPercent)
        assertTrue(card.updating)
    }
    @Test fun localBooksNeverShowUpdatingOrNetworkUpdateAge() {
        val card = Book(type = BookType.local or BookType.text).toBookshelfCardModel(true, 1, true, "one hour")
        assertFalse(card.updating)
        assertNull(card.latestUpdateLabel)
        assertNull(card.progressPercent)
    }
    @Test fun startedSingleChapterAndOvershootingBooksClampToComplete() {
        assertEquals("100%", Book(totalChapterNum = 1, durChapterPos = 1).toBookshelfCardModel(true, 1, false).progressPercent)
        assertEquals("100%", Book(totalChapterNum = 10, durChapterIndex = 30).toBookshelfCardModel(true, 1, false).progressPercent)
        assertEquals(0, Book(totalChapterNum = 10, durChapterIndex = 30).toBookshelfCardModel(true, 1, false).unreadCount)
    }
}
