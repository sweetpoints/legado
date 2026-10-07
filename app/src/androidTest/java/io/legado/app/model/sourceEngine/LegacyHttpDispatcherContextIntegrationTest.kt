package io.legado.app.model.sourceEngine

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real legacy java.ajax RPC: delayed bodies must not move synchronous reads onto Main. */
@RunWith(AndroidJUnit4::class)
class LegacyHttpDispatcherContextIntegrationTest {
    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val bodyStarted = CompletableDeferred<Unit>()
        val headersWaiting = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
        val releaseHeaders = CountDownLatch(1)
        val base
            get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response =
            when (session.uri) {
                "/slow-body" ->
                    newChunkedResponse(
                        Response.Status.OK,
                        "text/plain; charset=utf-8",
                        object : InputStream() {
                            private val bytes = "body-complete".toByteArray(Charsets.UTF_8)
                            private var index = 0

                            override fun read(): Int {
                                if (index == 0) {
                                    bodyStarted.complete(Unit)
                                    check(releaseBody.await(8, TimeUnit.SECONDS)) {
                                        "Body fixture release deadline"
                                    }
                                }
                                return if (index < bytes.size) bytes[index++].toInt() and 255
                                else -1
                            }
                        },
                    )
                "/slow-headers" -> {
                    headersWaiting.complete(Unit)
                    check(releaseHeaders.await(8, TimeUnit.SECONDS)) {
                        "Header fixture release deadline"
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "late-response")
                }
                "/ready" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ready")
                else ->
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Unknown fixture",
                    )
            }
    }

    private fun source(server: FixtureServer) =
        BookSource(
            bookSourceUrl = server.base + "/unsaved-source",
            bookSourceName = "Native HTTP dispatcher context fixture",
        )

    private suspend fun ajax(source: BookSource, url: String): Any? =
        DartSourceEngine.evaluateAuxiliary("java.ajax(${GSON.toJson(url)})", source = source)

    @Test
    fun slowResponseBodyLeavesMainResponsiveAndCompletesAfterExplicitRelease(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source = source(server)
            val pending = async { ajax(source, server.base + "/slow-body") }
            try {
                withTimeout(4_000) { server.bodyStarted.await() }
                // Headers were flushed before NanoHTTPD began reading this held body.
                // Let the actual native response consumer begin its synchronous body read.
                delay(100)
                val heartbeat = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                withTimeout(2_000) {
                    while (heartbeat.count != 0L) delay(10)
                }
                assertTrue(
                    "The held response must still be pending when Main handles its heartbeat",
                    !pending.isCompleted,
                )
                server.releaseBody.countDown()
                assertEquals("body-complete", withTimeout(4_000) { pending.await() })
            } finally {
                server.releaseBody.countDown()
                server.releaseHeaders.countDown()
                pending.cancelAndJoin()
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun cancellationWhileNativeRequestWaitsForHeadersReleasesTaskAndPermitsNextCall(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source = source(server)
            val pending = async { ajax(source, server.base + "/slow-headers") }
            try {
                withTimeout(4_000) { server.headersWaiting.await() }
                assertTrue(
                    "The native request reached the server and is awaiting its response",
                    !pending.isCompleted,
                )
                withTimeout(4_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                server.releaseHeaders.countDown()
                assertEquals("ready", withTimeout(4_000) { ajax(source, server.base + "/ready") })
            } finally {
                server.releaseBody.countDown()
                server.releaseHeaders.countDown()
                pending.cancelAndJoin()
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
