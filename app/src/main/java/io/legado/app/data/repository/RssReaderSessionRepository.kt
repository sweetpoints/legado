package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID

data class RssReaderSession(val request: RssReaderRequest, val revision: Long,
    val currentUrl: String? = null, val currentTitle: String? = null)
interface RssReaderSessionRepository {
    suspend fun read(session: String): RssReaderSession?
    suspend fun write(session: String, value: RssReaderSession)
    suspend fun release(session: String)
}
/** HTML, source URLs and browser title checkpoints are private IO-owned values, not Bundle payloads. */
class FileRssReaderSessionRepository(private val directory: File = File(appCtx.filesDir, "rss-reader-state")) : RssReaderSessionRepository {
    private fun path(session: String): File { require(UUID.fromString(session).toString() == session); return File(directory, "$session.json") }
    private fun lock(session: String) = gates[(path(session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun released(session: String) = File(directory, "$session.released").exists() || File(directory, "$session.released.bak").exists()
    private fun readFile(session: String): RssReaderSession? {
        if (released(session)) return null
        val file = AtomicFile(path(session))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<RssReaderSession>(it.readText()).getOrThrow() }
    }
    override suspend fun read(session: String): RssReaderSession? = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, value: RssReaderSession): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if (released(session) || (readFile(session)?.revision ?: -1) > value.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(path(session)); val output = file.startWrite()
            try { output.write(GSON.toJson(value).toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(directory, "$session.released")); val output = marker.startWrite()
            try { marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            val body = path(session)
            AtomicFile(body).delete()
            check(listOf(body, File(body.path + ".bak"), File(body.path + ".new")).none { it.exists() })
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
}
