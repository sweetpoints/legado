package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual App parser and background Android WebView, with no public source or UI navigation. */
@RunWith(AndroidJUnit4::class)
class LegacyWebJsAppIntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val rendererStarted = CompletableDeferred<Unit>()
        val base
            get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response {
            val body =
                when (session.uri) {
                    "/info" -> "<div class='author'>WebView author</div><a href='/toc'>Contents</a>"
                    "/renderer-started" -> {
                        rendererStarted.complete(Unit)
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "image/svg+xml",
                            "<svg xmlns='http://www.w3.org/2000/svg'/>",
                        )
                    }
                    else ->
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "text/plain",
                            "Unknown fixture",
                        )
                }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
        }
    }

    private fun source(server: FixtureServer, author: String) =
        BookSource(
            bookSourceUrl = server.base + "/source",
            bookSourceName = "Background WebJS fixture",
            ruleBookInfo = BookInfoRule(author = author, tocUrl = "tag.a@href"),
        )

    @Test
    fun webJsFieldExtractionRunsThroughActualWebBookAndBackgroundRenderer(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source = source(server, "@webjs:document.querySelector('.author').textContent")
            try {
                val book = Book(bookUrl = server.base + "/info", origin = source.bookSourceUrl)
                withTimeout(15_000) { WebBook.getBookInfoAwait(source, book) }
                assertEquals("WebView author", book.author)
                assertEquals(server.base + "/toc", book.tocUrl)
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun cancellingAnActiveWebJsRendererReleasesTaskAndAllowsNextExtraction(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            // null keeps the original background evaluator waiting/retrying. The same-origin XHR
            // proves execution reached the real renderer before cancellation, not just the HTTP
            // stage.
            val source =
                source(
                    server,
                    "@webjs:var marker=new XMLHttpRequest();marker.open('GET','${server.base}/renderer-started');marker.send();null",
                )
            try {
                val pending = async {
                    WebBook.getBookInfoAwait(
                        source,
                        Book(bookUrl = server.base + "/info", origin = source.bookSourceUrl),
                    )
                }
                try {
                    withTimeout(10_000) { server.rendererStarted.await() }
                    withTimeout(4_000) { pending.cancelAndJoin() }
                    assertTrue(pending.isCancelled)
                } finally {
                    pending.cancelAndJoin()
                }
                source.getBookInfoRule().author =
                    "@webjs:document.querySelector('.author').textContent"
                val next = Book(bookUrl = server.base + "/info", origin = source.bookSourceUrl)
                withTimeout(15_000) { WebBook.getBookInfoAwait(source, next) }
                assertEquals("WebView author", next.author)
                assertEquals(server.base + "/toc", next.tocUrl)
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
