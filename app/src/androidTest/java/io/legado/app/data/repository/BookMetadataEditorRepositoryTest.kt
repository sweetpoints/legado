package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class BookMetadataEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory =
            File(context.cacheDir, "book-metadata-test-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo(move: (Book, Book) -> Unit = { _, _ -> }) =
        RoomBookMetadataEditorRepository(database, move)

    private suspend fun insert(book: Book) =
        withContext(Dispatchers.IO) { database.bookDao.insert(book) }

    private fun input(
        book: Book,
        changed: Set<BookMetadataField> = BookMetadataField.entries.toSet(),
    ) =
        BookMetadataInput(
            book.bookUrl,
            "Edited",
            "Edited author",
            1,
            "new-cover",
            "new-intro",
            changed,
        )

    @Test
    fun saveMergesLatestProgressMetadataAndSynchronizesHighlightLabelsInSameTransaction() =
        runBlocking {
            val book =
                Book(
                    bookUrl = "book",
                    name = "Original",
                    author = "Author",
                    coverUrl = "old source",
                    type = BookType.text,
                )
            insert(book)
            val loaded = repo().load("book")!!
            val fresh =
                book.copy(
                    durChapterIndex = 9,
                    durChapterPos = 42,
                    latestChapterTitle = "Fresh chapter",
                    totalChapterNum = 99,
                    group = 123,
                    order = 77,
                    variable = "fresh variable",
                    coverUrl = "fresh source",
                    intro = "fresh intro",
                    type = BookType.image or BookType.local or BookType.archive,
                )
            insert(fresh)
            withContext(Dispatchers.IO) {
                database.bookHighlightDao.insert(
                    BookHighlight(
                        time = 1,
                        bookUrl = "book",
                        bookName = "Original",
                        bookAuthor = "Author",
                        note = "unchanged",
                    )
                )
            }
            val saved =
                repo().save(input(book, setOf(BookMetadataField.Name, BookMetadataField.Author))) {}
            val actual = withContext(Dispatchers.IO) { database.bookDao.getBook("book")!! }
            assertEquals("Edited", saved.name)
            assertEquals(9, actual.durChapterIndex)
            assertEquals(42, actual.durChapterPos)
            assertEquals("Fresh chapter", actual.latestChapterTitle)
            assertEquals(123L, actual.group)
            assertEquals(77, actual.order)
            assertEquals("fresh variable", actual.variable)
            assertEquals("fresh source", actual.coverUrl)
            assertEquals("fresh intro", actual.intro)
            assertEquals(fresh.type, actual.type)
            assertEquals("Original", loaded.name)
            val highlight =
                withContext(Dispatchers.IO) { database.bookHighlightDao.getByBook("book").single() }
            assertEquals("Edited", highlight.bookName)
            assertEquals("Edited author", highlight.bookAuthor)
            assertEquals("unchanged", highlight.note)
        }

    @Test
    fun loadThenDeleteCannotResurrectBookOrTouchCache() = runBlocking {
        val book = Book(bookUrl = "book", name = "Original")
        insert(book)
        val moves = mutableListOf<String>()
        val repo = repo { _, next -> moves += next.name }
        assertNotNull(repo.load("book"))
        withContext(Dispatchers.IO) { database.bookDao.delete(book) }
        assertTrue(
            runCatching { repo.save(input(book)) {} }.exceptionOrNull() is BookMetadataMissing
        )
        assertNull(repo.load("book"))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun duplicateNameAuthorConstraintRollsBackBookHighlightAndDoesNotMoveCache() = runBlocking {
        val book = Book(bookUrl = "book", name = "Original", author = "Author")
        insert(book)
        insert(Book(bookUrl = "other", name = "Edited", author = "Edited author"))
        withContext(Dispatchers.IO) {
            database.bookHighlightDao.insert(
                BookHighlight(
                    time = 1,
                    bookUrl = "book",
                    bookName = "Original",
                    bookAuthor = "Author",
                )
            )
        }
        var moves = 0
        assertTrue(runCatching { repo { _, _ -> moves++ }.save(input(book)) {} }.isFailure)
        assertEquals("Original", repo().load("book")!!.name)
        assertEquals(0, moves)
        assertEquals(
            "Original",
            withContext(Dispatchers.IO) {
                database.bookHighlightDao.getByBook("book").single().bookName
            },
        )
    }

    @Test
    fun durablePlanFailureBeforeRoomWriteLeavesBookAndFilesUntouched() = runBlocking {
        val book = Book(bookUrl = "book", name = "Original")
        insert(book)
        var moves = 0
        assertTrue(
            runCatching { repo { _, _ -> moves++ }.save(input(book)) { error("disk full") } }
                .isFailure
        )
        assertEquals("Original", repo().load("book")!!.name)
        assertEquals(0, moves)
    }

    @Test
    fun cacheFailureAfterRoomCommitCanRecoverPlannedOriginalFolderWithoutAnotherBookMutation() =
        runBlocking {
            val book = Book(bookUrl = "book", name = "Original", author = "Author")
            insert(book)
            var plan: BookMetadataSave? = null
            var fail = true
            val pairs = mutableListOf<Pair<String, String>>()
            val repo = repo { before, target ->
                pairs += before.name to target.name
                if (fail) error("cache failed")
            }
            assertTrue(runCatching { repo.save(input(book)) { plan = it } }.isFailure)
            assertEquals("Edited", repo.load("book")!!.name)
            fail = false
            val recovered = repo.recover(checkNotNull(plan))
            assertEquals("Edited", recovered.name)
            assertEquals(listOf("Original" to "Edited", "Original" to "Edited"), pairs)
            assertEquals(
                checkNotNull(plan).targetJson,
                withContext(Dispatchers.IO) { GSON.toJson(database.bookDao.getBook("book")) },
            )
        }

    @Test
    fun recoveryRejectsExternalMetadataChangeAndDeletedTargetWithoutOverwritingOrMoving() =
        runBlocking {
            val book = Book(bookUrl = "book", name = "Original")
            insert(book)
            var plan: BookMetadataSave? = null
            assertTrue(
                runCatching {
                    repo { _, _ -> error("post commit failure") }.save(input(book)) { plan = it }
                }
                    .isFailure
            )
            val saved = withContext(Dispatchers.IO) { database.bookDao.getBook("book")!! }
            insert(saved.copy(author = "External author", durChapterPos = 999))
            var moves = 0
            val repo = repo { _, _ -> moves++ }
            assertTrue(
                runCatching { repo.recover(checkNotNull(plan)) }.exceptionOrNull()
                    is BookMetadataConflict
            )
            assertEquals("External author", repo.load("book")!!.author)
            assertEquals(0, moves)
            withContext(Dispatchers.IO) { database.bookDao.delete(saved) }
            assertTrue(
                runCatching { repo.recover(checkNotNull(plan)) }.exceptionOrNull()
                    is BookMetadataMissing
            )
            assertNull(repo.load("book"))
            assertEquals(0, moves)
        }

    @Test
    fun realCacheRenameUsesLatestRoomNameAndRunsOnlyAfterCommittedMetadata() = runBlocking {
        val book = Book(bookUrl = "book", name = "Original", author = "Author")
        insert(book)
        val loaded = repo().load("book")!!
        val fresh = book.copy(name = "Concurrent name")
        insert(fresh)
        val original = File(directory, fresh.getFolderNameNoCache()).apply { mkdirs() }
        File(original, "chapter.txt").writeText("cached chapter")
        val repo = repo { before, target ->
            assertEquals("Concurrent name", before.name)
            assertEquals("Edited", database.bookDao.getBook("book")!!.name)
            val old = File(directory, before.getFolderNameNoCache())
            val next = File(directory, target.getFolderNameNoCache())
            if (old.exists()) check(old.renameTo(next))
        }
        val saved = repo.save(input(book, setOf(BookMetadataField.Name))) {}
        val target = File(directory, fresh.copy(name = saved.name).getFolderNameNoCache())
        assertFalse(original.exists())
        assertEquals("cached chapter", File(target, "chapter.txt").readText())
        assertEquals("Original", loaded.name)
    }

    @Test
    fun coverChangeAndExplicitRefreshClearPersistedCacheButUnchangedCoverKeepsIt() = runBlocking {
        val book =
            Book(
                bookUrl = "book",
                name = "Original",
                coverUrl = "network",
                persistedCoverUrl = "cached",
                type = BookType.text,
            )
        insert(book)
        val same = input(book, setOf(BookMetadataField.Cover)).copy(cover = "network")
        assertEquals("cached", repo().save(same) {}.persistedCoverUrl)
        assertNull(repo().save(same.copy(refreshCover = true)) {}.persistedCoverUrl)
        insert(book)
        assertNull(repo().save(same.copy(cover = "new")) {}.persistedCoverUrl)
    }
}
