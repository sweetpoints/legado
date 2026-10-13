package io.legado.app.model.sourceEngine

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

/** Actual project Jsoup HTTP and cookie stores, on controlled local JDK servers. */
class NativeOrgConnectionHostTest {
    private class Server : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var method: String? = null
        var requestBody: String? = null
        var header: String? = null
        val base
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                method = exchange.requestMethod
                requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                header = exchange.requestHeaders.getFirst("X-Test")
                val path = exchange.requestURI.path
                if (path == "/held") {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                if (path == "/cookie")
                    exchange.responseHeaders.add("Set-Cookie", "retained=value; Path=/")
                val body =
                    when (path) {
                        "/cookie" -> "<title>First</title><p>one</p>"
                        "/echo" -> "<p>${exchange.requestHeaders.getFirst("Cookie").orEmpty()}</p>"
                        "/json" -> "{\"value\":1}"
                        else -> "<title>Put</title><p>payload</p>"
                    }.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (path == "/json") "application/json" else "text/html; charset=utf-8",
                )
                runCatching {
                    exchange.sendResponseHeaders(
                        if (path == "/error") 409 else 200,
                        body.size.toLong(),
                    )
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            server.start()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private suspend fun connect(host: NativeOrgConnectionHost, owner: String, url: String) =
        (host.call(owner, "orgJsoup.connect", listOf(url), currentCoroutineContext()) as Map<*, *>)[
            "__legacyOrgConnection"]
            as String

    private suspend fun conn(
        host: NativeOrgConnectionHost,
        owner: String,
        token: String,
        operation: String,
        vararg args: Any?,
    ) =
        host.call(
            owner,
            "orgJsoup.connectionCall",
            listOf(token, operation, args.toList()),
            currentCoroutineContext(),
        )

    private suspend fun resp(
        host: NativeOrgConnectionHost,
        owner: String,
        token: String,
        operation: String,
        vararg args: Any?,
    ) =
        host.call(
            owner,
            "orgJsoup.responseCall",
            listOf(token, operation, args.toList()),
            currentCoroutineContext(),
        )

    private fun responseToken(value: Any?) = (value as Map<*, *>)["__legacyOrgResponse"] as String

    private fun leases(host: NativeOrgConnectionHost): Int =
        (NativeOrgConnectionHost::class
                .java
                .getDeclaredField("leases")
                .apply { isAccessible = true }
                .get(host) as Map<*, *>)
            .size

    private fun nativeResponse(
        host: NativeOrgConnectionHost,
        token: String,
    ): org.jsoup.Connection.Response {
        val table =
            NativeOrgConnectionHost::class
                .java
                .getDeclaredField("leases")
                .apply { isAccessible = true }
                .get(host) as Map<*, *>
        val lease = checkNotNull(table[token])
        return lease.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(lease)
            as org.jsoup.Connection.Response
    }

    private fun resources(host: NativeOrgConnectionHost): Int =
        (NativeOrgConnectionHost::class
                .java
                .getDeclaredField("responses")
                .apply { isAccessible = true }
                .get(host) as Map<*, *>)
            .size

    private suspend fun release(host: NativeOrgConnectionHost, owner: String, token: String) {
        host.call(owner, "orgJsoup.release", listOf(token), currentCoroutineContext())
    }

    @Test
    fun releasedConnectionLeavesIndependentLazyResponseReadable(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val original = Jsoup.connect(server.base + "/cookie").execute()
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/cookie")
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    release(host, "owner", c)
                    assertEquals(1, resources(host))
                    assertEquals(original.body(), resp(host, "owner", r, "body"))
                    assertEquals(
                        original.bodyAsBytes().map { it.toInt() },
                        resp(host, "owner", r, "bodyAsBytes"),
                    )
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun releasedResponseLeavesConnectionCurrentResponseReadable(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val originalConnection = Jsoup.connect(server.base + "/cookie")
                val original = originalConnection.execute()
                assertSame(original, originalConnection.response())
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/cookie")
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    release(host, "owner", r)
                    assertEquals(1, resources(host))
                    val again = responseToken(conn(host, "owner", c, "response"))
                    assertNotEquals(r, again)
                    assertEquals(again, responseToken(conn(host, "owner", c, "response")))
                    assertEquals(
                        originalConnection.response().body(),
                        resp(host, "owner", again, "body"),
                    )
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun nextExecutionKeepsOlderIndependentResponseReadable(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val original = Jsoup.connect(server.base + "/cookie")
                val firstOriginal = original.execute()
                original.url(server.base + "/put")
                val secondOriginal = original.execute()
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/cookie")
                    val first = responseToken(conn(host, "owner", c, "execute"))
                    conn(host, "owner", c, "url", server.base + "/put")
                    val second = responseToken(conn(host, "owner", c, "execute"))
                    assertNotEquals(first, second)
                    assertEquals(2, resources(host))
                    release(host, "owner", c)
                    assertEquals(firstOriginal.body(), resp(host, "owner", first, "body"))
                    assertEquals(secondOriginal.body(), resp(host, "owner", second, "body"))
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun nextExecutionClosesReplacedResponseAfterItsLastLeaseWasReleased(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/cookie")
                    val first = responseToken(conn(host, "owner", c, "execute"))
                    val actualFirst = nativeResponse(host, first)
                    release(host, "owner", first)
                    assertEquals(1, resources(host))
                    conn(host, "owner", c, "url", server.base + "/put")
                    val second = responseToken(conn(host, "owner", c, "execute"))
                    assertEquals(1, resources(host))
                    assertTrue(runCatching { actualFirst.body() }.isFailure)
                    assertTrue((resp(host, "owner", second, "body") as String).contains("payload"))
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun lastConnectionAndResponseReleaseCloseStreamsInBothOrders(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    for (connectionFirst in listOf(true, false)) {
                        val c = connect(host, "owner", server.base + "/cookie")
                        val r = responseToken(conn(host, "owner", c, "execute"))
                        val actual = nativeResponse(host, r)
                        release(host, "owner", if (connectionFirst) c else r)
                        assertEquals(1, resources(host))
                        release(host, "owner", if (connectionFirst) r else c)
                        assertEquals(0, resources(host))
                        assertEquals(0, leases(host))
                        assertTrue(runCatching { actual.body() }.isFailure)
                    }
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun ownerClearAndShutdownCloseLastStreamsWithoutAffectingOtherOwner(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val a = connect(host, "a", server.base + "/cookie")
                    val ar = responseToken(conn(host, "a", a, "execute"))
                    val actualA = nativeResponse(host, ar)
                    val b = connect(host, "b", server.base + "/put")
                    val br = responseToken(conn(host, "b", b, "execute"))
                    val actualB = nativeResponse(host, br)
                    host.releaseOwner("a")
                    assertEquals(1, resources(host))
                    assertTrue(runCatching { actualA.body() }.isFailure)
                    assertEquals(200, resp(host, "b", br, "statusCode"))
                    host.close()
                    assertEquals(0, resources(host))
                    assertEquals(0, leases(host))
                    assertTrue(runCatching { actualB.body() }.isFailure)
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun putFluentHeadersBodyAndLazyResponseParsingUseTheRealConnection(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/put")
                    conn(host, "owner", c, "headers", mapOf("X-Test" to "first"))
                    conn(host, "owner", c, "header", "X-Test", "second")
                    conn(host, "owner", c, "method", "PUT")
                    conn(host, "owner", c, "requestBody", "raw-payload")
                    conn(host, "owner", c, "timeout", 2000)
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    assertEquals("PUT", server.method)
                    assertEquals("raw-payload", server.requestBody)
                    assertEquals("second", server.header)
                    assertEquals(200, resp(host, "owner", r, "statusCode"))
                    val body = resp(host, "owner", r, "body") as String
                    assertArrayEquals(
                        body.toByteArray(StandardCharsets.UTF_8),
                        (resp(host, "owner", r, "bodyAsBytes") as List<*>)
                            .map { (it as Number).toByte() }
                            .toByteArray(),
                    )
                    val doc =
                        LegacyDomHost.restore(resp(host, "owner", r, "parse") as Map<*, *>)
                            as Document
                    assertEquals("Put", doc.title())
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun sameOwnerCrossTaskRetainsCookiesAndSavedResponseIdentity(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c =
                        withContext(Dispatchers.IO) {
                            connect(host, "owner", server.base + "/cookie")
                        }
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    assertEquals(r, responseToken(conn(host, "owner", c, "response")))
                    assertEquals(r, responseToken(conn(host, "owner", c, "response")))
                    val next =
                        withContext(Dispatchers.IO) {
                            conn(host, "owner", c, "url", server.base + "/echo")
                            conn(host, "owner", c, "get")
                        }
                    assertEquals(
                        "retained=value",
                        (LegacyDomHost.restore(next as Map<*, *>) as Document).body().text(),
                    )
                    assertTrue((resp(host, "owner", r, "body") as String).contains("one"))
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun postDataAndHttpErrorOptionsPreserveJsoupSemantics(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/error")
                    conn(host, "owner", c, "ignoreHttpErrors", true)
                    conn(host, "owner", c, "data", "field", "a b")
                    val doc = conn(host, "owner", c, "post")
                    assertEquals("POST", server.method)
                    assertEquals("field=a+b", server.requestBody)
                    assertTrue(LegacyDomHost.restore(doc as Map<*, *>) is Document)
                    assertEquals(
                        409,
                        resp(
                            host,
                            "owner",
                            responseToken(conn(host, "owner", c, "response")),
                            "statusCode",
                        ),
                    )
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun jsonExecuteDoesNotEagerlyRequireAnHtmlDocument(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/json")
                    conn(host, "owner", c, "ignoreContentType", true)
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    assertEquals("{\"value\":1}", resp(host, "owner", r, "body"))
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun ownerClearConfigurationSwitchAndShutdownInvalidateOnlyTheirRealLeases(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeOrgConnectionHost()
            val a = connect(host, "a", "https://fixture.invalid/")
            val b = connect(host, "b", "https://fixture.invalid/")
            host.updateConfiguration("a", "recipe-1")
            host.updateConfiguration("a", "recipe-1")
            conn(host, "a", a, "timeout", 10)
            host.updateConfiguration("a", "recipe-2")
            assertTrue(
                runCatching { conn(host, "a", a, "timeout", 10) }.exceptionOrNull()
                    is SourceScriptException
            )
            conn(host, "b", b, "timeout", 10)
            host.releaseOwner("b")
            assertTrue(
                runCatching { conn(host, "b", b, "timeout", 10) }.exceptionOrNull()
                    is SourceScriptException
            )
            val c = connect(host, "c", "https://fixture.invalid/")
            host.close()
            assertTrue(
                runCatching { conn(host, "c", c, "timeout", 10) }.exceptionOrNull()
                    is SourceScriptException
            )
            assertEquals(0, leases(host))
        }

    @Test
    fun foreignOwnerReleaseIsRejectedAndOwnedDisposalIsIdempotent(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeOrgConnectionHost()
            try {
                val c = connect(host, "owner", "https://fixture.invalid/")
                assertTrue(
                    runCatching { conn(host, "other", c, "timeout", 10) }.exceptionOrNull()
                        is SourceScriptException
                )
                assertTrue(
                    runCatching {
                        host.call(
                            "other",
                            "orgJsoup.release",
                            listOf(c),
                            currentCoroutineContext(),
                        )
                    }
                        .exceptionOrNull() is SourceScriptException
                )
                host.call("owner", "orgJsoup.release", listOf(c), currentCoroutineContext())
                host.call("owner", "orgJsoup.release", listOf(c), currentCoroutineContext())
                assertEquals(0, leases(host))
                val identityField =
                    NativeOrgConnectionHost::class.java.getDeclaredField("identities")
                identityField.isAccessible = true
                assertTrue((identityField.get(host) as Map<*, *>).isEmpty())
            } finally {
                host.close()
            }
        }

    @Test
    fun ownerClearCancelsAnInflightRequestWithoutPublishingALateResponse(): Unit =
        runBlocking(Dispatchers.IO) {
            Server().use { server ->
                val host = NativeOrgConnectionHost()
                try {
                    val c = connect(host, "owner", server.base + "/held")
                    conn(host, "owner", c, "timeout", 500)
                    val pending = async { conn(host, "owner", c, "execute") }
                    assertTrue(server.entered.await(2, TimeUnit.SECONDS))
                    host.releaseOwner("owner")
                    server.release.countDown()
                    assertTrue(
                        runCatching { withTimeout(2000) { pending.await() } }.exceptionOrNull()
                            is kotlinx.coroutines.CancellationException
                    )
                    assertEquals(0, leases(host))
                } finally {
                    host.close()
                }
            }
        }

    @Test
    fun truncatedActualHttpBodyFailsOnLazyReadLikeOriginalJsoup(): Unit =
        runBlocking(Dispatchers.IO) {
            ServerSocket(0).use { socket ->
                val thread = Thread {
                    repeat(2) {
                        socket.accept().use { peer ->
                            val input = peer.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            peer
                                .getOutputStream()
                                .write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1000\r\nConnection: close\r\n\r\nx"
                                        .toByteArray(StandardCharsets.US_ASCII)
                                )
                            peer.getOutputStream().flush()
                        }
                    }
                }
                    .apply {
                        isDaemon = true
                        start()
                    }
                val host = NativeOrgConnectionHost()
                try {
                    val url = "http://127.0.0.1:${socket.localPort}/truncated"
                    val original = Jsoup.connect(url).timeout(2000).execute()
                    assertEquals(200, original.statusCode())
                    assertTrue(
                        runCatching { original.body() }.exceptionOrNull()
                            is java.io.UncheckedIOException
                    )
                    val c = connect(host, "owner", url)
                    conn(host, "owner", c, "timeout", 2000)
                    val r = responseToken(conn(host, "owner", c, "execute"))
                    assertEquals(200, resp(host, "owner", r, "statusCode"))
                    val failure = runCatching { resp(host, "owner", r, "body") }.exceptionOrNull()
                    assertTrue(failure is SourceScriptException)
                    assertEquals("network_error", (failure as SourceScriptException).code)
                    // Both leases were actually published. A failed lazy read must not leak an
                    // extra one.
                    assertEquals(2, leases(host))
                    host.call("owner", "orgJsoup.release", listOf(r), currentCoroutineContext())
                    assertEquals(1, leases(host))
                } finally {
                    host.close()
                    thread.join(2000)
                }
            }
        }
}
