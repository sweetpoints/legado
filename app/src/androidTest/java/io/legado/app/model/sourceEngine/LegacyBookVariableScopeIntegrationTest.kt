package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Two books from one real source, with separate WebBook calls and interleaved stages. */
@RunWith(AndroidJUnit4::class)
class LegacyBookVariableScopeIntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val base get() = "http://127.0.0.1:$listeningPort"
        override fun serve(session: IHTTPSession): Response {
            val html = when (session.uri) {
                "/search" -> "<article><h2>Book A</h2><i>A-token</i><a href='/info/a'>A</a></article>" +
                    "<article><h2>Book B</h2><i>B-token</i><a href='/info/b'>B</a></article>"
                "/info/a" -> "<b>A-detail</b><a href='/toc/a'>Contents</a>"
                "/info/b" -> "<b>B-detail</b><a href='/toc/b'>Contents</a>"
                "/toc/a" -> "<li><a href='/chapter/a'>A Chapter</a></li>"
                "/toc/b" -> "<li><a href='/chapter/b'>B Chapter</a></li>"
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown fixture")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    @Test
    fun searchPutFollowsEachBookIntoInterleavedInfoAndTocCalls(): Unit = runBlocking(Dispatchers.IO) {
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = BookSource(
            bookSourceUrl = server.base + "/source",
            bookSourceName = "Two-book variable scope fixture",
            searchUrl = server.base + "/search",
            ruleSearch = SearchRule(
                bookList = "tag.article",
                name = "@put:{\"saved\":\"tag.i@text\"}tag.h2@text",
                bookUrl = "tag.a@href",
            ),
            ruleBookInfo = BookInfoRule(
                author = "@put:{\"detail\":\"tag.b@text\"}@js:java.get('saved')",
                tocUrl = "tag.a@href",
            ),
            ruleToc = TocRule(
                chapterList = "tag.li",
                chapterName = "@js:java.get('saved') + ':' + java.get('detail')",
                chapterUrl = "tag.a@href",
            ),
        )
        try {
            withTimeout(30_000) {
                val results = WebBook.searchBookAwait(source, "Query", 1)
                assertEquals(listOf("Book A", "Book B"), results.map { it.name })
                // These assertions check the real returned entity, not a source-wide host cache.
                assertEquals("A-token", results[0].getVariable("saved"))
                assertEquals("B-token", results[1].getVariable("saved"))
                val first = results[0].toBook()
                val second = results[1].toBook()
                WebBook.getBookInfoAwait(source, second)
                WebBook.getBookInfoAwait(source, first)
                assertEquals("A-token", first.author)
                assertEquals("B-token", second.author)
                assertEquals("A-detail", first.getVariable("detail"))
                assertEquals("B-detail", second.getVariable("detail"))
                // A global last-write-wins implementation would now give both books A's values.
                val secondChapter = WebBook.getChapterListAwait(source, second).getOrThrow().single()
                val firstChapter = WebBook.getChapterListAwait(source, first).getOrThrow().single()
                assertEquals("B-token:B-detail", secondChapter.title)
                assertEquals("A-token:A-detail", firstChapter.title)
                assertEquals("A-token", first.getVariable("saved"))
                assertEquals("B-token", second.getVariable("saved"))
            }
        } finally {
            server.stop()
            DartSourceEngine.clearSourceState(source)
        }
    }
}
