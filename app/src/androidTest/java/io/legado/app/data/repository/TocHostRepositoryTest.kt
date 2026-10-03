package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class TocHostRepositoryTest {
    private lateinit var database: AppDatabase; private lateinit var repository: AppTocHostRepository
    private val book = Book(bookUrl = "fixture://${UUID.randomUUID()}", name = "Book", author = "Author", origin = "remote", totalChapterNum = 2,
        customCoverUrl = "custom.png", persistedCoverUrl = "persisted.png", readConfig = Book.ReadConfig(pageAnim = 5))
    private val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "toc-export-${UUID.randomUUID()}")
    private val notifications = mutableListOf<Book>()
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repository = AppTocHostRepository(database, { value -> value.totalChapterNum = 1; listOf(BookChapter(url = "new", bookUrl = value.bookUrl, index = 0, title = "New")) }, { notifications += ownedTocBook(it) })
        runBlocking(Dispatchers.IO) { database.bookDao.insert(book); database.bookChapterDao.insert(BookChapter(url = "old0", bookUrl = book.bookUrl, index = 0), BookChapter(url = "old1", bookUrl = book.bookUrl, index = 1)) }
    }
    @After fun cleanup() { database.close(); directory.deleteRecursively() }
    @Test fun loadReturnsDetachedReadConfigAndMissingOwnerReturnsNull() = runBlocking {
        val loaded = repository.load(book.bookUrl)!!; loaded.setTocExpanded(false)
        assertTrue(repository.load(book.bookUrl)!!.getTocExpanded()); assertNull(repository.load("missing"))
    }
    @Test fun ordinaryReverseChangesOnlyDisplayOrderAndKeepsChapterMetadataAndOtherReadConfig() = runBlocking {
        val reversed = repository.reverse(book)
        assertTrue(reversed.getReverseTocDisplay()); assertFalse(reversed.getReverseToc()); assertFalse(book.getReverseTocDisplay())
        withContext(Dispatchers.IO) {
            val stored = database.bookDao.getBook(book.bookUrl)!!; assertTrue(stored.getReverseTocDisplay()); assertEquals(5, stored.readConfig!!.pageAnim)
            assertEquals("custom.png", stored.customCoverUrl); assertEquals(listOf("old0", "old1"), database.bookChapterDao.getChapterList(book.bookUrl).map { it.url })
        }
    }
    @Test fun epubAndPdfReverseRetainPersistedReverseTocContract() = runBlocking {
        for (extension in listOf("epub", "pdf")) {
            val local = book.copy(type = BookType.local or BookType.text, originName = "book.$extension")
            val reversed = repository.reverse(local); assertTrue(reversed.getReverseToc()); assertFalse(reversed.getReverseTocDisplay())
            withContext(Dispatchers.IO) { assertTrue(database.bookDao.getBook(book.bookUrl)!!.getReverseToc()) }
        }
    }
    @Test fun expandedUpdatePreservesUnknownReadConfigFieldsAndUnrelatedBookColumns() = runBlocking {
        withContext(Dispatchers.IO) { database.bookDao.updateReadConfigJson(book.bookUrl, "{\"futureOption\":\"keep\",\"reverseToc\":true,\"pageAnim\":5}") }
        repository.expanded(book.bookUrl, false)
        withContext(Dispatchers.IO) {
            val json = GSON.fromJsonObject<com.google.gson.JsonObject>(database.bookDao.getReadConfigJson(book.bookUrl)!!).getOrThrow()
            assertEquals("keep", json.get("futureOption").asString); assertTrue(json.get("reverseToc").asBoolean); assertFalse(json.get("tocExpanded").asBoolean)
            assertEquals("custom.png", database.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
        }
    }
    @Test fun rebuildingUsesExistingParserPipelineAndPreservesLatestCustomCoverWhileReplacingChapterTable() = runBlocking {
        val stale = book.copy(customCoverUrl = "stale", persistedCoverUrl = "stale", tocUrl = "Regex")
        val result = repository.rebuild(stale); assertEquals(1, result.totalChapterNum); assertEquals(1, notifications.size)
        withContext(Dispatchers.IO) {
            val stored = database.bookDao.getBook(book.bookUrl)!!; assertEquals("custom.png", stored.customCoverUrl); assertEquals("persisted.png", stored.persistedCoverUrl); assertEquals("Regex", stored.tocUrl)
            assertEquals(listOf("new"), database.bookChapterDao.getChapterList(book.bookUrl).map { it.url })
        }
    }
    @Test fun actualJsonAndMarkdownExportsKeepFullBookmarkMetadataAndOriginalFormat() = runBlocking {
        val row = Bookmark(7, "Book", "Author", 1, 28, "Chapter", "Full original", "Note")
        withContext(Dispatchers.IO) { database.bookmarkDao.insert(row, row.copy(time = 8, bookAuthor = "Foreign")) }; directory.mkdirs()
        repository.export(book, Uri.fromFile(directory).toString(), false); repository.export(book, Uri.fromFile(directory).toString(), true)
        val json = File(directory, "bookmark-Book Author.json").readText(); assertTrue(json.contains("Full original")); assertTrue(json.contains("28")); assertFalse(json.contains("Foreign"))
        assertEquals("## Book Author\n\n#### Chapter\n\n###### 原文\n Full original\n\n###### 摘要\n Note\n\n", File(directory, "bookmark-Book Author.md").readText())
    }
    @Test fun activeReaderExpansionIsImmediateAndForeignReaderBookIsUntouched() {
        val previous = ReadBook.book
        try {
            ReadBook.book = book.copy(readConfig = book.readConfig!!.copy()); repository.synchronizeExpanded(book.bookUrl, false)
            assertFalse(ReadBook.book!!.getTocExpanded())
            ReadBook.book = book.copy(bookUrl = "foreign", readConfig = Book.ReadConfig(tocExpanded = true)); repository.synchronizeExpanded(book.bookUrl, false)
            assertTrue(ReadBook.book!!.getTocExpanded())
        } finally { ReadBook.book = previous }
    }
}
