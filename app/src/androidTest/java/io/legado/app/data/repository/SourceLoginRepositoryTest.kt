package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class SourceLoginRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppSourceLoginRepository
    private val id = UUID.randomUUID().toString()
    private val rss = RssSource(sourceUrl = "https://login-$id.invalid", sourceName = "Rss login",
        header = "@js:JSON.stringify({'X-Source':source.getTag()})",
        loginUi = "@js:throw new Error('Initialization must not execute login UI');")
    @Before fun before() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repository = AppSourceLoginRepository(database)
        runBlocking(Dispatchers.IO) { database.rssSourceDao.insert(rss) }
    }
    @After fun after() { rss.removeLoginInfo(); rss.removeLoginHeader(); database.close() }
    @Test fun realRssInitializationKeepsHeaderScriptsLoginHeadersAndStoredFormOnly() = runBlocking {
        withContext(Dispatchers.IO) {
            assertTrue(rss.putLoginInfo("{\"username\":\"Exact user\",\"password\":\"typed\"}"))
            rss.putLoginHeader("{\"X-Session\":\"Owned session\"}")
        }
        val value = repository.load(SourceLoginRequest(type = "rssSource", key = rss.sourceUrl))
        assertEquals(rss.sourceUrl, value.source!!.getKey()); assertEquals("Rss login", value.headers["X-Source"])
        assertEquals("Owned session", value.headers["X-Session"])
        assertEquals(mapOf("username" to "Exact user", "password" to "typed"), value.loginInfo)
        assertNull(value.book); assertNull(value.chapter)
        withContext(Dispatchers.IO) { rss.removeLoginInfo() }
        assertTrue(repository.load(SourceLoginRequest(type = "rssSource", key = rss.sourceUrl)).loginInfo.isEmpty())
    }
    @Test fun bookSourceAndTtsUseExactDaoTypesAndFullMetadata() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://book-$id.invalid", bookSourceName = "Book login", header = "{\"X-Book\":\"Exact\"}")
        val tts = HttpTTS(id = 81371, name = "Tts login", url = "https://tts.invalid", header = "{\"X-Tts\":\"Exact\"}")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(source); database.httpTTSDao.insert(tts) }
        val book = repository.load(SourceLoginRequest(type = "bookSource", key = source.bookSourceUrl))
        assertTrue(book.source is BookSource); assertEquals("Book login", book.source!!.getTag()); assertEquals("Exact", book.headers["X-Book"])
        val speech = repository.load(SourceLoginRequest(type = "httpTts", key = tts.id.toString()))
        assertTrue(speech.source is HttpTTS); assertEquals(tts.url, (speech.source as HttpTTS).url); assertEquals("Exact", speech.headers["X-Tts"])
    }
    @Test fun bookLookupPrefersStoredBookThenSearchBookWithoutChangingSource() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://search-$id.invalid", bookSourceName = "Search")
        val result = SearchBook(bookUrl = "https://search-$id.invalid/book", origin = source.bookSourceUrl, name = "Search title", author = "Author")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(source); database.searchBookDao.insert(result) }
        val request = SourceLoginRequest(type = "rssSource", key = rss.sourceUrl, bookUrl = result.bookUrl)
        val searched = repository.load(request)
        assertEquals("Search title", searched.book!!.name); assertEquals(rss.sourceUrl, searched.source!!.getKey())
        withContext(Dispatchers.IO) { database.bookDao.insert(result.toBook().copy(name = "Stored title")) }
        assertEquals("Stored title", repository.load(request).book!!.name)
    }
    @Test fun missingAndUnknownSourcesRemainExplicitAndInvalidTtsKeyIsRejected() = runBlocking {
        assertNull(repository.load(SourceLoginRequest(type = "rssSource", key = "missing")).source)
        assertNull(repository.load(SourceLoginRequest(type = "unknown", key = rss.sourceUrl)).source)
        try { repository.load(SourceLoginRequest(type = "rssSource")); fail("Missing key") } catch (_: NoStackTraceException) {}
        try { repository.load(SourceLoginRequest(type = "httpTts", key = "bad")); fail("Invalid numeric key") } catch (_: NumberFormatException) {}
    }
}
