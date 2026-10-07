package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Two books from one real source, with separate WebBook calls and interleaved stages. */
@RunWith(AndroidJUnit4::class)
class LegacyBookVariableScopeIntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val base
            get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response {
            val html =
                when (session.uri) {
                    "/search" ->
                        "<article><h2>Book A</h2><i>A-token</i><a href='/info/a'>A</a></article>" +
                            "<article><h2>Book B</h2><i>B-token</i><a href='/info/b'>B</a></article>"
                    "/info/a" -> "<b>A-detail</b><a href='/toc/a'>Contents</a>"
                    "/info/b" -> "<b>B-detail</b><a href='/toc/b'>Contents</a>"
                    "/toc/a" -> "<li><a href='/chapter/a'>A Chapter</a></li>"
                    "/toc/b" -> "<li><a href='/chapter/b'>B Chapter</a></li>"
                    else ->
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "text/plain",
                            "Unknown fixture",
                        )
                }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    @Test
    fun originalParserKeepsSearchBookVariablesThroughConversionAndSeparateStages() {
        val firstResult = SearchBook(name = "Book A", bookUrl = "https://fixture.invalid/a")
        val secondResult = SearchBook(name = "Book B", bookUrl = "https://fixture.invalid/b")
        val rule = "@put:{\"saved\":\"tag.i@text\"}tag.h2@text"
        assertEquals(
            "Book A",
            AnalyzeRule(firstResult).setContent("<h2>Book A</h2><i>A-token</i>").getString(rule),
        )
        assertEquals(
            "Book B",
            AnalyzeRule(secondResult).setContent("<h2>Book B</h2><i>B-token</i>").getString(rule),
        )
        val first = firstResult.toBook()
        val second = secondResult.toBook()
        AnalyzeRule(second)
            .setContent("<b>B-detail</b>")
            .getString("@put:{\"detail\":\"tag.b@text\"}@get:{saved}")
        AnalyzeRule(first)
            .setContent("<b>A-detail</b>")
            .getString("@put:{\"detail\":\"tag.b@text\"}@get:{saved}")
        assertEquals("B-token", AnalyzeRule(second).setContent("unused").getString("@get:{saved}"))
        assertEquals("B-detail", second.getVariable("detail"))
        assertEquals("A-token", AnalyzeRule(first).setContent("unused").getString("@get:{saved}"))
        assertEquals("A-detail", first.getVariable("detail"))
        val chapter = BookChapter(bookUrl = first.bookUrl, url = "/chapter")
        val contentParser =
            AnalyzeRule(first).setChapter(chapter).setContent("<i>chapter-token</i>")
        assertEquals("A-token", contentParser.getString("@get:{saved}"))
        contentParser.getString("@put:{\"saved\":\"tag.i@text\"}tag.i@text")
        assertEquals("chapter-token", chapter.getVariable("saved"))
        assertEquals("A-token", first.getVariable("saved"))
        assertEquals("chapter-token", contentParser.getString("@get:{saved}"))
        chapter.putVariable("saved", "")
        assertEquals("A-token", contentParser.getString("@get:{saved}"))
    }

    @Test
    fun nativeScopedParserCallbackReadsTheSameBookLayerAsPureRule(): Unit =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://fixture.invalid/scoped-callback",
                    bookSourceName = "Scoped callback fixture",
                )
            val host = LegacyRuleHost(source.bookSourceUrl, currentCoroutineContext())
            fun payload(rule: String) =
                mapOf<String, Any?>(
                    "rule" to rule,
                    "input" to "<b>A-detail</b>",
                    "mode" to "scalar",
                    "source" to DartSourceEngine.jsonObject(source),
                    "operation" to "info",
                    "baseUrl" to source.bookSourceUrl,
                    "variables" to
                        mapOf(
                            "book" to
                                mapOf("name" to "Book A", "variable" to "{\"saved\":\"A-token\"}")
                        ),
                    "variableScope" to
                        mapOf(
                            "id" to "book-a",
                            "target" to "book",
                            "source" to emptyMap<String, String>(),
                            "book" to mapOf("saved" to "A-token"),
                            "chapter" to emptyMap<String, String>(),
                        ),
                )
            try {
                assertEquals(
                    "A-token",
                    host.evaluate(payload("@get:{saved}"), fromScript = false)["value"],
                )
                withTimeout(15_000) {
                    assertEquals(
                        "A-token",
                        host
                            .evaluate(
                                payload("@put:{\"detail\":\"tag.b@text\"}@js:java.get('saved')"),
                                fromScript = false,
                            )["value"],
                    )
                }
                assertEquals(
                    "A-detail",
                    host.evaluate(payload("@get:{detail}"), fromScript = false)["value"],
                )
            } finally {
                host.close()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun searchPutFollowsEachBookIntoInterleavedInfoAndTocCalls(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = server.base + "/source",
                    bookSourceName = "Two-book variable scope fixture",
                    searchUrl = server.base + "/search",
                    ruleSearch =
                        SearchRule(
                            bookList = "tag.article",
                            name = "@put:{\"saved\":\"tag.i@text\"}tag.h2@text",
                            bookUrl = "tag.a@href",
                        ),
                    ruleBookInfo =
                        BookInfoRule(
                            author = "@put:{\"detail\":\"tag.b@text\"}@js:java.get('saved')",
                            tocUrl = "tag.a@href",
                        ),
                    ruleToc =
                        TocRule(
                            chapterList = "tag.li",
                            chapterName = "@js:java.get('saved') + ':' + java.get('detail')",
                            chapterUrl = "tag.a@href",
                        ),
                )
            try {
                withTimeout(30_000) {
                    val results = WebBook.searchBookAwait(source, "Query", 1)
                    assertEquals(listOf("Book A", "Book B"), results.map { it.name })
                    // These assertions check the real returned entity, not a source-wide host
                    // cache.
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
                    val secondChapter =
                        WebBook.getChapterListAwait(source, second).getOrThrow().single()
                    val firstChapter =
                        WebBook.getChapterListAwait(source, first).getOrThrow().single()
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
