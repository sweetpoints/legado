package io.legado.app.ui.book.group

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.RoomBookGroupSelectionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookGroupSelectionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBookGroupSelectionRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomBookGroupSelectionRepository(database)
        database.bookGroupDao.insert(
            BookGroup(-1, "all", order = 19),
            BookGroup(Long.MIN_VALUE, "legacy", order = 23),
            BookGroup(0, "zero", order = 42),
            BookGroup(1, "one", "cover", 42, false, false, 5, true),
            BookGroup(2, "two", order = 42),
        )
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun observeSelectIncludesZeroAndHiddenPositiveGroupsButExcludesAllNegativeGroups() =
        runBlocking {
            val rows = repository.observe().first()
            assertEquals(listOf(0L, 1L, 2L), rows.map { it.id })
            assertFalse(rows.first { it.id == 1L }.show)
        }

    @Test
    fun reorderOnlySelectGroupsPreservesAllSystemFieldsAndOrders() = runBlocking {
        val system = checkNotNull(database.bookGroupDao.getByID(-1))
        val legacy = checkNotNull(database.bookGroupDao.getByID(Long.MIN_VALUE))
        val editable = checkNotNull(database.bookGroupDao.getByID(1))
        repository.reorder(listOf(2, 1, 0, -1, Long.MIN_VALUE, 2))
        assertEquals(system, database.bookGroupDao.getByID(-1))
        assertEquals(legacy, database.bookGroupDao.getByID(Long.MIN_VALUE))
        assertEquals(listOf(2L, 1L, 0L), repository.observe().first().map { it.id })
        assertEquals(editable.copy(order = 2), database.bookGroupDao.getByID(1))
    }

    @Test
    fun reorderUsesLatestEditedFieldsAndRetainsNewGroupsWithoutRecreatingDeletedGroups() =
        runBlocking {
            database.bookGroupDao.delete(checkNotNull(database.bookGroupDao.getByID(0)))
            database.bookGroupDao.update(
                BookGroup(1, "edited", "new-cover", 7, true, true, 3, false)
            )
            database.bookGroupDao.insert(BookGroup(4, "new", order = 100))
            repository.reorder(listOf(2, 0, 1))
            assertNull(database.bookGroupDao.getByID(0))
            assertEquals(listOf(2L, 1L, 4L), repository.observe().first().map { it.id })
            val current = checkNotNull(database.bookGroupDao.getByID(1))
            assertEquals("edited", current.groupName)
            assertEquals("new-cover", current.cover)
            assertEquals(3, current.bookSort)
        }

    @Test
    fun failedReorderRollsBackEverySelectGroupAndKeepsSystems() = runBlocking {
        val before = database.bookGroupDao.all.map(BookGroupEditorSnapshot::from)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_group_select_update BEFORE UPDATE ON book_groups WHEN NEW.groupId = 1 BEGIN SELECT RAISE(ABORT, 'rejected'); END"
        )
        assertTrue(runCatching { repository.reorder(listOf(2, 1, 0)) }.isFailure)
        assertEquals(before, database.bookGroupDao.all.map(BookGroupEditorSnapshot::from))
    }
}
