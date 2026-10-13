package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.model.webBook.WebBook
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual V8 login checks on the original native request, on controlled local HTTP only. */
@RunWith(AndroidJUnit4::class)
class LegacyLoginCheckV8IntegrationTest {
    private class Server : NanoHTTPD("127.0.0.1", 0) {
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val logged = AtomicBoolean(false)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        var redirect: String? = null
        var forbidden = false
        val base
            get() = "http://127.0.0.1:$listeningPort"

        fun count(path: String) = hits[path]?.get() ?: 0

        override fun serve(session: IHTTPSession): Response {
            hits.computeIfAbsent(session.uri) { AtomicInteger() }.incrementAndGet()
            if (session.uri == "/search" && redirect != null)
                return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                    addHeader("Location", redirect!!)
                }
            if (session.uri == "/login") {
                logged.set(true)
                return newFixedLengthResponse("ok")
            }
            if (session.uri == "/held") {
                holding.countDown()
                release.await()
                return newFixedLengthResponse("released")
            }
            val name =
                if (session.uri == "/landing") "Landing"
                else if (logged.get()) "After" else "Before"
            return newFixedLengthResponse(
                if (forbidden) Response.Status.FORBIDDEN else Response.Status.OK,
                "text/html",
                "<article><b>$name</b><a href='/info'>Book</a></article>",
            )
        }
    }

    private fun source(server: Server, hook: String) =
        BookSource(
            bookSourceUrl = server.base + "/source-${UUID.randomUUID()}",
            bookSourceName = "Login check fixture",
            searchUrl = server.base + "/search",
            loginCheckJs = hook,
            ruleSearch =
                SearchRule(bookList = "tag.article", name = "tag.b@text", bookUrl = "tag.a@href"),
        )

    private suspend fun search(source: BookSource) =
        withTimeout(20_000) { WebBook.searchBookAwait(source, "fixture").single() }

    private fun start() = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

    @Test
    fun normalResponseFacadeRunsTheHookOnceAndKeepsOriginalResponse(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = start()
            val source =
                source(
                    server,
                    "source.setVariable('hook-ran'); if(result.code()!==200 || result.body().indexOf('Before')<0) throw new Error('bad fixture response'); result",
                )
            try {
                assertEquals("Before", search(source).name)
                assertEquals("hook-ran", source.getVariable())
                assertEquals(1, server.count("/search"))
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
                source.setVariable(null)
            }
        }

    @Test
    fun loginAjaxThenGetStrResponseReusesTheCurrentRequest(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = start()
            val source =
                source(
                    server,
                    "if(result.body().indexOf('Before')>=0){if(java.ajax('${server.base}/login')!=='ok')throw new Error('login failed');result=java.getStrResponse();} result",
                )
            try {
                assertEquals("After", search(source).name)
                assertEquals(2, server.count("/search"))
                assertEquals(1, server.count("/login"))
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun redirectUpdatesSourceVariableAndInitUrlResumesDynamicUrlInTheSameVm(): Unit =
        runBlocking(Dispatchers.IO) {
            val old = start()
            val updated = start()
            old.redirect = updated.base + "/landing"
            val source =
                source(
                    old,
                    "var next=String(result.url()).replace(/(https?:\\/\\/[^/]+).*/, '$1');source.setVariable(JSON.stringify({url:next}));java.initUrl();result=java.getStrResponse();result",
                )
            source.searchUrl =
                "@js:var state=JSON.parse(source.getVariable()||'{}');(state.url||'${old.base}')+'/search'"
            try {
                assertEquals("Before", search(source).name)
                assertEquals(1, old.count("/search"))
                assertEquals(1, updated.count("/landing"))
                assertEquals(1, updated.count("/search"))
                assertTrue(source.getVariable().contains(updated.base))
            } finally {
                old.stop()
                updated.stop()
                DartSourceEngine.clearSourceState(source)
                source.setVariable(null)
            }
        }

    @Test
    fun forbiddenResponseRunsTheHookAndPreservesItsHttpStatus(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = start()
            server.forbidden = true
            val source =
                source(
                    server,
                    "if(result.code()!==403 || result.body().indexOf('Before')<0)throw new Error('403 response was lost');source.setVariable('403-checked');result",
                )
            try {
                val result =
                    withTimeout(20_000) {
                        LegacyRequestHost(source.bookSourceUrl, currentCoroutineContext())
                            .fetch(
                                mapOf(
                                    "urlRule" to source.searchUrl,
                                    "source" to DartSourceEngine.jsonObject(source),
                                    "baseUrl" to source.bookSourceUrl,
                                    "variables" to emptyMap<String, Any?>(),
                                ),
                                fromScript = false,
                            )
                    }
                val value = result["value"] as Map<*, *>
                assertEquals(403, (value["status"] as Number).toInt())
                assertTrue((value["body"] as String).contains("Before"))
                assertEquals("403-checked", source.getVariable())
                assertEquals(1, server.count("/search"))
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
                source.setVariable(null)
            }
        }

    @Test
    fun malformedHookResultIsAnExplicitFailureAfterOneFetch(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = start()
            val source = source(server, "({url:'relative',body:'fake',status:200})")
            try {
                val error = runCatching { search(source) }.exceptionOrNull()
                assertTrue(error is SourceHostException)
                assertEquals("invalid_request", (error as SourceHostException).code)
                assertEquals(1, server.count("/search"))
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun cancellationDuringLoginHttpDoesNotBecomeARecoveredResponseAndNextTaskWorks(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = start()
            val source = source(server, "java.ajax('${server.base}/held');result")
            try {
                val pending = async { search(source) }
                withTimeout(10_000) { while (server.holding.count > 0) delay(10) }
                pending.cancel()
                server.release.countDown()
                val error = runCatching { withTimeout(5_000) { pending.await() } }.exceptionOrNull()
                assertTrue(error is CancellationException)
                source.loginCheckJs = "result"
                assertEquals("Before", search(source).name)
                assertEquals(2, server.count("/search"))
            } finally {
                server.release.countDown()
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
