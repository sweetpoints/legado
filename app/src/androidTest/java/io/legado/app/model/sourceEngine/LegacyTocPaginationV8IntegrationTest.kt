package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.WebBook
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original native TOC pipeline versus actual Dart/V8 WebBook, synthetic localhost only. */
@RunWith(AndroidJUnit4::class)
class LegacyTocPaginationV8IntegrationTest {
    private data class Seen(val path: String, val header: String?)

    private class Server : NanoHTTPD("127.0.0.1", 0) {
        val requests = CopyOnWriteArrayList<Seen>()
        val base
            get() = "http://127.0.0.1:$listeningPort"

        fun chapter(index: Int) = "<ol><li><a href='/chapter/$index'>Chapter $index</a></li></ol>"

        val first
            get() =
                chapter(1) +
                    "<a class='next' href='/toc/1'>Self</a><a class='next' href='/toc/2'>Second page</a><a class='next' href='/toc/3'>Third page</a>"

        override fun serve(session: IHTTPSession): Response {
            requests.add(Seen(session.uri, session.headers["x-toc-fixture"]))
            val html =
                when (session.uri) {
                    "/toc/1" -> first
                    "/toc/2" ->
                        chapter(2) + "<a class='next' href='/toc/3'>Ignored child continuation</a>"
                    "/toc/3" -> chapter(3) + "<a class='next' href='/toc/1'>Back</a>"
                    "/fallback" -> chapter(1) + chapter(2) + chapter(3)
                    else ->
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "text/plain",
                            "No fixture",
                        )
                }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
        }
    }

    private fun source(server: Server, paged: Boolean) =
        BookSource(
            bookSourceUrl = server.base + "/source-${UUID.randomUUID()}",
            bookSourceName = "TOC pagination fixture",
            ruleToc =
                TocRule(
                    chapterList = "tag.li@tag.a",
                    chapterName = "text",
                    chapterUrl = "href",
                    nextTocUrl = if (paged) "class.next@href" else null,
                ),
        )

    private fun assertChapters(
        expected: List<BookChapter>,
        actual: List<BookChapter>,
        base: String,
    ) {
        assertEquals("A successful TOC must contain all three fixture chapters", 3, actual.size)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), actual.map { it.title })
        assertEquals((1..3).map { "$base/chapter/$it" }, actual.map { it.url })
        assertEquals(expected.map { it.title }, actual.map { it.title })
        assertEquals(expected.map { it.getAbsoluteURL() }, actual.map { it.getAbsoluteURL() })
        assertEquals(expected.map { it.index }, actual.map { it.index })
        assertEquals(listOf(0, 1, 2), actual.map { it.index })
    }

    @Test
    fun selfLinksAndMultipleNextPagesKeepOriginalChapterOrderWithoutDuplicateFetches(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = Server()
            server.start()
            val source = source(server, true)
            val book =
                Book(
                    bookUrl = server.base + "/book",
                    tocUrl = server.base + "/toc/1",
                    origin = source.bookSourceUrl,
                    name = "TOC fixture",
                )
            appDb.bookSourceDao.insert(source)
            try {
                val expected =
                    withTimeout(20_000) {
                        BookChapterList.analyzeChapterList(
                            source,
                            book.copy(),
                            book.tocUrl,
                            book.tocUrl,
                            server.first,
                        )
                    }
                assertEquals(listOf("/toc/2", "/toc/3"), server.requests.map { it.path }.sorted())
                server.requests.clear()
                val actual =
                    withTimeout(20_000) { WebBook.getChapterListAwait(source, book).getOrThrow() }
                assertChapters(expected, actual, server.base)
                assertEquals(
                    listOf("/toc/1", "/toc/2", "/toc/3"),
                    server.requests.map { it.path }.sorted(),
                )
            } finally {
                DartSourceEngine.clearSourceState(source)
                appDb.bookSourceDao.delete(source)
                server.stop()
            }
        }
    }

    @Test
    fun blankTocUrlLoadsBookUrlAndPreservesItsRequestOptionsHeader(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = Server()
            server.start()
            val source = source(server, false)
            val optionsUrl =
                server.base + "/fallback,{\"headers\":{\"X-Toc-Fixture\":\"preserved\"}}"
            val book =
                Book(
                    bookUrl = optionsUrl,
                    tocUrl = "",
                    origin = source.bookSourceUrl,
                    name = "Fallback fixture",
                )
            appDb.bookSourceDao.insert(source)
            try {
                val expected =
                    withTimeout(20_000) {
                        val response =
                            AnalyzeUrl(
                                    mUrl = optionsUrl,
                                    source = source,
                                    ruleData = book.copy(),
                                    coroutineContext = currentCoroutineContext(),
                                )
                                .getStrResponseAwait()
                        BookChapterList.analyzeChapterList(
                            source,
                            book.copy(),
                            response.url,
                            response.url,
                            response.body,
                        )
                    }
                assertEquals(listOf(Seen("/fallback", "preserved")), server.requests.toList())
                server.requests.clear()
                val actual =
                    withTimeout(20_000) { WebBook.getChapterListAwait(source, book).getOrThrow() }
                assertChapters(expected, actual, server.base)
                assertEquals(listOf(Seen("/fallback", "preserved")), server.requests.toList())
            } finally {
                DartSourceEngine.clearSourceState(source)
                appDb.bookSourceDao.delete(source)
                server.stop()
            }
        }
    }
}
