package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

data class SimulatedReadingRequest(val bookUrl: String, val initial: SimulatedReadingSettings)

interface SimulatedReadingRequestRepository {
    suspend fun create(request: SimulatedReadingRequest): String

    suspend fun read(ticket: String): SimulatedReadingRequest

    suspend fun release(ticket: String)
}

class FileSimulatedReadingRequestRepository(
    context: Context,
    private val directory: File =
        File(context.applicationContext.filesDir, "simulated-reading-requests"),
) : SimulatedReadingRequestRepository {
    private fun body(ticket: String): AtomicFile {
        require(runCatching { UUID.fromString(ticket) }.isSuccess)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    override suspend fun create(request: SimulatedReadingRequest): String {
        val ticket = UUID.randomUUID().toString()
        try {
            return withContext(Dispatchers.IO) {
                directory.mkdirs()
                val body = body(ticket)
                val stream = body.startWrite()
                try {
                    stream.write(GSON.toJson(request).toByteArray())
                    body.finishWrite(stream)
                } catch (error: Throwable) {
                    body.failWrite(stream)
                    throw error
                }
                ticket
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { release(ticket) }
            throw error
        }
    }

    override suspend fun read(ticket: String): SimulatedReadingRequest =
        withContext(Dispatchers.IO) {
            body(ticket).openRead().bufferedReader().use {
                GSON.fromJsonObject<SimulatedReadingRequest>(it.readText()).getOrThrow()
            }
        }

    override suspend fun release(ticket: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val body = body(ticket)
            body.delete()
            check(listOf("", ".bak", ".new").none { File(body.baseFile.path + it).exists() }) {
                "Unable to remove simulation request"
            }
        }
}
