package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppChapterSourceContentStoreTest {
    @Test fun actualPrivateFilesKeepLargePayloadsRejectStaleRevisionsAndRecoverAtomicBackupAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val session = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "chapter-source-sessions/$session")
        val store = AppChapterSourceContentStore(context); val other = AppChapterSourceContentStore(context)
        val snapshot = ChapterSourceSession(ChapterSourceSearchRequest("Book", "Author", originalBookJson = "x".repeat(1200000)), 3, "Title", true, revision = 10)
        try { withContext(Dispatchers.IO) {
            store.write(session, snapshot); other.write(session, snapshot.copy(revision = 9, chapterTitle = "stale")); assertEquals(snapshot, other.read(session))
            coroutineScope { (11L..20L).map { revision -> async { other.write(session, snapshot.copy(revision = revision)) } }.awaitAll() }
            assertEquals(20L, store.read(session)!!.revision)
            val file = File(directory, "state.json"); assertTrue(file.renameTo(File(file.path + ".bak"))); assertEquals(20L, store.read(session)!!.revision)
            val receipt = ChapterSourceReceipt("receipt", ChapterSourceReceiptKind.Content, body = "y".repeat(1200000), committed = true)
            store.writeReceipt(session, receipt); other.writeReceipt(session, receipt.copy(consumed = true)); store.writeReceipt(session, receipt)
            assertTrue(other.receipt(session, receipt.key)!!.consumed); assertEquals(1200000, other.receipt(session, receipt.key)!!.body!!.length)
            val receiptFile = File(directory, "receipt.json"); assertTrue(receiptFile.renameTo(File(receiptFile.path + ".bak")))
            assertTrue(other.receipts(session).single().consumed)
        } } finally { withContext(Dispatchers.IO) { directory.deleteRecursively() } }
    }
    @Test fun actualOriginalChapterProjectionPreservesMetadataAndSourceOperationsKeepUnrelatedFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { withContext(Dispatchers.IO) {
            val token = UUID.randomUUID().toString(); val book = Book(bookUrl = "book-$token", name = "Book-$token", author = "Author")
            val chapter = BookChapter(url = "chapter-$token", bookUrl = book.bookUrl, index = 0, title = "Title", isVip = true, isPay = true, tag = "Tag", variable = "{\"metadata\":true}", imgUrl = "image")
            val source = BookSource(bookSourceUrl = "https://$token.invalid", bookSourceName = "Source", bookSourceGroup = "Group", customOrder = 7, bookSourceComment = "Comment")
            db.bookDao.insert(book); db.bookChapterDao.insert(chapter); db.bookSourceDao.insert(source)
            val store = AppChapterSourceContentStore(context, db)
            val projected = store.original(GSON.toJson(book)).single(); assertEquals(GSON.toJson(chapter), projected.json); assertEquals("Tag", projected.tag)
            assertTrue(projected.key.length < 100)
            val raw = io.legado.app.data.entities.SearchBook(bookUrl = "result-$token", origin = source.bookSourceUrl, name = book.name, author = book.author)
            db.searchBookDao.insert(raw)
            val row = AppChapterSourceSearchStore(db).cached(ChapterSourceSearchRequest(book.name, book.author)).single()
            store.order(row, false); assertEquals(8, db.bookSourceDao.getBookSource(source.bookSourceUrl)!!.customOrder)
            store.disableSource(row); val disabled = db.bookSourceDao.getBookSource(source.bookSourceUrl)!!
            assertFalse(disabled.enabled); assertEquals("Comment", disabled.bookSourceComment); assertEquals("Group", disabled.bookSourceGroup)
            assertTrue(store.groups().isEmpty())
        } } finally { db.close() }
    }
}
