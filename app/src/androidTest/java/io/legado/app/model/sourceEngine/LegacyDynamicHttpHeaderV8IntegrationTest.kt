package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual V8 -> original Native HTTP -> controlled server, with bounded evaluation. */
@RunWith(AndroidJUnit4::class)
class LegacyDynamicHttpHeaderV8IntegrationTest {
    private class Server : NanoHTTPD("127.0.0.1", 0) {
        val observed = CopyOnWriteArrayList<String>()
        val seeds = CopyOnWriteArrayList<String>()
        val url
            get() = "http://127.0.0.1:$listeningPort/header"

        override fun serve(session: IHTTPSession): Response {
            val value = session.headers["x-dynamic"].orEmpty()
            observed += value
            seeds += session.headers["x-seed"].orEmpty()
            return newFixedLengthResponse(Response.Status.OK, "text/plain", value)
        }
    }

    @Test
    fun ajaxSourceHeaderRunsInTheExistingOwnerAndPreservesLibraryAndGlobals(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = "opaque-dynamic-ajax-${UUID.randomUUID()}",
                    bookSourceName = "Dynamic header fixture",
                    jsLib =
                        "globalThis.headerLibraryLoads=(globalThis.headerLibraryLoads||0)+1; var capturedHeaderGet=java.get; function headerValue(){return headerLibraryLoads+':'+globalThis.headerRuns;}",
                    header =
                        "@js:globalThis.headerRuns=(globalThis.headerRuns||0)+1; java.put('headerCounter',String(headerRuns)); ({'X-Dynamic':headerValue(),'X-Seed':capturedHeaderGet('headerSeed')})",
                )
            source.put("headerSeed", "source-seed")
            val entity = linkedMapOf("headerSeed" to "book-seed")
            val callbacks = SourceHostCallbacks { method, arguments ->
                when (method) {
                    "analyze.get" -> entity[arguments[0] as String].orEmpty()
                    "analyze.put" ->
                        (arguments[1] as String).also { entity[arguments[0] as String] = it }
                    else -> error("Unexpected callback $method")
                }
            }
            try {
                val first =
                    withTimeout(15_000) {
                        withContext(callbacks) {
                            DartSourceEngine.evaluate(source, "java.ajax('${server.url}',8000)")
                        }
                    }
                assertEquals("1:1", first)
                assertEquals(listOf("1:1"), server.observed.toList())
                val second =
                    withTimeout(15_000) {
                        withContext(callbacks) {
                            DartSourceEngine.evaluate(source, "java.ajax('${server.url}',8000)")
                        }
                    }
                assertEquals("1:2", second)
                assertEquals(listOf("1:1", "1:2"), server.observed.toList())
                assertEquals(listOf("source-seed", "source-seed"), server.seeds.toList())
                assertEquals("2", source.get("headerCounter"))
                assertEquals(mapOf("headerSeed" to "book-seed"), entity)
                val state =
                    withTimeout(15_000) {
                        DartSourceEngine.evaluate(
                            source,
                            "({loads:headerLibraryLoads,runs:headerRuns})",
                        )
                    }
                        as Map<*, *>
                assertEquals(1, (state["loads"] as Number).toInt())
                assertEquals(2, (state["runs"] as Number).toInt())
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun dynamicHeaderUsesSourceVariablesAndRestoresTheCallerBookScope(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = "opaque-header-scope-${UUID.randomUUID()}",
                    bookSourceName = "Header variable scope",
                    jsLib =
                        "var headerRead=java.get; function readHeaderToken(){return headerRead('token');}",
                    header = "@js:({'X-Dynamic':readHeaderToken()})",
                )
            source.put("token", "source")
            val callbacks = SourceHostCallbacks { method, arguments ->
                require(method == "analyze.get" && arguments == listOf("token"))
                "book"
            }
            try {
                val result =
                    withContext(callbacks) {
                        withTimeout(15_000) {
                            DartSourceEngine.evaluate(
                                source,
                                "({before:java.get('token'),body:java.ajax('${server.url}',8000),after:java.get('token')})",
                            )
                        }
                    }
                        as Map<*, *>
                assertEquals("book", result["before"])
                assertEquals("source", result["body"])
                assertEquals("book", result["after"])
                assertEquals(listOf("source"), server.observed.toList())
                assertEquals("source", source.get("token"))
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun jsoupGetUsesItsExplicitDynamicHeaderAndDoesNotEvaluateSourceHeader(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                BookSource(
                    bookSourceUrl = "opaque-dynamic-get-${UUID.randomUUID()}",
                    bookSourceName = "Explicit header fixture",
                    jsLib =
                        "globalThis.getLibraryLoads=(globalThis.getLibraryLoads||0)+1; function getHeader(){return 'get:'+getLibraryLoads;}",
                    header = "@js:throw new Error('Jsoup must not inherit the source header');",
                )
            try {
                val value =
                    withTimeout(15_000) {
                        DartSourceEngine.evaluate(
                            source,
                            "java.get('${server.url}', {'X-Dynamic':getHeader()},8000).body()",
                        )
                    }
                assertEquals("get:1", value)
                assertEquals(listOf("get:1"), server.observed.toList())
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
