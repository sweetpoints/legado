package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.model.ReadBook
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookMetadataReaderRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun before() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun mainCallerPreparesActualRoomHighlightsOnIoWithoutAllowMainThreadQueries() = runBlocking {
        val value =
            BookHighlight(time = 1, bookUrl = "book", bookName = "Edited", note = "Preserved")
        withContext(Dispatchers.IO) { database.bookHighlightDao.insert(value) }
        val prepared =
            withContext(Dispatchers.Main) {
                RoomBookMetadataReaderRepository(database).loadHighlights("book")
            }
        assertEquals(listOf(value), prepared)
        prepared.single().note = "Changed detached result"
        assertEquals(
            "Preserved",
            withContext(Dispatchers.IO) {
                database.bookHighlightDao.getByBook("book").single().note
            },
        )
    }

    @Test
    fun ownerSwitchAfterIoPreparationCannotPublishIntoNewReaderOrClearItsExistingHighlights() =
        runBlocking {
            val old = ReadBook.book
            val oldHighlights = ReadBook.highlights.toList()
            try {
                withContext(Dispatchers.Main) {
                    ReadBook.book = Book(bookUrl = "new", durChapterIndex = 13, durChapterPos = 99)
                    val current =
                        listOf(BookHighlight(time = 2, bookUrl = "new", note = "New reader"))
                    assertTrue(ReadBook.applyPreparedHighlights("new", current))
                    assertFalse(
                        ReadBook.applyPreparedHighlights(
                            "old",
                            listOf(BookHighlight(time = 1, bookUrl = "old")),
                        )
                    )
                    assertEquals(current, ReadBook.highlights)
                    assertEquals(13, ReadBook.book!!.durChapterIndex)
                    assertEquals(99, ReadBook.book!!.durChapterPos)
                }
            } finally {
                withContext(Dispatchers.Main) { restore(old, oldHighlights) }
            }
        }

    @Test
    fun sameOwnerPreparedPublishKeepsLatestReadingPositionAndPublishesCopiedList() = runBlocking {
        val old = ReadBook.book
        val oldHighlights = ReadBook.highlights.toList()
        try {
            withContext(Dispatchers.Main) {
                ReadBook.book =
                    Book(
                        bookUrl = "book",
                        name = "Edited",
                        durChapterIndex = 17,
                        durChapterPos = 81,
                    )
                val prepared =
                    mutableListOf(BookHighlight(time = 1, bookUrl = "book", bookName = "Edited"))
                assertTrue(ReadBook.applyPreparedHighlights("book", prepared))
                prepared.clear()
                assertEquals("Edited", ReadBook.highlights.single().bookName)
                assertEquals(17, ReadBook.book!!.durChapterIndex)
                assertEquals(81, ReadBook.book!!.durChapterPos)
            }
        } finally {
            withContext(Dispatchers.Main) { restore(old, oldHighlights) }
        }
    }

    private fun restore(book: Book?, highlights: List<BookHighlight>) {
        // The public publication API preserves the exact old list without a synchronous Room query.
        // An unmounted reader can still have retained highlights; use a temporary owner only for
        // cleanup.
        val owner = book ?: Book(bookUrl = "test-cleanup-owner")
        ReadBook.book = owner
        check(ReadBook.applyPreparedHighlights(owner.bookUrl, highlights))
        ReadBook.book = book
    }
}
