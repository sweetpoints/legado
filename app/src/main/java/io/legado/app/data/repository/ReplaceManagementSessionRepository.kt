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

/** Unlimited queries, selected IDs and modal drafts belong to the owned private checkpoint. */
data class ReplaceManagementCheckpoint(val revision: Long = 0, val query: String = "",
    val queryStart: Int = 0, val queryEnd: Int = 0, val selected: List<Long> = emptyList(),
    val dialog: String? = null, val draft: String = "", val draftStart: Int = 0, val draftEnd: Int = 0,
    val targets: List<Long> = emptyList())
interface ReplaceManagementSessionRepository {
    suspend fun read(session: String): ReplaceManagementCheckpoint?
    suspend fun write(session: String, value: ReplaceManagementCheckpoint)
    suspend fun release(session: String)
}
class FileReplaceManagementSessionRepository(private val directory: File = File(appCtx.filesDir, "replace-management-state")) : ReplaceManagementSessionRepository {
    private fun path(id: String): File { require(UUID.fromString(id).toString() == id); return File(directory, "$id.json") }
    private fun gate(id: String) = gates[(path(id).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun released(id: String) = File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()
    private fun load(id: String): ReplaceManagementCheckpoint? {
        if (released(id)) return null
        val atomic = AtomicFile(path(id))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader().use { GSON.fromJsonObject<ReplaceManagementCheckpoint>(it.readText()).getOrThrow() }
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { gate(session).withLock { load(session) } }
    override suspend fun write(session: String, value: ReplaceManagementCheckpoint): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(session).withLock {
            if (released(session) || (load(session)?.revision ?: -1) > value.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs())
            val atomic = AtomicFile(path(session)); val output = atomic.startWrite()
            try { output.write(GSON.toJson(value).toByteArray()); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(session).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(directory, "$session.released")); val output = marker.startWrite()
            try { marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            val body = path(session); AtomicFile(body).delete()
            check(listOf(body, File(body.path + ".bak"), File(body.path + ".new")).none { it.exists() })
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
}
