package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.HtmlFormatter
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.text.StringEscapeUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual legacy hooks, with self-authored HTTP fixtures and exact original parser goldens. */
@RunWith(AndroidJUnit4::class)
class LegacyPipelineHooksV8IntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val base
            get() = "http://127.0.0.1:$listeningPort"

        val info =
            "<h2>Outside</h2><article class='target'><h2>InsideOne</h2></article><article class='target'><h2>InsideTwo</h2></article>"
        val pageOne =
            "<h1>FirstTitle</h1><div class='body'><p>&amp;Alpha&nbsp;</p><p>END</p></div><a class='next' href='/c2'>Next</a>"
        val pageTwo =
            "<h1>SecondTitle</h1><div class='body'><p>START</p><p>Beta&nbsp;&amp;</p></div>"

        override fun serve(session: IHTTPSession): Response {
            val html =
                when (session.uri) {
                    "/info" -> info
                    "/c1" -> pageOne
                    "/c2" -> pageTwo
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
    fun infoInitTransformsWholeElementContainerBeforeFieldsWithOneLibraryInitialization(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = server.base + "/init-source",
                    bookSourceName = "Init fixture",
                    jsLib =
                        """
                        var libraryInitCount=(typeof libraryInitCount==='undefined'?0:libraryInitCount)+1;
                        var initRuns=0;
                        function prepareInit(value){initRuns++;return String(value);}
                        function decorate(value){return value+':'+libraryInitCount+':'+initRuns;}
                        """
                            .trimIndent(),
                    ruleBookInfo =
                        BookInfoRule(
                            init = "class.target@js:prepareInit(result)",
                            name = "tag.h2.0@text@js:decorate(result)",
                            author = "@js:String(result)",
                        ),
                )
            try {
                val originalContainer =
                    AnalyzeRule().setContent(server.info).getElement("class.target").toString()
                val exactContainer =
                    "<article class=\"target\">\n <h2>InsideOne</h2>\n</article>\n<article class=\"target\">\n <h2>InsideTwo</h2>\n</article>"
                assertEquals(
                    "The original Elements container golden must remain exact",
                    exactContainer,
                    originalContainer,
                )
                val book =
                    Book(bookUrl = server.base + "/info", name = "", origin = source.bookSourceUrl)
                val actual = withTimeout(15_000) { WebBook.getBookInfoAwait(source, book) }
                assertEquals("InsideOne:1:1", actual.name)
                assertEquals(originalContainer, actual.author)
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    private fun formattedOriginalPage(html: String, base: String): String {
        val parser = AnalyzeRule().setContent(html, base)
        val raw = parser.getString(parser.splitSourceRule("class.body@html"), unescape = false)
        return StringEscapeUtils.unescapeHtml4(HtmlFormatter.formatKeepImg(raw, URL(base)))
    }

    @Test
    fun contentFormatsEachPageThenReplacesWholeJoinOnceAndReadsTitleFromFirstBody(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val regexSource =
                BookSource(
                    bookSourceUrl = server.base + "/regex-source",
                    bookSourceName = "Regex fixture",
                    ruleContent =
                        ContentRule(
                            content = "class.body@html",
                            nextContentUrl = "class.next@href",
                            replaceRegex = "##END\\nSTART##MERGED",
                            title = "tag.h1@text",
                        ),
                )
            val scriptSource =
                regexSource.copy(
                    bookSourceUrl = server.base + "/script-source",
                    jsLib =
                        "var replaceRuns=0;function replaceJoined(value){replaceRuns++;return value.replace(/END\\nSTART/g,'MERGED');}",
                    ruleContent =
                        regexSource.ruleContent!!.copy(
                            replaceRegex = "@js:replaceJoined(result)",
                            title = "@js:java.getString('tag.h1@text',src)+'|'+replaceRuns",
                        ),
                )
            try {
                val first = formattedOriginalPage(server.pageOne, server.base + "/c1")
                val second = formattedOriginalPage(server.pageTwo, server.base + "/c2")
                assertEquals("&Alpha\nEND", first.split('\n').joinToString("\n") { it.trim() })
                assertEquals("START\nBeta &", second.split('\n').joinToString("\n") { it.trim() })
                val originalJoined =
                    (first + "\n" + second).split('\n').joinToString("\n") { it.trim() }
                val originalReplaced =
                    AnalyzeRule().setContent(originalJoined).getString("##END\\nSTART##MERGED")
                assertEquals("&Alpha\nMERGED\nBeta &", originalReplaced)
                val expectedOnlineText = originalReplaced.split('\n').joinToString("\n") { "　　$it" }
                val book =
                    Book(
                        bookUrl = server.base + "/book",
                        name = "Fixture Book",
                        origin = regexSource.bookSourceUrl,
                        type = BookType.text,
                    )
                val chapter =
                    BookChapter(
                        url = server.base + "/c1",
                        bookUrl = book.bookUrl,
                        title = "Original chapter",
                        index = 0,
                    )
                val actual =
                    withTimeout(15_000) {
                        WebBook.getContentAwait(regexSource, book, chapter, needSave = false)
                    }
                assertEquals(expectedOnlineText, actual)
                val scripted =
                    withTimeout(15_000) {
                            DartSourceEngine.execute(
                                scriptSource,
                                "content",
                                DartSourceEngine.jsonObject(chapter) +
                                    mapOf(
                                        "chapterUrl" to chapter.url,
                                        "book" to DartSourceEngine.jsonObject(book),
                                        "chapter" to DartSourceEngine.jsonObject(chapter),
                                        "__legacyContentFormat" to true,
                                        "__legacyAdaptSpecialStyle" to false,
                                        "__legacyOnLineTxt" to true,
                                    ),
                            )
                        }
                        .single()
                assertEquals(expectedOnlineText, scripted["content"])
                assertEquals("FirstTitle|1", scripted["title"])
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(regexSource)
                DartSourceEngine.clearSourceState(scriptSource)
            }
        }

    @Test
    fun nullInfoInitReturnsExplicitFailureInsteadOfUsingUninitializedBody(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = server.base + "/null-source",
                    bookSourceName = "Null init fixture",
                    ruleBookInfo = BookInfoRule(init = "@js:null", name = "tag.h2@text"),
                )
            try {
                val error = runCatching {
                    withTimeout(10_000) {
                        WebBook.getBookInfoAwait(
                            source,
                            Book(bookUrl = server.base + "/info", name = "Original"),
                        )
                    }
                }
                    .exceptionOrNull()
                assertTrue(
                    "Native null init must report structured invalid_request, not swallow or time out",
                    error is SourceHostException,
                )
                assertEquals("invalid_request", (error as SourceHostException).code)
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
