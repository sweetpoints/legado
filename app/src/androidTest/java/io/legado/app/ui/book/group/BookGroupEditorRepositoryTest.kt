package io.legado.app.ui.book.group

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.RoomBookGroupEditorRepository
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookGroupEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBookGroupEditorRepository

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = RoomBookGroupEditorRepository(context, database)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun editingPreservesLatestVisibilityAndOrderAndAllEditableFields() = runBlocking {
        val original = BookGroupEditorSnapshot(4, "old", "old", 7, true, true, -1, false)
        database.bookGroupDao.insert(original.entity())
        val loaded = repository.load(4)!!
        database.bookGroupDao.update(original.copy(order = 19, show = false).entity())
        val result =
            repository.save(
                loaded.copy(
                    name = "draft",
                    cover = "https://cover",
                    bookSort = 5,
                    enableRefresh = false,
                    onlyUpdateRead = true,
                ),
                true,
            )
        assertEquals(
            BookGroupEditorSnapshot(4, "draft", "https://cover", 19, false, false, 5, true),
            result,
        )
        assertEquals(result, repository.load(4))
    }

    @Test
    fun concurrentDeletionCannotBeRecreatedByExistingSave() = runBlocking {
        val original = BookGroupEditorSnapshot(4, "old")
        database.bookGroupDao.insert(original.entity())
        val loaded = repository.load(4)!!
        database.bookGroupDao.delete(original.entity())
        assertTrue(runCatching { repository.save(loaded.copy(name = "draft"), true) }.isFailure)
        assertNull(repository.load(4))
    }

    @Test
    fun newGroupAllocatesFirstUnusedBitAndClearsOnlyThatStaleMembership() = runBlocking {
        database.bookGroupDao.insert(BookGroup(1, "existing", order = 17))
        database.bookDao.insert(Book(bookUrl = "test", name = "book", group = 2L or 4L))
        val added =
            repository.save(
                BookGroupEditorSnapshot(
                    0,
                    "new",
                    bookSort = 5,
                    enableRefresh = false,
                    onlyUpdateRead = true,
                ),
                false,
            )
        assertEquals(2L, added.id)
        assertEquals(18, added.order)
        assertTrue(added.show)
        assertEquals(4L, database.bookDao.getBook("test")!!.group)
        assertEquals(added, repository.load(2))
    }

    @Test
    fun all63PositiveGroupsEnforceLimitAndConcurrentAddsUseDistinctBits() = runBlocking {
        val added = coroutineScope {
            listOf(
                    async { repository.save(BookGroupEditorSnapshot(0, "one"), false) },
                    async { repository.save(BookGroupEditorSnapshot(0, "two"), false) },
                )
                .awaitAll()
        }
        assertEquals(setOf(1L, 2L), added.map { it.id }.toSet())
        database.bookGroupDao.insert(
            *(0..62).map { BookGroup(1L shl it, "group $it") }.toTypedArray()
        )
        assertFalse(database.bookGroupDao.canAddGroup)
        assertTrue(
            runCatching { repository.save(BookGroupEditorSnapshot(0, "too many"), false) }.isFailure
        )
        assertEquals(63, database.bookGroupDao.all.size)
    }

    @Test
    fun deletionClearsOnlyChosenBitIncludingLegacySignBitAndSystemGroupIsProtected() = runBlocking {
        database.bookGroupDao.insert(
            BookGroup(2, "two"),
            BookGroup(Long.MIN_VALUE, "legacy"),
            BookGroup(-1, "all"),
        )
        database.bookDao.insert(Book(bookUrl = "test", group = Long.MIN_VALUE or 2 or 4))
        repository.delete(2)
        assertEquals(Long.MIN_VALUE or 4, database.bookDao.getBook("test")!!.group)
        repository.delete(Long.MIN_VALUE)
        assertEquals(4L, database.bookDao.getBook("test")!!.group)
        assertTrue(runCatching { repository.delete(-1) }.isFailure)
        assertNotNull(repository.load(-1))
    }

    @Test
    fun failedInsertRollsBackStaleMembershipCleanupAndGroupCreation() = runBlocking {
        database.bookDao.insert(Book(bookUrl = "test", group = 1L or 4L))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_group_insert BEFORE INSERT ON book_groups WHEN NEW.groupName = 'rejected' BEGIN SELECT RAISE(ABORT, 'rejected'); END"
        )
        assertTrue(
            runCatching { repository.save(BookGroupEditorSnapshot(0, "rejected"), false) }.isFailure
        )
        assertEquals(5L, database.bookDao.getBook("test")!!.group)
        assertTrue(database.bookGroupDao.all.isEmpty())
    }

    @Test
    fun failedBookCleanupRollsBackGroupDeletion() = runBlocking {
        val group = BookGroup(2, "two")
        database.bookGroupDao.insert(group)
        database.bookDao.insert(Book(bookUrl = "test", group = 2L or 4L))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_group_cleanup BEFORE UPDATE ON books BEGIN SELECT RAISE(ABORT, 'rejected'); END"
        )
        assertTrue(runCatching { repository.delete(2) }.isFailure)
        assertEquals(group, repository.load(2)!!.entity())
        assertEquals(6L, database.bookDao.getBook("test")!!.group)
    }

    @Test
    fun coverImportCopiesBytesByMd5RetainsNinePatchSuffixAndRemoteUrlWithoutCopy() = runBlocking {
        val source = File(context.cacheDir, "group-cover-${System.nanoTime()}.9.png")
        source.writeBytes(byteArrayOf(1, 2, 3) + System.nanoTime().toString().toByteArray())
        var imported: File? = null
        try {
            val path = repository.importCover(Uri.fromFile(source).toString())
            imported = File(path)
            val expected = source.inputStream().use(MD5Utils::md5Encode) + ".9.png"
            assertEquals(expected, imported.name)
            assertEquals(File(context.externalFiles, "covers"), imported.parentFile)
            assertArrayEquals(source.readBytes(), imported.readBytes())
            assertEquals(path, repository.importCover(Uri.fromFile(source).toString()))
            assertEquals(
                "https://example.invalid/cover.png?x=1",
                repository.importCover("https://example.invalid/cover.png?x=1"),
            )
            assertTrue(
                imported.parentFile!!.listFiles().orEmpty().none {
                    it.name.startsWith("group_cover_")
                }
            )
        } finally {
            source.delete()
            imported?.delete()
        }
    }
}
