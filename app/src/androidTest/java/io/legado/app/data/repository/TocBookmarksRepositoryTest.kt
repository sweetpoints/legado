package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Bookmark
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class TocBookmarksRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repo: RoomTocBookmarksRepository
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "toc-state-${UUID.randomUUID()}",
        )

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repo = RoomTocBookmarksRepository(database, directory)
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    private val parameters = TocBookmarksParameters("Book", "Author")

    @Test
    fun blankSearchUsesBookAndAuthorScopeAndActualChapterIndexOrder() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookmarkDao.insert(
                Bookmark(1, "Book", "Author", 4, chapterName = "Four"),
                Bookmark(2, "Book", "Author", 1, chapterName = "One"),
                Bookmark(3, "Book", "Other", 0),
                Bookmark(4, "Other", "Author", 0),
            )
        }
        assertEquals(listOf(2L, 1L), repo.observe(parameters).first().map { it.id })
        assertEquals(
            listOf(2L, 1L),
            repo.observe(parameters.copy(search = "  ")).first().map { it.id },
        )
    }

    @Test
    fun actualSearchIncludesChapterAndSummaryButNotOriginalAndPreservesSqlWildcardBehavior() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.bookmarkDao.insert(
                    Bookmark(1, "Book", "Author", 1, chapterName = "Match title"),
                    Bookmark(2, "Book", "Author", 2, content = "Match note"),
                    Bookmark(3, "Book", "Author", 3, bookText = "Match original"),
                    Bookmark(4, "Book", "Other", 0, content = "Match"),
                )
            }
            assertEquals(
                listOf(1L, 2L),
                repo.observe(parameters.copy(search = "Match")).first().map { it.id },
            )
            assertEquals(3, repo.observe(parameters.copy(search = "%")).first().size)
        }

    @Test
    fun resolveReReadsFullMetadataAndRefusesDeletedOrForeignScopeIds() = runBlocking {
        val row = Bookmark(7, "Book", "Author", 9, 28, "Chapter", "Full".repeat(50000), "Note")
        withContext(Dispatchers.IO) {
            database.bookmarkDao.insert(row)
            database.bookmarkDao.update(row.copy(chapterPos = 88, content = "Latest"))
        }
        val resolved = repo.resolve(parameters, 7)!!
        assertEquals(88, resolved.chapterPos)
        assertEquals(200000, resolved.bookText.length)
        assertEquals("Latest", resolved.content)
        assertNull(repo.resolve(parameters.copy(author = "Other"), 7))
        withContext(Dispatchers.IO) { database.bookmarkDao.delete(row) }
        assertNull(repo.resolve(parameters, 7))
    }

    @Test
    fun projectionUsesOldSingleLineTransformationAndNeverSplitsSurrogatePair() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookmarkDao.insert(
                Bookmark(
                    8,
                    "Book",
                    "Author",
                    chapterName = "One\nTwo\rThree",
                    bookText = "x".repeat(511) + "😀" + "more",
                )
            )
        }
        val row = repo.observe(parameters).first().single()
        assertEquals("One Two\uFEFFThree", row.chapter)
        assertEquals("x".repeat(511), row.original)
    }

    @Test
    fun actualDiskCheckpointRestoresFullQueryAndRejectsLateStaleWrites() = runBlocking {
        val session = UUID.randomUUID().toString()
        val full =
            TocBookmarksCheckpoint(
                parameters.copy(
                    name = "Name".repeat(50000),
                    author = "Author".repeat(30000),
                    search = "Query".repeat(50000),
                ),
                7,
            )
        repo.checkpoint(session, full)
        repo.checkpoint(session, TocBookmarksCheckpoint(parameters, 2))
        assertEquals(full, RoomTocBookmarksRepository(database, directory).checkpoint(session))
        assertTrue(runCatching { repo.checkpoint("../escape", full) }.isFailure)
    }

    @Test
    fun releasedSessionRejectsLateWritesAndDoesNotRecreateFullQueryFile() = runBlocking {
        val session = UUID.randomUUID().toString()
        val value = TocBookmarksCheckpoint(parameters.copy(search = "Large".repeat(50000)), 9)
        repo.checkpoint(session, value)
        repo.release(session)
        repo.release(session)
        repo.checkpoint(session, value.copy(revision = 1000))
        assertNull(repo.checkpoint(session))
        assertFalse(File(directory, "$session.json").exists())
        assertTrue(File(directory, "$session.released").isFile)
    }
}
