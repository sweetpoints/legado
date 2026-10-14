package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The real Android request and rule bridges accept label IDs with dynamic search URLs. */
@RunWith(AndroidJUnit4::class)
class LegacyDynamicIdentityV8IntegrationTest {
    @Test
    fun categoryClickUsesItsAbsoluteUrlAndKeepsTheOpaqueSourceId(): Unit =
        runBlocking(Dispatchers.IO) {
            val requests = CopyOnWriteArrayList<String>()
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    requests.add(session.uri + "?page=" + session.parameters["page"]?.single())
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        "<article><h2>Category fixture</h2><a href='/book'>Book</a></article>",
                    )
                }
            }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val base = "http://127.0.0.1:${server.listeningPort}"
            val source = BookSource(
                bookSourceUrl = "歪歪斜斜的字眼里，满篇都是吃人二字-----鲁迅",
                bookSourceName = "Dynamic identity fixture",
                searchUrl = "<js>host + '/search?key={{key}}';</js>",
                ruleExplore = ExploreRule(
                    bookList = "tag.article", name = "tag.h2@text", bookUrl = "tag.a@href",
                ),
            )
            try {
                val records = withTimeout(30_000) {
                    DartSourceEngine.execute(source, "explore", mapOf(
                        "exploreUrl" to "$base/category?page={{page}}", "page" to 2,
                    ))
                }
                assertEquals(1, records.size)
                assertEquals("Category fixture", records.single()["name"])
                assertEquals("$base/book", records.single()["bookUrl"])
                assertEquals(listOf("/category?page=2"), requests)
                assertEquals("歪歪斜斜的字眼里，满篇都是吃人二字-----鲁迅", source.bookSourceUrl)
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.stop()
            }
        }
}
