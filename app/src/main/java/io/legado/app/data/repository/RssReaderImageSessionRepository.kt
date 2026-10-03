package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

enum class RssReaderImagePhase {
    PickDirectory,
    Save,
    Complete,
}

data class RssReaderImageSession(
    val owner: String,
    val image: String?,
    val revision: Long,
    val directory: String? = null,
    val fileName: String? = null,
    val phase: RssReaderImagePhase = RssReaderImagePhase.PickDirectory,
)

interface RssReaderImageSessionRepository {
    suspend fun read(session: String): RssReaderImageSession?

    suspend fun write(session: String, value: RssReaderImageSession)

    suspend fun release(session: String)
}

/**
 * The native file picker receives only a small receipt; even a multi-megabyte data URL stays here.
 */
class FileRssReaderImageSessionRepository(
    private val directory: File = File(appCtx.filesDir, "rss-reader-images")
) : RssReaderImageSessionRepository {
    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }

    private fun gate(id: String) =
        gates[(path(id).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun released(id: String) =
        File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()

    private fun load(id: String): RssReaderImageSession? {
        if (released(id)) return null
        val atomic = AtomicFile(path(id))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader().use {
            GSON.fromJsonObject<RssReaderImageSession>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(session: String) =
        withContext(Dispatchers.IO) { gate(session).withLock { load(session) } }

    override suspend fun write(session: String, value: RssReaderImageSession): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(session).withLock {
                if (released(session) || (load(session)?.revision ?: -1) > value.revision)
                    return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val atomic = AtomicFile(path(session))
                val output = atomic.startWrite()
                try {
                    output.write(GSON.toJson(value).toByteArray())
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(session).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$session.released"))
                val output = marker.startWrite()
                try {
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                val body = path(session)
                AtomicFile(body).delete()
                check(
                    listOf(body, File(body.path + ".bak"), File(body.path + ".new")).none {
                        it.exists()
                    }
                )
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
