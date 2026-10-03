package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class BookshelfCoverRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var actual: AppBookshelfCoverStore
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        actual = AppBookshelfCoverStore(context, database)
        withContext(Dispatchers.IO) { database.bookDao.insert(Book(bookUrl = "cover", name = "Cover", coverUrl = "https://synthetic-cover", durChapterIndex = 20)) }
    }
    @After fun close() { database.close() }
    @Test fun realComparePredicateRejectsEditedCoverAndDoesNotTouchReadingProgress() = runBlocking {
        val simulatedDownload = object : BookshelfCoverStore by actual {
            override suspend fun download(book: Book, url: String): String {
                val latest = checkNotNull(database.bookDao.getBook(book.bookUrl)); database.bookDao.update(latest.copy(customCoverUrl = "https://edited-while-downloading")); return "/synthetic-persistent-cover"
            }
        }
        val result = DefaultBookshelfCoverRepository(simulatedDownload).run(listOf("cover"), ShelfCoverAction.PersistNetwork).toList().last() as ShelfCoverEvent.Completed
        assertEquals(ShelfCoverSummary(0, 1, 0), result.summary)
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.persistedCoverUrl); assertEquals("https://edited-while-downloading", row.customCoverUrl); assertEquals(20, row.durChapterIndex) }
    }
    @Test fun actualRestorePredicatesPreserveCustomOnNetworkRestoreAndClearOverridesOnSourceRestore() = runBlocking {
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); database.bookDao.update(row.copy(customCoverUrl = "https://custom", persistedCoverUrl = "/synthetic-persisted")) }
        val repository = DefaultBookshelfCoverRepository(actual)
        repository.run(listOf("cover"), ShelfCoverAction.RestoreNetwork).toList()
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.persistedCoverUrl); assertEquals("https://custom", row.customCoverUrl) }
        repository.run(listOf("cover"), ShelfCoverAction.RestoreSource).toList()
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.customCoverUrl); assertNull(row.persistedCoverUrl); assertEquals(20, row.durChapterIndex) }
    }
}
