package io.legado.app.data.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationOnlineRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun automaticImportClassifiesJsonAndKeepsSeparatePrivatePayloads() = runBlocking {
        val directory = File(context.cacheDir, "association-http-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val first =
            sessions.create(AssociationInput(AssociationHostKind.Online, AssociationInputKind.View))
        val second =
            sessions.create(AssociationInput(AssociationHostKind.Online, AssociationInputKind.View))
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        try {
            val repository = HttpAssociationOnlineRepository(context, sessions)
            val firstPayload =
                repository.determine(first, "http://127.0.0.1:${server.listeningPort}/json")
            val secondPayload =
                repository.determine(second, "http://127.0.0.1:${server.listeningPort}/json")
            assertEquals("bookSource", firstPayload.importType)
            assertTrue(checkNotNull(firstPayload.source).contains(first))
            assertTrue(checkNotNull(secondPayload.source).contains(second))
            assertFalse(firstPayload.source == secondPayload.source)
            sessions.release(first)
            assertTrue(
                runCatching {
                        repository.determine(first, "http://127.0.0.1:${server.listeningPort}/json")
                    }
                    .exceptionOrNull() is AssociationSessionClosed
            )
            assertEquals(AssociationHostKind.Online, sessions.read(second).input.host)
            assertTrue(File(android.net.Uri.parse(secondPayload.source).path!!).exists())
        } finally {
            server.stop()
            sessions.release(first)
            sessions.release(second)
            directory.deleteRecursively()
        }
    }

    @Test
    fun binaryConfigPreservesRawBytesAndWithoutUaHeaderWhileInvalidJsonFails() = runBlocking {
        val directory = File(context.cacheDir, "association-http-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.Online, AssociationInputKind.View))
        val server = FixtureServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        try {
            val repository = HttpAssociationOnlineRepository(context, sessions)
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            val automatic = repository.determine(ticket, "$baseUrl/binary#requestWithoutUA")
            assertEquals(null, automatic.importType)
            assertTrue(
                sessions
                    .readBytes(ticket, checkNotNull(automatic.readConfigFile))
                    .contentEquals(server.binary)
            )
            assertEquals("null", server.headers["/binary"]?.get("user-agent"))
            val explicit = repository.readConfig(ticket, "$baseUrl/binary")
            assertTrue(
                sessions
                    .readBytes(ticket, checkNotNull(explicit.readConfigFile))
                    .contentEquals(server.binary)
            )
            assertEquals("plain text", repository.text("$baseUrl/text"))
            assertTrue(runCatching { repository.determine(ticket, "$baseUrl/unknown") }.isFailure)
            assertEquals(AssociationPhase.Ready, sessions.read(ticket).phase)
        } finally {
            server.stop()
            sessions.release(ticket)
            directory.deleteRecursively()
        }
    }

    private class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        val binary = byteArrayOf(0, 1, -1, 3)
        val headers = ConcurrentHashMap<String, Map<String, String>>()

        override fun serve(session: IHTTPSession): Response {
            headers[session.uri] = session.headers.toMap()
            return when (session.uri) {
                "/binary" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/octet-stream",
                        binary.inputStream(),
                        binary.size.toLong(),
                    )
                "/json" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        "[{\"bookSourceUrl\":\"fixture\"}]",
                    )
                "/text" -> newFixedLengthResponse("plain text")
                else ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        "{\"unknown\":true}",
                    )
            }
        }
    }
}
