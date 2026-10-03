package io.legado.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Bookmark
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookmarkEditorRepositoryTest {
    private lateinit var database: AppDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = mutableListOf<String>()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun cleanup() {
        database.close()
        ids.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
    }

    private fun stage(bookmark: Bookmark, editPos: Int = 0) =
        FileBookmarkEditorRepository.stage(context, BookmarkEditorSeed.from(bookmark, editPos))
            .also { ids += it }

    @Test
    fun stagingCapturesImmutableMetadataAndLargeDraftDoesNotTouchActualRoomBeforeConfirm() =
        runBlocking {
            val bookmark =
                Bookmark(
                    42,
                    "Book",
                    "Author",
                    7,
                    15,
                    "Chapter",
                    "Large ".repeat(30000),
                    "Original note",
                )
            val id = stage(bookmark)
            bookmark.chapterPos = 99
            bookmark.content = "Mutated outside"
            val repository = FileBookmarkEditorRepository(context, database)
            val initial = repository.load(id)
            assertEquals(15, initial.seed.chapterPos)
            assertEquals("Original note", initial.content)
            val edited = initial.copy(bookText = "New text", content = "New note", revision = 2)
            repository.write(id, edited)
            repository.write(id, initial.copy(revision = 1))
            assertEquals(edited, repository.load(id))
            withContext(Dispatchers.IO) { assertTrue(database.bookmarkDao.all.isEmpty()) }
        }

    @Test
    fun realRoomInsertKeepsAllMetadataAndSuccessfulSessionCannotOverwriteLaterUpdates() =
        runBlocking {
            val original = Bookmark(52, "Book", "Author", 3, 17, "Chapter name", "Original", "Note")
            withContext(Dispatchers.IO) { database.bookmarkDao.insert(original) }
            val id = stage(original)
            val repository = FileBookmarkEditorRepository(context, database)
            val loaded = repository.load(id)
            // The persisted seed is authoritative even if an erroneous caller changes its draft
            // metadata.
            val edited =
                loaded.copy(
                    seed = loaded.seed.copy(bookName = "Wrong", chapterPos = 999),
                    bookText = "",
                    content = " Edited ",
                    revision = 1,
                )
            repository.commit(id, edited, false)
            val expected = original.copy(bookText = "", content = " Edited ")
            withContext(Dispatchers.IO) {
                assertEquals(listOf(expected), database.bookmarkDao.all)
                database.bookmarkDao.update(expected.copy(content = "Later"))
            }
            repository.commit(id, edited, false)
            assertTrue(repository.load(id).finished)
            withContext(Dispatchers.IO) {
                assertEquals("Later", database.bookmarkDao.all.single().content)
            }
        }

    @Test
    fun deleteOnlyEditingSessionRemovesExactPrimaryKeyAndKeepsOtherBookmarks() = runBlocking {
        val original = Bookmark(61, "Book", "Author", 1, 1, "One", "Text", "Note")
        val other = original.copy(time = 62, chapterName = "Two")
        withContext(Dispatchers.IO) { database.bookmarkDao.insert(original, other) }
        val freshId = stage(original, -1)
        val repository = FileBookmarkEditorRepository(context, database)
        assertTrue(
            runCatching { repository.commit(freshId, repository.load(freshId), true) }.isFailure
        )
        val editId = stage(original, 0)
        repository.commit(editId, repository.load(editId), true)
        repository.write(editId, repository.load(editId).copy(finished = false, revision = 999))
        assertTrue(repository.load(editId).finished)
        withContext(Dispatchers.IO) { assertEquals(listOf(other), database.bookmarkDao.all) }
    }

    @Test
    fun initialDiskFailureCanRetrySameRequestWithoutLosingConstructorMetadata() = runBlocking {
        val root =
            File(context.cacheDir, "bookmark-stage-test-${java.util.UUID.randomUUID()}").apply {
                mkdirs()
            }
        val isolatedContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = root
            }
        val directory = File(root, "bookmark-editor").apply { writeText("Blocks mkdir") }
        val original =
            Bookmark(72, "Saved book", "Author", 8, 21, "Chapter", "Original text", "Original note")
        try {
            val id =
                FileBookmarkEditorRepository.stage(
                    isolatedContext,
                    BookmarkEditorSeed.from(original, 4),
                )
            val repository = FileBookmarkEditorRepository(isolatedContext, database)
            assertTrue(runCatching { repository.load(id) }.isFailure)
            assertTrue(directory.delete())
            val restored = repository.load(id)
            assertEquals(original, restored.seed.bookmark())
            assertEquals(4, restored.seed.editPos)
            assertEquals(original.bookText, restored.bookText)
            assertEquals(original.content, restored.content)
            assertEquals(restored, repository.load(id))
            withContext(Dispatchers.IO) { assertTrue(database.bookmarkDao.all.isEmpty()) }
        } finally {
            root.deleteRecursively()
        }
    }
}
