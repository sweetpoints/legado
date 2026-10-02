package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookMemoRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBookMemoRepository
    private val ids = mutableListOf<String>()
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); repository = RoomBookMemoRepository(context, database)
        withContext(Dispatchers.IO) { database.bookDao.insert(Book(bookUrl = "memo-book", name = "Book")) }
    }
    @After fun close() { database.close(); ids.forEach { AtomicFile(File(context.filesDir, "book-memo-drafts/$it.json")).delete() } }
    @Test fun saveAndClearPreserveMonotonicDatedEmptyEntriesAgainstOlderBackups() = runBlocking {
        withContext(Dispatchers.IO) { database.bookMemoDao.insert(BookMemo("memo-book", "Old", Long.MAX_VALUE - 20)) }
        val saved = repository.save("memo-book", "**Markdown**"); assertEquals(Long.MAX_VALUE - 19, saved.updatedAt)
        val cleared = repository.save("memo-book", ""); assertEquals(Long.MAX_VALUE - 18, cleared.updatedAt); assertEquals("", repository.observe("memo-book").first()?.content)
        withContext(Dispatchers.IO) { database.bookMemoDao.restore(listOf(BookMemo("memo-book", "Old backup", saved.updatedAt))) }
        assertEquals(cleared, repository.observe("memo-book").first())
    }
    @Test fun deletedBookCannotBeRecreatedBySavingMemo() = runBlocking {
        withContext(Dispatchers.IO) { database.bookDao.delete(checkNotNull(database.bookDao.getBook("memo-book"))) }
        assertTrue(runCatching { repository.save("memo-book", "late") }.isFailure)
        assertNull(repository.observe("memo-book").first())
    }
    @Test fun largeDraftRoundTripRejectsOlderWritesAndPersistsCancelTombstone() = runBlocking {
        val id = UUID.randomUUID().toString().also(ids::add); val body = "正文".repeat(200000)
        val draft = BookMemoDraft("memo-book", body, true, 8); repository.writeDraft(id, draft)
        repository.writeDraft(id, draft.copy(content = "stale", revision = 7)); assertEquals(draft, repository.readDraft(id))
        val canceled = draft.copy(content = "", editing = false, revision = 9); repository.writeDraft(id, canceled)
        repository.writeDraft(id, draft); assertEquals(canceled, repository.readDraft(id))
    }
}
