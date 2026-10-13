package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.webBook.WebBook
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Entirely self-authored local fixtures, through the actual V8 and legacy parser boundaries. */
@RunWith(AndroidJUnit4::class)
class LegacyRuleV8IntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val requests = CopyOnWriteArrayList<String>()
        val base
            get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response {
            requests.add(session.uri)
            val html =
                when (session.uri) {
                    "/search" ->
                        "<article class='book'><h2>Journey</h2><a href='/info'>Book</a></article>"
                    "/info" -> "<h2>Info Header</h2><a href='/toc'>Contents</a>"
                    "/toc" -> "<ol><li><a href='/chapter'>First Chapter</a></li></ol>"
                    "/chapter" -> "<div class='content'>Local fixture content</div>"
                    else ->
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "text/plain",
                            "Unknown fixture path",
                        )
                }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    private fun source(server: FixtureServer) =
        BookSource(
            bookSourceUrl = server.base,
            bookSourceName = "Local legacy/V8 fixture",
            searchUrl = server.base + "/search?key={{key}}&page={{page}}",
            ruleSearch =
                SearchRule(bookList = "class.book", name = "tag.h2@text", bookUrl = "tag.a@href"),
            ruleBookInfo = BookInfoRule(name = "tag.h2@text", tocUrl = "tag.a@href"),
            ruleToc =
                TocRule(chapterList = "tag.li@tag.a", chapterName = "text", chapterUrl = "href"),
            ruleContent = ContentRule(content = "class.content@text"),
        )

    @Test
    fun mixedCssJavaScriptAndInlineExpressionsUseActualOuterParser(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue("Mandatory Flutter/V8 backend", BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            try {
                val definition =
                    source(server).apply {
                        ruleSearch =
                            ruleSearch!!.copy(
                                name = "tag.h2@text@js:String(result).toUpperCase()",
                                author = "Author {{1+2}}",
                            )
                    }
                val result =
                    withTimeout(15_000) { WebBook.searchBookAwait(definition, "Local", 1) }.single()
                assertEquals("JOURNEY", result.name)
                assertEquals("Author 3", result.author)
                assertEquals(server.base + "/info", result.bookUrl)
                assertEquals(listOf("/search"), server.requests.toList())
            } finally {
                server.stop()
            }
        }

    @Test
    fun libraryInitializesOnceAndGlobalXPathCallbackPreservesOuterHtmlAcrossFieldsAndStages():
        Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue("Mandatory Flutter/V8 backend", BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val definition =
                source(server).apply {
                    jsLib =
                        """
                        var initCount=(typeof initCount==='undefined'?0:initCount)+1;
                        function decorate(value){return value+':'+initCount;}
                        function libraryExtract(){return java.getString('@XPath://h2',src);}
                        """
                            .trimIndent()
                    ruleSearch =
                        ruleSearch!!.copy(
                            name = "tag.h2@text@js:decorate(result)",
                            author = "@js:libraryExtract()",
                        )
                    ruleBookInfo =
                        ruleBookInfo!!.copy(
                            name = "{{book.name}}",
                            author = "@js:libraryExtract()+':'+initCount",
                        )
                    ruleContent =
                        ContentRule(content = "@js:book.name+'|'+chapter.title+'|'+initCount")
                }
            try {
                withTimeout(20_000) {
                    val row = WebBook.searchBookAwait(definition, "Local", 1).single()
                    assertEquals("Journey:1", row.name)
                    assertEquals("<h2>Journey</h2>", row.author)
                    val book = row.toBook()
                    val fields =
                        DartSourceEngine.execute(
                                definition,
                                "info",
                                DartSourceEngine.jsonObject(book) +
                                    mapOf("book" to DartSourceEngine.jsonObject(book)),
                            )
                            .single()
                    assertEquals("Journey:1", fields["name"])
                    assertEquals("<h2>Info Header</h2>:1", fields["author"])
                    book.tocUrl = fields["tocUrl"] as String
                    val chapters = WebBook.getChapterListAwait(definition, book).getOrThrow()
                    assertEquals(1, chapters.size)
                    assertEquals("First Chapter", chapters.single().title)
                    assertEquals(
                        "Journey:1|First Chapter|1",
                        WebBook.getContentAwait(
                            definition,
                            book,
                            chapters.single(),
                            needSave = false,
                        ),
                    )
                }
                assertEquals(
                    listOf("/search", "/info", "/toc", "/chapter"),
                    server.requests.toList(),
                )
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(definition)
            }
        }

    @Test
    fun trustedLegacyHostUsesJsonSnapshotsAndCannotAdoptScriptSuppliedSourceIdentity(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue("Mandatory Flutter/V8 backend", BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            try {
                val originalBook = linkedMapOf<String, Any?>("name" to "Snapshot Book")
                val originalChapter =
                    linkedMapOf<String, Any?>("title" to "Snapshot Chapter", "index" to 7)
                // Invoke the public production host, which still executes outer @js in native V8.
                // Only its registered ID is trusted; this is not a mocked script evaluator.
                val host = LegacyRuleHost(server.base)
                val value =
                    withTimeout(15_000) {
                        host
                            .evaluate(
                                mapOf(
                                    "rule" to
                                        "@js:var observed=[book.name,chapter.title,source.getKey()].join('|');book.name='JS-only';chapter.title='JS-only';observed",
                                    "mode" to "scalar",
                                    "input" to "<h2>Snapshot</h2>",
                                    "baseUrl" to server.base,
                                    "operation" to "info",
                                    "source" to
                                        mapOf(
                                            "bookSourceUrl" to "https://spoof.invalid",
                                            "bookSourceName" to "Untrusted identity",
                                        ),
                                    "variables" to
                                        mapOf("book" to originalBook, "chapter" to originalChapter),
                                ),
                                fromScript = false,
                            )["value"]
                    }
                assertEquals("Snapshot Book|Snapshot Chapter|${server.base}", value)
                assertEquals("Snapshot Book", originalBook["name"])
                assertEquals("Snapshot Chapter", originalChapter["title"])
                assertTrue(server.requests.isEmpty())
            } finally {
                server.stop()
            }
        }

    @Test
    fun javascriptInsideSynchronousExtractionCallbackIsRejectedWithoutDeadlock(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue("Mandatory Flutter/V8 backend", BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            try {
                val definition =
                    source(server).apply {
                        ruleSearch =
                            ruleSearch!!.copy(
                                author = "@js:java.getString('tag.h2@text@js:result',src)"
                            )
                    }
                val error = runCatching {
                    withTimeout(10_000) { WebBook.searchBookAwait(definition, "Local", 1) }
                }
                    .exceptionOrNull()
                assertTrue(
                    "Nested script extraction must return a structured migration error, not timeout or deadlock",
                    error is SourceScriptException,
                )
                assertEquals(
                    "nested_script_requires_migration",
                    (error as SourceScriptException).code,
                )
            } finally {
                server.stop()
            }
        }
}
