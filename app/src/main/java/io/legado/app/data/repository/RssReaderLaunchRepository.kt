package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

/** One owned UUID crosses the Activity boundary; complete reader inputs only cross private IO. */
interface RssReaderLaunchRepository {
    suspend fun stage(request: RssReaderRequest): String

    suspend fun read(ticket: String): RssReaderRequest?

    suspend fun release(ticket: String)
}

class FileRssReaderLaunchRepository(
    private val directory: File = File(appCtx.filesDir, "rss-reader-launch"),
    private val afterStage: (File) -> Unit = {},
) : RssReaderLaunchRepository {
    private fun path(ticket: String): File {
        require(UUID.fromString(ticket).toString() == ticket)
        return File(directory, "$ticket.json")
    }

    private fun gate(ticket: String) =
        gates[(path(ticket).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    override suspend fun stage(request: RssReaderRequest): String {
        val ticket = UUID.randomUUID().toString()
        try {
            return withContext(Dispatchers.IO) {
                gate(ticket).withLock {
                    withContext(NonCancellable) {
                        check(directory.isDirectory || directory.mkdirs())
                        val file = AtomicFile(path(ticket))
                        val output = file.startWrite()
                        try {
                            output.write(GSON.toJson(request).toByteArray())
                            file.finishWrite(output)
                        } catch (error: Throwable) {
                            file.failWrite(output)
                            throw error
                        }
                    }
                }
                afterStage(path(ticket))
                currentCoroutineContext().ensureActive()
                ticket
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { runCatching { release(ticket) } }
            throw error
        }
    }

    override suspend fun read(ticket: String): RssReaderRequest? =
        withContext(Dispatchers.IO) {
            gate(ticket).withLock {
                val file = AtomicFile(path(ticket))
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                    return@withLock null
                file.openRead().bufferedReader().use {
                    GSON.fromJsonObject<RssReaderRequest>(it.readText()).getOrThrow()
                }
            }
        }

    override suspend fun release(ticket: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                val body = path(ticket)
                AtomicFile(body).delete()
                check(
                    listOf(body, File(body.path + ".bak"), File(body.path + ".new")).none {
                        it.exists()
                    }
                )
            }
        }

    companion object {
        private val gates = Array(64) { Mutex() }
    }
}
