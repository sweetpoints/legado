package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.book.toc.TocListItem
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.*
import org.junit.Assert.*

class TocChapterRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppTocChapterRepository
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "chapter-state-${UUID.randomUUID()}",
        )
    private val book =
        Book(
            bookUrl = "book",
            name = "Book",
            origin = "remote",
            totalChapterNum = 3,
            durChapterIndex = 1,
        )

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = AppTocChapterRepository(database, directory)
        runBlocking(Dispatchers.IO) {
            database.bookDao.insert(book, book.copy(bookUrl = "other"))
            database.bookChapterDao.insert(
                BookChapter(
                    url = "volume",
                    bookUrl = "book",
                    index = 0,
                    isVolume = true,
                    title = "Volume",
                ),
                BookChapter(
                    url = "one",
                    bookUrl = "book",
                    index = 1,
                    title = "Same",
                    wordCount = "10",
                ),
                BookChapter(url = "two", bookUrl = "book", index = 2, title = "Same", isVip = true),
                BookChapter(url = "beyond", bookUrl = "book", index = 3, title = "Beyond"),
                BookChapter(url = "foreign", bookUrl = "other", index = 0, title = "Same"),
            )
        }
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun actualLoadAndSearchKeepOwnerRangeIndexAndSqlWildcardContract() = runBlocking {
        val parameters = TocChapterParameters(book)
        assertEquals(listOf(0, 1, 2), repository.load(parameters).chapters.map { it.index })
        assertEquals(listOf(1, 2), repository.search(parameters, "Same"))
        assertEquals(listOf(0, 1, 2), repository.search(parameters, "%"))
        assertEquals(
            listOf(0),
            repository.search(parameters.copy(book = book.copy(bookUrl = "other")), "Same"),
        )
    }

    @Test
    fun resolveReReadsChapterIdentityAndRefusesForeignOrDeletedTargets() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookChapterDao.insert(
                BookChapter(url = "one", bookUrl = "book", index = 4, title = "Latest")
            )
        }
        assertEquals(4, repository.resolve(book, "one")!!.index)
        assertTrue(repository.resolve(book, "one")!!.changed)
        assertNull(repository.resolve(book.copy(bookUrl = "other"), "one"))
        assertNull(repository.resolve(book, "missing"))
    }

    @Test
    fun videoNavigationPreservesVolumeAndWithinVolumeMetadata() = runBlocking {
        val video = book.copy(type = BookType.video)
        val chapter = repository.resolve(video, "two")!!
        assertEquals(0, chapter.volumeIndex)
        assertEquals(1, chapter.chapterInVolume)
        val volume = repository.resolve(video, "volume")!!
        assertEquals(0, volume.volumeIndex)
        assertEquals(0, volume.chapterInVolume)
        val withoutVolumes = repository.resolve(video.copy(bookUrl = "other"), "foreign")!!
        assertEquals(0, withoutVolumes.chapterInVolume)
    }

    @Test
    fun sameRawTitlesRetainSeparateItemKeysAndNativeTitleFormattingPipeline() = runBlocking {
        val chapters = repository.load(TocChapterParameters(book)).chapters.drop(1)
        val items = chapters.map { TocListItem.Chapter(it, 0) }
        val titles = repository.titles(book, items).toList()
        assertEquals(listOf("chapter:1", "chapter:2"), titles.map { it.first })
        assertEquals(repository.title(book, items.first()), titles.first().second)
    }

    @Test
    fun privateDiskCheckpointRestoresFullUnboundedQueryAndCollapseAndRejectsStaleRevision() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            val value =
                TocChapterCheckpoint(
                    TocChapterParameters(
                        book.copy(name = "Name".repeat(50000)),
                        "Query".repeat(100000),
                    ),
                    5,
                    setOf(0, 3),
                    setOf(2),
                )
            repository.checkpoint(session, value)
            repository.checkpoint(session, value.copy(revision = 1))
            assertEquals(value, AppTocChapterRepository(database, directory).checkpoint(session))
            assertTrue(runCatching { repository.checkpoint("../escape", value) }.isFailure)
        }

    @Test
    fun releasedOwnerPreventsLateWritesFromRecreatingFullBookAndQuery() = runBlocking {
        val session = UUID.randomUUID().toString()
        val value = TocChapterCheckpoint(TocChapterParameters(book), 1)
        repository.checkpoint(session, value)
        repository.release(session)
        repository.checkpoint(session, value.copy(revision = 100))
        assertNull(repository.checkpoint(session))
        assertFalse(File(directory, "$session.json").exists())
    }
}
