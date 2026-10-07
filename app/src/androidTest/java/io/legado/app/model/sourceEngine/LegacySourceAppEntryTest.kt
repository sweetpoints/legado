package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.association.BookSourceImportJson
import io.legado.app.ui.association.parseBookSourceJson
import io.legado.app.ui.association.prepareBookSourceImportCandidate
import io.legado.app.utils.GSON
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** User import and WebBook routes, not a reduced source sent directly to the Dart test host. */
@RunWith(AndroidJUnit4::class)
class LegacySourceAppEntryTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        var info = "<section class='target'><h2>Info Book</h2><a href='/toc'>Contents</a></section>"
        val requests = CopyOnWriteArrayList<String>()
        val base get() = "http://127.0.0.1:$listeningPort"
        override fun serve(session: IHTTPSession): Response {
            requests += session.uri
            val body = when (session.uri) {
                "/search", "/explore" -> "<article class='book'><h2>Imported Book</h2><a href='/info'>Read</a></article>"
                "/info" -> info
                "/toc" -> "<ol><li><a href='/chapter'>Original Chapter</a></li></ol>"
                "/chapter" -> "<h3>Returned Chapter</h3><div class='body'><p>Alpha&nbsp;Beta</p><p>&lt;Gamma&gt;</p></div>"
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown fixture path")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", body)
        }
    }
    private fun importSource(server: FixtureServer, unsupportedContent: Boolean = false): io.legado.app.data.entities.BookSource {
        val raw = mapOf(
            "bookSourceUrl" to server.base,
            "bookSourceName" to "Imported complete legacy fixture",
            "searchUrl" to server.base + "/search",
            "exploreUrl" to listOf(mapOf("title" to "Category", "url" to server.base + "/explore", "style" to mapOf("layout_flexGrow" to 1))),
            "exploreScreen" to "unused discovery UI configuration",
            "ruleSearch" to mapOf("bookList" to "class.book", "name" to "tag.h2@text", "bookUrl" to "tag.a@href"),
            "ruleExplore" to mapOf("bookList" to "class.book", "name" to "tag.h2@text", "bookUrl" to "tag.a@href"),
            "ruleBookInfo" to mapOf("init" to "class.target", "name" to "tag.h2@text", "tocUrl" to "tag.a@href", "canReName" to "true"),
            "ruleToc" to mapOf("chapterList" to "tag.li@tag.a", "chapterName" to "text", "chapterUrl" to "href"),
            "ruleContent" to buildMap {
                put("content", "class.body@html")
                put("replaceRegex", "##Beta##Replaced")
                put("title", "tag.h3@text")
                if (unsupportedContent) put("subContent", "class.unsupported@text")
            },
        )
        val imported = (parseBookSourceJson(GSON.toJson(raw)) as BookSourceImportJson.Sources).items.single()
        val candidate = prepareBookSourceImportCandidate(imported, emptyList())
        assertTrue(candidate.canImport(false))
        assertFalse(SourceEngineSourcePolicy.hasVersionedDefinition(imported.bookSourceComment))
        // Omitted source flags must use the actual App DTO defaults, not a reduced test definition.
        assertEquals(true, imported.enabledCookieJar)
        return candidate.source(false)
    }

    @Test fun importingCompleteOldSourceDoesNotBlockSearchOnUnrelatedContentHooks(): Unit = runBlocking(Dispatchers.IO) {
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = importSource(server, unsupportedContent = true)
        try {
            val books = withTimeout(15_000) { WebBook.searchBookAwait(source, "Query", 1) }
            assertEquals("Imported Book", books.single().name)
            assertEquals(listOf("/search"), server.requests.toList())
        } finally { server.stop(); DartSourceEngine.clearSourceState(source) }
    }

    @Test fun importedOldSourceRunsSearchExploreInfoInitTocAndFormattedReplacementThroughApp(): Unit = runBlocking(Dispatchers.IO) {
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = importSource(server)
        try {
            withTimeout(25_000) {
                val book = WebBook.searchBookAwait(source, "Query", 1).single().toBook()
                assertEquals("Imported Book", WebBook.exploreBookAwait(source, server.base + "/explore", 1).single().name)
                WebBook.getBookInfoAwait(source, book)
                assertEquals("Info Book", book.name)
                val chapter = WebBook.getChapterListAwait(source, book).getOrThrow().single()
                val content = WebBook.getContentAwait(source, book, chapter, needSave = false)
                assertEquals("　　Alpha Replaced\n　　<Gamma>", content)
                assertEquals("Returned Chapter", chapter.title)
            }
            assertEquals(listOf("/search", "/explore", "/info", "/toc", "/chapter"), server.requests.toList())
        } finally { server.stop(); DartSourceEngine.clearSourceState(source) }
    }

    @Test fun initScriptIdentityPreservesWholeNativeElementContainer(): Unit = runBlocking(Dispatchers.IO) {
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = importSource(server)
        server.info = "<section class='target'><h2>First Book</h2><a href='/wrong'>First</a></section>" +
            "<section class='target'><h2>Second Book</h2><a href='/toc'>Second</a></section>"
        source.ruleBookInfo = source.getBookInfoRule().copy(
            init = "class.target@js:result", name = "tag.h2.1@text", tocUrl = "tag.a.1@href",
        )
        try {
            val book = WebBook.searchBookAwait(source, "Query", 1).single().toBook()
            withTimeout(15_000) { WebBook.getBookInfoAwait(source, book) }
            assertEquals("Second Book", book.name)
            assertEquals(server.base + "/toc", book.tocUrl)
            assertEquals(listOf("/search", "/info"), server.requests.toList())
        } finally { server.stop(); DartSourceEngine.clearSourceState(source) }
    }

    @Test fun unsupportedHookInSelectedOperationStillFailsBeforeNetwork(): Unit = runBlocking(Dispatchers.IO) {
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = importSource(server, unsupportedContent = true)
        try {
            val book = WebBook.searchBookAwait(source, "Query", 1).single().toBook()
            val error = runCatching {
                withTimeout(10_000) {
                    WebBook.getContentAwait(source, book, BookChapter(url = server.base + "/chapter", title = "Chapter"), needSave = false)
                }
            }.exceptionOrNull()
            assertTrue(error is SourceHostException)
            assertEquals("legacy_requires_migration", (error as SourceHostException).code)
            assertEquals(listOf("/search"), server.requests.toList())
        } finally { server.stop(); DartSourceEngine.clearSourceState(source) }
    }
}
