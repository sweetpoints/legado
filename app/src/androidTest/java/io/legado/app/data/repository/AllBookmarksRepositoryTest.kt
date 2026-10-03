package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class AllBookmarksRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repo: AppAllBookmarksRepository
    private val directory = File(context.cacheDir, "all-bookmarks-${UUID.randomUUID()}")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = AppAllBookmarksRepository(database) { 1700000000000L }
        directory.mkdirs()
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun actualRoomSortAndSmallProjectionKeepLargeOriginalOutOfRows() = runBlocking {
        val first = Bookmark(1, "A", "Author", 1, 2, "Chapter", "x".repeat(511) + "😀" + "more", "")
        val last = Bookmark(2, "B", "Author", 3, 8, "Other", "", "Note")
        val earlier = first.copy(time = 3, chapterIndex = 0, chapterPos = 99)
        withContext(Dispatchers.IO) { database.bookmarkDao.insert(last, first, earlier) }
        val rows = repo.observe().first()
        assertEquals(listOf(3L, 1L, 2L), rows.map { it.id })
        assertEquals("x".repeat(511), rows[1].original)
        assertEquals("", rows[1].content)
        assertEquals("A(Author)", rows[1].group)
    }

    @Test
    fun resolveReReadsLatestMetadataFindsBookByNameAuthorAndLongPressAlwaysEdits() = runBlocking {
        val original =
            Bookmark(12, "Book", "Author", 4, 19, "Chapter", "Full".repeat(50000), "Note")
        val book = Book(bookUrl = "reader://book", name = "Book", author = "Author")
        withContext(Dispatchers.IO) {
            database.bookmarkDao.insert(original)
            database.bookDao.insert(book)
            database.bookmarkDao.update(original.copy(chapterPos = 29, content = "Updated"))
        }
        val read = repo.resolve(12, false)!!
        assertEquals("reader://book", read.book!!.bookUrl)
        assertEquals(29, read.bookmark.chapterPos)
        assertEquals("Updated", read.bookmark.content)
        assertEquals(200000, read.bookmark.bookText.length)
        assertNull(repo.resolve(12, true)!!.book)
        withContext(Dispatchers.IO) { database.bookDao.delete(book) }
        assertNull(repo.resolve(12, false)!!.book)
        withContext(Dispatchers.IO) { database.bookmarkDao.delete(original) }
        assertNull(repo.resolve(12, false))
    }

    @Test
    fun actualJsonAndMarkdownExportsKeepFullMetadataAndOldDocumentFormat() = runBlocking {
        val row = Bookmark(32, "Book", "Author", 4, 27, "Chapter", "Original\ntext", "Summary")
        withContext(Dispatchers.IO) { database.bookmarkDao.insert(row) }
        val json = repo.export(directory.toURI().toString(), false)
        val md = repo.export(directory.toURI().toString(), true)
        assertTrue(json.startsWith("bookmark-"))
        assertTrue(json.endsWith(".json"))
        assertEquals(
            listOf(row),
            GSON.fromJsonArray<Bookmark>(File(directory, json).readText()).getOrThrow(),
        )
        assertEquals(
            "## Book Author\n\n#### Chapter\n\n###### 原文\n Original\ntext\n\n###### 摘要\n Summary\n\n",
            File(directory, md).readText(),
        )
    }

    @Test
    fun markdownHelperRetainsOriginalTwoFieldBoundaryAndEmptyExportIsValid() = runBlocking {
        val out = ByteArrayOutputStream()
        AppAllBookmarksRepository.writeMarkdown(
            out,
            listOf(
                Bookmark(1, "A", "Same", chapterName = "First"),
                Bookmark(2, "B", "Same", chapterName = "Second"),
            ),
        )
        val output = out.toString("UTF-8")
        assertTrue(output.contains("## A Same"))
        assertFalse(output.contains("## B Same"))
        assertTrue(output.contains("#### Second"))
        val name = repo.export(directory.toURI().toString(), false)
        assertEquals(
            emptyList<Bookmark>(),
            GSON.fromJsonArray<Bookmark>(File(directory, name).readText()).getOrThrow(),
        )
    }
}
