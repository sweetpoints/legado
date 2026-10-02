package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AppAddBookLinkStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun realNetworkDetailsAndRoomSearchWriteCompleteBeforeRestoredNavigationResult() = runBlocking {
        val id = UUID.randomUUID().toString(); val requests = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet(); return newFixedLengthResponse("<html><h1>Book $id</h1><span class='author'>Author</span></html>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = BookSource("http://127.0.0.1:${server.listeningPort}", "Link fixture $id", enabled = true,
            ruleBookInfo = BookInfoRule(name = "h1@text", author = ".author@text"))
        val url = "${source.bookSourceUrl}/details"; val session = UUID.randomUUID().toString()
        try {
            withContext(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
            val target = withContext(Dispatchers.Main) { DefaultAddBookLinkRepository(AppAddBookLinkStore(context)).resolve(session, url) }
            assertEquals("Book $id", target.name); assertEquals("Author", target.author); assertEquals(url, target.bookUrl)
            val search = withContext(Dispatchers.IO) { appDb.searchBookDao.getSearchBook(url) }
            assertNotNull(search); assertEquals(source.bookSourceUrl, search!!.origin)
            assertNull(withContext(Dispatchers.IO) { appDb.bookDao.getBook(url) })
            assertEquals(target, withContext(Dispatchers.Main) { DefaultAddBookLinkRepository(AppAddBookLinkStore(context)).resolve(session, url) })
            assertEquals(1, requests.get())
        } finally {
            server.stop(); withContext(Dispatchers.IO) {
                appDb.searchBookDao.getSearchBook(url)?.let { appDb.searchBookDao.delete(it) }
                appDb.bookSourceDao.delete(source); File(context.cacheDir, "add-book-link/$session.json").delete()
            }
        }
    }
    @Test fun existingBookGoesDirectlyToInfoWithoutInsertingSearchRecordOrRequiringMatchingSource() = runBlocking {
        val session = UUID.randomUUID().toString(); val book = Book(bookUrl = "https://existing-$session.invalid/book", name = "Existing", author = "Author")
        try {
            withContext(Dispatchers.IO) { appDb.bookDao.insert(book) }
            val target = withContext(Dispatchers.Main) { DefaultAddBookLinkRepository(AppAddBookLinkStore(context)).resolve(session, book.bookUrl) }
            assertEquals(BookLinkTarget(book.name, book.author, book.bookUrl), target)
            assertNull(withContext(Dispatchers.IO) { appDb.searchBookDao.getSearchBook(book.bookUrl) })
        } finally { withContext(Dispatchers.IO) { appDb.bookDao.delete(book); File(context.cacheDir, "add-book-link/$session.json").delete() } }
    }
}
