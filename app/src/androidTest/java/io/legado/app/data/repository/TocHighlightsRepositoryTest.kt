package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class TocHighlightsRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repo: RoomTocHighlightsRepository
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "highlight-state-${UUID.randomUUID()}",
        )
    private val parameters = TocHighlightsParameters("book")

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repo = RoomTocHighlightsRepository(database, directory)
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    private suspend fun insert(vararg rows: BookHighlight) =
        withContext(Dispatchers.IO) { database.bookHighlightDao.insert(*rows) }

    @Test
    fun actualOwnerSearchMatchesChapterOriginalNoteAndSqlWildcards() = runBlocking {
        insert(
            BookHighlight(time = 1, bookUrl = "book", chapterName = "Match"),
            BookHighlight(time = 2, bookUrl = "book", bookText = "Match"),
            BookHighlight(time = 3, bookUrl = "book", note = "Match"),
            BookHighlight(time = 4, bookUrl = "foreign", note = "Match"),
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            repo.observe(parameters.copy(search = "Match")).first().map { it.id },
        )
        assertEquals(3, repo.observe(parameters.copy(search = "%")).first().size)
        assertEquals(3, repo.observe(parameters.copy(search = " ")).first().size)
        assertTrue(repo.observe(parameters.copy(supported = false)).first().isEmpty())
    }

    @Test
    fun currentChapterUrlsThenBodyPositionAndTimeDetermineOrdering() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookDao.insert(Book(bookUrl = "book"))
            database.bookChapterDao.insert(
                BookChapter(url = "first", bookUrl = "book", index = 1),
                BookChapter(url = "later", bookUrl = "book", index = 5),
            )
        }
        insert(
            BookHighlight(time = 9, bookUrl = "book", chapterUrl = "later", chapterIndex = 0),
            BookHighlight(
                time = 3,
                bookUrl = "book",
                chapterUrl = "first",
                chapterPos = 20,
                layoutTitleLength = 19,
            ),
            BookHighlight(
                time = 2,
                bookUrl = "book",
                chapterUrl = "first",
                chapterPos = 4,
                layoutTitleLength = 3,
            ),
            BookHighlight(
                time = 1,
                bookUrl = "book",
                chapterUrl = "first",
                chapterPos = 3,
                layoutTitleLength = 1,
            ),
            BookHighlight(time = 7, bookUrl = "book", chapterUrl = "missing"),
        )
        assertEquals(listOf(2L, 3L, 1L, 9L, 7L), repo.observe(parameters).first().map { it.id })
        assertNull(repo.observe(parameters).first().last().chapterIndex)
    }

    @Test
    fun resolveReadsLatestFullMetadataAndFreshChapterMapWithoutForeignOrDeletedTargets() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.bookDao.insert(Book(bookUrl = "book"))
                database.bookChapterDao.insert(
                    BookChapter(url = "chapter", bookUrl = "book", index = 8)
                )
            }
            val row =
                BookHighlight(
                    time = 7,
                    bookUrl = "book",
                    chapterUrl = "chapter",
                    chapterPos = 9,
                    bookText = "Full".repeat(50000),
                )
            insert(row.copy(chapterPos = 88, note = "Latest"))
            val result = repo.resolve(parameters, 7)!!
            assertEquals(8, result.chapterIndex)
            assertEquals(88, result.highlight.chapterPos)
            assertEquals(200000, result.highlight.bookText.length)
            assertNull(repo.resolve(parameters.copy(bookUrl = "Other"), 7))
            assertNull(repo.resolve(parameters.copy(supported = false), 7))
            withContext(Dispatchers.IO) { database.bookHighlightDao.delete(row) }
            assertNull(repo.resolve(parameters, 7))
        }

    @Test
    fun orphanStillHasFullEditableMetadataButNoReadingIndexAndLegacyIndexRemainsUsable() =
        runBlocking {
            insert(
                BookHighlight(time = 1, bookUrl = "book", chapterUrl = "missing", note = "Keep"),
                BookHighlight(time = 2, bookUrl = "book", chapterIndex = 6),
            )
            assertNull(repo.resolve(parameters, 1)!!.chapterIndex)
            assertEquals("Keep", repo.resolve(parameters, 1)!!.highlight.note)
            assertEquals(6, repo.resolve(parameters, 2)!!.chapterIndex)
        }

    @Test
    fun singleLineProjectionRetainsColorAndDoesNotSplitSurrogate() = runBlocking {
        val row =
            BookHighlight(
                time = 1,
                bookUrl = "book",
                chapterName = "One\nTwo\rThree",
                bookText = "x".repeat(511) + "😀",
                note = "Note",
            )
        row.applyStyle(io.legado.app.help.HighlightStyle(fill = 0xff123456.toInt()))
        insert(row)
        val projected = repo.observe(parameters).first().single()
        assertEquals("One Two\uFEFFThree", projected.chapter)
        assertEquals("x".repeat(511), projected.original)
        assertEquals(0xff123456.toInt(), projected.color)
    }

    @Test
    fun fullLargeQueryDiskCheckpointRejectsLateRevisionAndInvalidIds() = runBlocking {
        val session = UUID.randomUUID().toString()
        val value =
            TocHighlightsCheckpoint(
                parameters.copy(bookUrl = "URL".repeat(100000), search = "Query".repeat(100000)),
                8,
            )
        repo.checkpoint(session, value)
        repo.checkpoint(session, value.copy(revision = 1))
        assertEquals(value, RoomTocHighlightsRepository(database, directory).checkpoint(session))
        assertTrue(runCatching { repo.checkpoint("../escape", value) }.isFailure)
    }

    @Test
    fun releaseFenceRejectsLateWritesAndNeverRecreatesQueryFile() = runBlocking {
        val session = UUID.randomUUID().toString()
        val value = TocHighlightsCheckpoint(parameters, 1)
        repo.checkpoint(session, value)
        repo.release(session)
        repo.release(session)
        repo.checkpoint(session, value.copy(revision = 20))
        assertNull(repo.checkpoint(session))
        assertFalse(File(directory, "$session.json").exists())
    }
}
