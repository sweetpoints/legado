package io.legado.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookCacheServiceBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "cache-service-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private class CapturingContext(context: Context) : ContextWrapper(context) {
        val intents = CopyOnWriteArrayList<Intent>()

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent): ComponentName? {
            intents += Intent(service)
            return service.component
        }
    }

    @Test
    fun actualDownloadBridgeUsesFreshReadingIndexAndSkipsLocalAudioDeletedAndDuplicateKeys() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.bookDao.insert(
                    Book(
                        bookUrl = "remote",
                        name = "Remote",
                        durChapterIndex = 7,
                        totalChapterNum = 15,
                    ),
                    Book(bookUrl = "local", name = "Local", type = BookType.text or BookType.local),
                    Book(bookUrl = "audio", type = BookType.audio),
                )
            }
            val capture = CapturingContext(context)
            val repository =
                RoomBookCacheRepository(capture, database, { 0 }, { emptySet() }, directory)
            repository.download(listOf("remote", "remote", "local", "audio", "missing"), true)
            val after = capture.intents.single()
            assertEquals(IntentAction.start, after.action)
            assertEquals("remote", after.getStringExtra("bookUrl"))
            assertEquals(7, after.getIntExtra("start", -1))
            assertEquals(14, after.getIntExtra("end", -1))
            capture.intents.clear()
            repository.download(listOf("remote"), false)
            assertEquals(0, capture.intents.single().getIntExtra("start", -1))
            capture.intents.clear()
            repository.toggleDownload("local")
            repository.toggleDownload("audio")
            assertTrue(capture.intents.isEmpty())
        }

    @Test
    fun actualDiskNativeReceiptsSurviveRecreationAndCancellationAndCannotBeReplacedOrResurrected() =
        runBlocking {
            val repository =
                RoomBookCacheRepository(context, database, { 0 }, { emptySet() }, directory)
            val ticket = repository.stage(listOf("a"))
            assertTrue(repository.folderResult(ticket, BookCacheFolderResult("content://chosen")))
            val next = RoomBookCacheRepository(context, database, { 0 }, { emptySet() }, directory)
            assertEquals(BookCacheFolderResult("content://chosen"), next.folderResult(ticket))
            assertFalse(next.folderResult(ticket, BookCacheFolderResult("wrong")))
            assertEquals(BookCacheFolderResult("content://chosen"), repository.folderResult(ticket))
            next.release(ticket)
            assertFalse(repository.folderResult(ticket, BookCacheFolderResult("late")))
            assertNull(repository.folderResult(ticket))
            assertFalse(File(directory, "$ticket.json").exists())
            val cancel = next.stage(listOf("a"))
            assertTrue(next.folderResult(cancel, BookCacheFolderResult(null)))
            assertEquals(BookCacheFolderResult(null), repository.folderResult(cancel))
            next.release(cancel)
        }

    @Test
    fun actualPdfAndCustomEpubBridgePreserveAllServiceExtrasAndDoNotMutateBooks() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookDao.insert(
                Book(bookUrl = "book", name = "Book", durChapterIndex = 3, totalChapterNum = 10)
            )
        }
        val capture = CapturingContext(context)
        val repository =
            RoomBookCacheRepository(capture, database, { 0 }, { emptySet() }, directory)
        repository.export(
            BookCacheExport(listOf("book", "book", "gone"), "content://folder", "pdf")
        )
        val pdf = capture.intents.single()
        assertEquals("pdf", pdf.getStringExtra("exportType"))
        assertEquals("content://folder", pdf.getStringExtra("exportPath"))
        assertEquals(IntentAction.start, pdf.action)
        assertFalse(pdf.hasExtra("epubScope"))
        capture.intents.clear()
        repository.export(BookCacheExport(listOf("book"), "path", "epub", 5, "1-5,8"))
        val epub = capture.intents.single()
        assertEquals("epub", epub.getStringExtra("exportType"))
        assertEquals(5, epub.getIntExtra("epubSize", -1))
        assertEquals("1-5,8", epub.getStringExtra("epubScope"))
        withContext(Dispatchers.IO) {
            val book = database.bookDao.getBook("book")!!
            assertEquals(3, book.durChapterIndex)
            assertEquals(10, book.totalChapterNum)
        }
    }
}
