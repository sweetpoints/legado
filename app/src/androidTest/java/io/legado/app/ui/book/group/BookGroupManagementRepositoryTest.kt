package io.legado.app.ui.book.group

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.RoomBookGroupManagementRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookGroupManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBookGroupManagementRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomBookGroupManagementRepository(database)
        database.bookGroupDao.insert(
            BookGroup(-1, "all", order = 42),
            BookGroup(1, "one", "cover", 42, false, false, 5, true),
            BookGroup(2, "two", order = 42),
        )
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun togglingShowKeepsLatestEditorFieldsAndOrder() = runBlocking {
        val latest = BookGroup(1, "edited", "new-cover", 19, false, false, 4, true)
        database.bookGroupDao.update(latest)
        repository.setShown(1, true)
        assertEquals(latest.copy(show = true), database.bookGroupDao.getByID(1))
    }

    @Test
    fun deletedGroupCannotBeRecreatedByShowToggle() = runBlocking {
        database.bookGroupDao.delete(checkNotNull(database.bookGroupDao.getByID(1)))
        assertTrue(runCatching { repository.setShown(1, true) }.isFailure)
        assertNull(database.bookGroupDao.getByID(1))
    }

    @Test
    fun reorderPersistsActualOrderAndKeepsEveryOtherFieldIncludingSystemGroups() = runBlocking {
        val before = checkNotNull(database.bookGroupDao.getByID(1))
        repository.reorder(listOf(2, 1, -1, 2, 99))
        assertEquals(listOf(2L, 1L, -1L), database.bookGroupDao.all.map { it.groupId })
        assertEquals(listOf(1, 2, 3), database.bookGroupDao.all.map { it.order })
        assertEquals(before.copy(order = 2), database.bookGroupDao.getByID(1))
    }

    @Test
    fun newGroupsAreRetainedAndDeletedGroupsDoNotReturnAfterReorder() = runBlocking {
        database.bookGroupDao.delete(checkNotNull(database.bookGroupDao.getByID(1)))
        database.bookGroupDao.insert(BookGroup(4, "new", order = 100))
        repository.reorder(listOf(2, 1, -1))
        assertEquals(listOf(2L, -1L, 4L), database.bookGroupDao.all.map { it.groupId })
    }

    @Test
    fun failedReorderRollsBackEveryGroupOrder() = runBlocking {
        val before = database.bookGroupDao.all.map(BookGroupEditorSnapshot::from)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_manage_group_update BEFORE UPDATE ON book_groups WHEN NEW.groupId = 1 BEGIN SELECT RAISE(ABORT, 'rejected'); END"
        )
        assertTrue(runCatching { repository.reorder(listOf(2, 1, -1)) }.isFailure)
        assertEquals(before, database.bookGroupDao.all.map(BookGroupEditorSnapshot::from))
    }
}
