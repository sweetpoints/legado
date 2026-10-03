package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class BookshelfManagementRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: BookshelfManagementRepository
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val actual = AppBookshelfManagementStore(database)
        val isolated = object : BookshelfManagementStore by actual {
            override fun sort(groupId: Long) = 3
            override fun openTitle() = false
            override fun setOpenTitle(value: Boolean) = Unit
        }
        repository = DefaultBookshelfManagementRepository(isolated)
        withContext(Dispatchers.IO) {
            database.bookDao.insert(Book(bookUrl = "a", name = "First", author = "one", group = 1, order = 1),
                Book(bookUrl = "hidden", name = "Hidden", author = "two", group = 2, order = 2),
                Book(bookUrl = "b", name = "Last", author = "three", group = 1, order = 3))
            database.bookGroupDao.insert(BookGroup(1, "Group"), BookGroup(2, "HiddenGroup"))
        }
    }
    @After fun close() { database.close() }
    @Test fun latestReadingAndCoverMetadataSurviveGroupAndOrderEditsWhileMissingIdsStayDeleted() = runBlocking {
        val oldProjection = repository.observe(1, "").first(); assertEquals(listOf("a", "b"), oldProjection.books.map { it.id })
        withContext(Dispatchers.IO) {
            val fresh = checkNotNull(database.bookDao.getBook("a")).copy(durChapterIndex = 17, customCoverUrl = "custom-new", persistedCoverUrl = "persistent-new", intro = "latest metadata")
            database.bookDao.update(fresh)
            database.bookDao.delete(checkNotNull(database.bookDao.getBook("b")))
        }
        repository.group(oldProjection.books.map { it.id }, 2, ShelfGroupMutation.Add)
        repository.order(listOf(ShelfOrderAssignment("hidden", 1), ShelfOrderAssignment("a", 2), ShelfOrderAssignment("b", 3)), true)
        withContext(Dispatchers.IO) {
            val current = checkNotNull(database.bookDao.getBook("a")); assertEquals(3L, current.group); assertEquals(17, current.durChapterIndex)
            assertEquals("custom-new", current.customCoverUrl); assertEquals("persistent-new", current.persistedCoverUrl); assertEquals("latest metadata", current.intro)
            assertNull(database.bookDao.getBook("b")); assertEquals(listOf("hidden", "a"), database.bookDao.allShelfByOrder.map { it.bookUrl })
        }
    }
}
