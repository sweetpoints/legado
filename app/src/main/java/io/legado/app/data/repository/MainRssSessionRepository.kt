package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/** Reader HTML, queries and complete source identity remain outside the saved-instance bundle. */
data class MainRssCheckpoint(val revision: Long = 0, val query: String = "", val queryStart: Int = 0,
    val queryEnd: Int = 0, val deletingId: String? = null, val deletingName: String? = null,
    val pending: MainRssPrepared? = null)
data class MainRssPrepared(val action: String, val nonce: String, val sourceId: String? = null,
    val sourceUrl: String? = null, val navigation: MainRssNavigation? = null)
interface MainRssSessionRepository {
    suspend fun read(id: String): MainRssCheckpoint?
    suspend fun write(id: String, checkpoint: MainRssCheckpoint)
    suspend fun release(id: String)
}
class FileMainRssSessionRepository(private val directory: File = File(appCtx.filesDir, "main-rss-state")) : MainRssSessionRepository {
    private fun path(id: String): File { require(UUID.fromString(id).toString() == id); return File(directory, "$id.json") }
    private fun gate(id: String) = gates[(path(id).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun released(id: String) = File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()
    private fun load(id: String): MainRssCheckpoint? {
        if (released(id)) return null
        val file = AtomicFile(path(id))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<MainRssCheckpoint>(it.readText()).getOrThrow() }
    }
    override suspend fun read(id: String) = withContext(Dispatchers.IO) { gate(id).withLock { load(id) } }
    override suspend fun write(id: String, checkpoint: MainRssCheckpoint): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(id).withLock {
            if (released(id) || (load(id)?.revision ?: -1) > checkpoint.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(path(id)); val output = file.startWrite()
            try { output.write(GSON.toJson(checkpoint).toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun release(id: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(id).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(directory, "$id.released")); val output = marker.startWrite()
            try { marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            val file = path(id); AtomicFile(file).delete()
            check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { it.exists() })
        }
    }
    companion object { private val gates = Array(64) { Mutex() } }
}
