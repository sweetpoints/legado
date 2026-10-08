package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual V8/native Jsoup leases and localhost HTTP; never external source data. */
@RunWith(AndroidJUnit4::class)
class LegacyOrgConnectionV8IntegrationTest {
    private data class Seen(
        val method: String,
        val body: String,
        val header: String?,
        val cookie: String?,
    )

    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val seen = LinkedBlockingQueue<Seen>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun serve(session: IHTTPSession): Response {
            val files = hashMapOf<String, String>()
            if (session.method in setOf(Method.POST, Method.PUT)) session.parseBody(files)
            seen.add(
                Seen(
                    session.method.name,
                    if (session.method == Method.PUT)
                        files["content"]?.let { File(it).readText(Charsets.UTF_8) }.orEmpty()
                    else files["postData"].orEmpty(),
                    session.headers["x-fixture"],
                    session.headers["cookie"],
                )
            )
            if (session.uri == "/slow") {
                entered.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=UTF-8", BODY)
                .also {
                    if (session.uri == "/cookie")
                        it.addHeader("Set-Cookie", "org-fixture=kept; Path=/")
                    it.addHeader("X-Response", "native")
                }
        }

        fun url(path: String = "/ready") = "http://127.0.0.1:$listeningPort$path"
    }

    private fun source(server: FixtureServer) =
        BookSource(
            bookSourceUrl = server.url("/source-${UUID.randomUUID()}"),
            bookSourceName = "Org Connection fixture",
        )

    private suspend fun evaluate(
        source: BookSource,
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
    ): Any? =
        withTimeout(20_000) {
            V8ScriptExecutor.evaluate(script, bindings = bindings, source = source)
        }

    private suspend fun rejected(
        source: BookSource,
        token: String,
        reason: String = "object was released",
    ) {
        val failure = runCatching {
            evaluate(
                source,
                "__sourceHostSync('orgJsoup.connectionCall',[token,'response',[]])",
                mapOf("token" to token),
            )
        }
            .exceptionOrNull()
        assertNotNull("A released or foreign token must fail", failure)
        // Native invalid_request crosses the existing synchronous JS bridge as
        // an uncaught JS error; assert both transport layers, not any exception.
        assertTrue(
            "The outer V8 boundary must be a script exception",
            failure is SourceScriptException,
        )
        assertEquals("script_error", (failure as SourceScriptException).code)
        assertTrue(
            "The inner native code must remain visible",
            failure.message.orEmpty().contains("invalid_request"),
        )
        assertTrue(
            "The precise native ownership/lifetime reason must remain visible",
            failure.message.orEmpty().contains(reason),
        )
    }

