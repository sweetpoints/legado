package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.UUID
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class BookshelfCoverRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var actual: AppBookshelfCoverStore
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        actual = AppBookshelfCoverStore(context, database, loadOnlyWifi = { false })
        withContext(Dispatchers.IO) { database.bookDao.insert(Book(bookUrl = "cover", name = "Cover", coverUrl = "https://synthetic-cover", durChapterIndex = 20)) }
    }
    @After fun close() { database.close() }
    @Test fun realComparePredicateRejectsEditedCoverAndDoesNotTouchReadingProgress() = runBlocking {
        val simulatedDownload = object : BookshelfCoverStore by actual {
            override suspend fun download(book: Book, url: String): String {
                val latest = checkNotNull(database.bookDao.getBook(book.bookUrl)); database.bookDao.update(latest.copy(customCoverUrl = "https://edited-while-downloading")); return "/synthetic-persistent-cover"
            }
        }
        val result = DefaultBookshelfCoverRepository(simulatedDownload).run(listOf("cover"), ShelfCoverAction.PersistNetwork).toList().last() as ShelfCoverEvent.Completed
        assertEquals(ShelfCoverSummary(0, 1, 0), result.summary)
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.persistedCoverUrl); assertEquals("https://edited-while-downloading", row.customCoverUrl); assertEquals(20, row.durChapterIndex) }
    }
    @Test fun actualRestorePredicatesPreserveCustomOnNetworkRestoreAndClearOverridesOnSourceRestore() = runBlocking {
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); database.bookDao.update(row.copy(customCoverUrl = "https://custom", persistedCoverUrl = "/synthetic-persisted")) }
        val repository = DefaultBookshelfCoverRepository(actual)
        repository.run(listOf("cover"), ShelfCoverAction.RestoreNetwork).toList()
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.persistedCoverUrl); assertEquals("https://custom", row.customCoverUrl) }
        repository.run(listOf("cover"), ShelfCoverAction.RestoreSource).toList()
        withContext(Dispatchers.IO) { val row = checkNotNull(database.bookDao.getBook("cover")); assertNull(row.customCoverUrl); assertNull(row.persistedCoverUrl); assertEquals(20, row.durChapterIndex) }
    }
    @Test fun realImageDownloadValidatesDecodedPixelsBeforeInstallingAndRejectsInvalidBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val directory = File(context.cacheDir, "synthetic-shelf-cover-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val valid = File(directory, "valid.png"); val invalid = File(directory, "invalid.image").apply { writeText("synthetic undecodable image bytes") }
        var installed: File? = null
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff000000.toInt() or (UUID.randomUUID().hashCode() and 0x00ffffff))
            valid.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            withContext(Dispatchers.IO) {
                val book = checkNotNull(database.bookDao.getBook("cover"))
                assertTrue(runCatching { actual.download(book, invalid.absolutePath) }.isFailure)
                assertNull(database.bookDao.getBook("cover")!!.persistedCoverUrl)
                val path = actual.download(book, valid.absolutePath); installed = File(path)
                assertTrue(installed!!.isFile); assertArrayEquals(valid.readBytes(), installed!!.readBytes())
                assertTrue(actual.installIfUnchanged(book, path)); assertEquals(path, database.bookDao.getBook("cover")!!.persistedCoverUrl)
            }
        } finally { installed?.delete(); directory.deleteRecursively() }
    }

}
