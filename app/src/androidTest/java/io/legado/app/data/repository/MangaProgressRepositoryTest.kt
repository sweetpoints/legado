package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MangaProgressRepositoryTest {
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
    fun savesOnlyProgressIntoFreshMetadataAndDoesNotResurrectRemovedBook() = runBlocking {
        val staleBook = Book(bookUrl = "manga", name = "Old", group = 1, order = 2)
        withContext(Dispatchers.IO) {
            database.bookDao.insert(staleBook)
            database.bookDao.update(
                staleBook.copy(
                    name = "Fresh",
                    group = 8,
                    order = 20,
                    customCoverUrl = "new-cover",
                    customIntro = "new-intro",
                )
            )
        }
        val repository = RoomMangaProgressRepository(database)
        val update = MangaProgressUpdate("manga", 3, 7, 1234, pageChanged = true)
        val receipt = repository.save(update)
        assertEquals(7, receipt?.pageIndex)
        withContext(Dispatchers.IO) {
            val stored = database.bookDao.getBook("manga")!!
            assertEquals("Fresh", stored.name)
            assertEquals(8L, stored.group)
            assertEquals(20, stored.order)
            assertEquals("new-cover", stored.customCoverUrl)
            assertEquals("new-intro", stored.customIntro)
            assertEquals(3, stored.durChapterIndex)
            assertEquals(7, stored.durChapterPos)
            assertEquals(1234L, stored.durChapterTime)
            database.bookDao.delete(stored)
        }
        assertNull(repository.save(update))
        withContext(Dispatchers.IO) { assertNull(database.bookDao.getBook("manga")) }
    }

    @Test
    fun sameChapterPageChangePreservesTitleAndChapterChangeLoadsFreshChapterTitle() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookDao.insert(
                Book(
                    bookUrl = "manga",
                    name = "Manga",
                    durChapterIndex = 2,
                    durChapterTitle = "Existing title",
                )
            )
            database.bookChapterDao.insert(
                BookChapter(bookUrl = "manga", url = "third", index = 3, title = "Fresh chapter")
            )
        }
        val repository = RoomMangaProgressRepository(database)
        val sameChapter = repository.save(MangaProgressUpdate("manga", 2, 4, 100, true))
        assertEquals("Existing title", sameChapter?.chapterTitle)
        val nextChapter = repository.save(MangaProgressUpdate("manga", 3, 0, 200, true))
        assertEquals("Fresh chapter", nextChapter?.chapterTitle)
        withContext(Dispatchers.IO) {
            assertEquals("Fresh chapter", database.bookDao.getBook("manga")?.durChapterTitle)
        }
    }
}