    @Test
    fun putEnumHeadersBodyAndNativeResponseBytesKeepIdentityAndCacheBrand(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            val key = "org-response-${UUID.randomUUID()}"
            try {
                val result =
                    evaluate(
                        source,
                        "const c=org.jsoup.Jsoup.connect(url);c.method(org.jsoup.Connection.Method.PUT);c.header('X-Fixture','wire');c.requestBody('request-body');const r=c.execute();const bytes=r.bodyAsBytes();cache.put(key,bytes);({body:r.body(),status:r.statusCode(),header:r.header('X-Response'),bytes:bytes,stored:java.bytesToStr(cache.getByteArray(key)),same:c.response()===r,again:c.response()===c.response()})",
                        mapOf("url" to server.url(), "key" to key),
                    )
                        as Map<*, *>
                val request = server.seen.poll(5, TimeUnit.SECONDS)!!
                assertEquals("PUT", request.method)
                assertEquals("request-body", request.body)
                assertEquals("wire", request.header)
                assertEquals(BODY, result["body"])
                assertEquals(200, (result["status"] as Number).toInt())
                assertEquals("native", result["header"])
                assertEquals(
                    BODY.toByteArray().map { it.toInt() },
                    (result["bytes"] as List<*>).map { (it as Number).toInt() },
                )
                assertEquals(BODY, result["stored"])
                assertEquals(true, result["same"])
                assertEquals(true, result["again"])
            } finally {
                CacheManager.delete(key)
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun retainedConnectionUsesItsCookieStoreAcrossAuxiliaryExecutions(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                CookieStore.setCookie(server.url(), "native-fixture=seed")
                // Jsoup.connect has its own cookie store; Legado's global seed is
                // only background state. Capture the original API's exact wire contract.
                val native = Jsoup.connect(server.url("/cookie"))
                assertEquals(BODY, native.execute().body())
                val nativeFirst = server.seen.poll(5, TimeUnit.SECONDS)!!
                native.url(server.url())
                assertEquals(BODY, native.execute().body())
                val nativeSecond = server.seen.poll(5, TimeUnit.SECONDS)!!
                assertTrue(
                    "Original Jsoup must retain its response cookie",
                    nativeSecond.cookie.orEmpty().contains("org-fixture=kept"),
                )
                assertEquals(
                    BODY,
                    evaluate(
                        source,
                        "globalThis.orgRetained=org.jsoup.Jsoup.connect(url);orgRetained.execute().body()",
                        mapOf("url" to server.url("/cookie")),
                    ),
                )
                val first = server.seen.poll(5, TimeUnit.SECONDS)!!
                assertEquals(
                    "The first V8 request must match original Jsoup, including global seed isolation",
                    nativeFirst.cookie,
                    first.cookie,
                )
                assertEquals(
                    BODY,
                    evaluate(
                        source,
                        "orgRetained.url(url);orgRetained.execute().body()",
                        mapOf("url" to server.url()),
                    ),
                )
                val second = server.seen.poll(5, TimeUnit.SECONDS)!!
                assertEquals(
                    "The second V8 request must match the original retained connection",
                    nativeSecond.cookie,
                    second.cookie,
                )
                assertTrue(
                    "The retained Jsoup cookie store must send response cookies",
                    second.cookie.orEmpty().contains("org-fixture=kept"),
                )
            } finally {
                CookieStore.removeCookie(server.url())
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun serializedConnectionTokenCannotBeInvokedByAnotherSourceOwner(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val first = source(server)
            val second = source(server)
            try {
                val token =
                    evaluate(
                        first,
                        "__sourceHostSync('orgJsoup.connect',[url]).__legacyOrgConnection",
                        mapOf("url" to server.url()),
                    )
                        as String
                rejected(second, token, "belongs to another source owner")
                assertEquals(
                    BODY,
                    evaluate(
                        first,
                        "const response=__sourceHostSync('orgJsoup.connectionCall',[token,'execute',[]]);__sourceHostSync('orgJsoup.responseCall',[response.__legacyOrgResponse,'body',[]])",
                        mapOf("token" to token),
                    ),
                )
            } finally {
                DartSourceEngine.clearSourceState(first)
                DartSourceEngine.clearSourceState(second)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun canonicalAndRawSourceClearRejectOldTokensButFreshConnectionsWork(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                for (raw in listOf(false, true)) {
                    val token =
                        evaluate(
                            source,
                            "__sourceHostSync('orgJsoup.connect',[url]).__legacyOrgConnection",
                            mapOf("url" to server.url()),
                        )
                            as String
                    if (raw) DartSourceEngine.clearSourceState(source.bookSourceUrl)
                    else DartSourceEngine.clearSourceState(source)
                    rejected(source, token)
                    assertEquals(
                        BODY,
                        evaluate(
                            source,
                            "org.jsoup.Jsoup.connect(url).execute().body()",
                            mapOf("url" to server.url()),
                        ),
                    )
                }
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun explicitlyDisposedConnectionRejectsWrapperAndNativeTokenReuse(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                val token =
                    evaluate(
                        source,
                        "globalThis.orgDisposed=org.jsoup.Jsoup.connect(url);orgDisposed.dispose();const token=__sourceHostSync('orgJsoup.connect',[url]).__legacyOrgConnection;__sourceHostSync('orgJsoup.release',[token]);token",
                        mapOf("url" to server.url()),
                    )
                        as String
                val failure = runCatching {
                    evaluate(source, "orgDisposed.execute()")
                }
                    .exceptionOrNull()
                assertTrue(failure is SourceScriptException)
                assertTrue(failure!!.message.orEmpty().contains("legacy.org_jsoup_disposed"))
                rejected(source, token)
                assertTrue(server.seen.isEmpty())
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun clearingSourceCancelsInflightConnectionAndPermitsFreshEntry(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                val pending = async {
                    evaluate(
                        source,
                        "globalThis.orgSlow=org.jsoup.Jsoup.connect(url);orgSlow.timeout(15000);orgSlow.execute().body()",
                        mapOf("url" to server.url("/slow")),
                    )
                }
                assertTrue(
                    "Native request must enter before clearing",
                    server.entered.await(5, TimeUnit.SECONDS),
                )
                withTimeout(5_000) { DartSourceEngine.clearSourceState(source) }
                val failure = runCatching {
                    withTimeout(5_000) { pending.await() }
                }
                    .exceptionOrNull()
                assertTrue(
                    "Clearing the owner must cancel its actual task",
                    failure is CancellationException,
                )
                assertFalse(
                    "A test deadline is not proof of owner cancellation",
                    failure is TimeoutCancellationException,
                )
                server.release.countDown()
                assertEquals(
                    BODY,
                    evaluate(
                        source,
                        "org.jsoup.Jsoup.connect(url).execute().body()",
                        mapOf("url" to server.url()),
                    ),
                )
            } finally {
                server.release.countDown()
                DartSourceEngine.clearSourceState(source)
                server.stop()
            }
        }
    }

    @Test
    fun libraryRecipeChangeInvalidatesOldConnectionLease(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            source.jsLib = "var orgRecipeMarker='first';"
            try {
                val token =
                    evaluate(
                        source,
                        "__sourceHostSync('orgJsoup.connect',[url]).__legacyOrgConnection",
                        mapOf("url" to server.url()),
                    )
                        as String
                source.jsLib = "var orgRecipeMarker='second';"
                assertEquals("second", evaluate(source, "orgRecipeMarker"))
                rejected(source, token)
                assertEquals(
                    BODY,
                    evaluate(
                        source,
                        "org.jsoup.Jsoup.connect(url).execute().body()",
                        mapOf("url" to server.url()),
                    ),
                )
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun disposingConnectionLeavesItsIndependentUnreadResponseUsable(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                val original = Jsoup.connect(server.url()).execute()
                val expectedBody = original.body()
                val expectedBytes = original.bodyAsBytes().map { it.toInt() }
                // Do not call body/parse/bytes before disposing the connection.
                val result =
                    evaluate(
                        source,
                        "const c=org.jsoup.Jsoup.connect(url),r=c.execute();c.dispose();({body:r.body(),bytes:r.bodyAsBytes()})",
                        mapOf("url" to server.url()),
                    )
                        as Map<*, *>
                assertEquals(expectedBody, result["body"])
                assertEquals(
                    expectedBytes,
                    (result["bytes"] as List<*>).map { (it as Number).toInt() },
                )
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    @Test
    fun disposingResponseAllowsConnectionToReturnANewUsableWrapper(): Unit {
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer()
            server.start()
            val source = source(server)
            try {
                val originalConnection = Jsoup.connect(server.url())
                originalConnection.execute()
                val original = originalConnection.response()
                val expectedBody = original.body()
                val expectedBytes = original.bodyAsBytes().map { it.toInt() }
                // Release the wrapper before reading its stream. The retained native
                // connection owns the original Response and must re-lease it intact.
                val result =
                    evaluate(
                        source,
                        "const c=org.jsoup.Jsoup.connect(url),first=c.execute();first.dispose();const second=c.response();({fresh:second!==first,stable:second===c.response(),body:second.body(),bytes:second.bodyAsBytes()})",
                        mapOf("url" to server.url()),
                    )
                        as Map<*, *>
                assertEquals(true, result["fresh"])
                assertEquals(true, result["stable"])
                assertEquals(expectedBody, result["body"])
                assertEquals(
                    expectedBytes,
                    (result["bytes"] as List<*>).map { (it as Number).toInt() },
                )
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.release.countDown()
                server.stop()
            }
        }
    }

    companion object {
        private const val BODY = "native-中文"
    }
}
