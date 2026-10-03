package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID

data class HandleFileCheckpoint(val revision: Long = 0, val phase: String = "Choices", val draft: String = "",
    val start: Int = 0, val end: Int = 0, val pending: HandleFilePending? = null,
    val result: String? = null, val finished: Boolean = false)
data class HandleFilePending(val action: Int, val nonce: String, val delivered: Boolean = false)
interface HandleFileChoicesSessionRepository {
    suspend fun stage(id: String, seed: HandleFileSeed): HandleFileInput
    suspend fun input(id: String): HandleFileInput?
    suspend fun bytes(id: String): ByteArray
    suspend fun read(id: String): HandleFileCheckpoint?
    suspend fun write(id: String, value: HandleFileCheckpoint)
    suspend fun release(id: String)
}
/** Owns the original one-shot IntentData payload and unlimited labels, URI results and drafts. */
class FileHandleFileChoicesSessionRepository(private val directory: File = File(appCtx.filesDir, "handle-file-choices")) : HandleFileChoicesSessionRepository {
    private fun folder(id: String): File { require(UUID.fromString(id).toString() == id); return File(directory, id) }
    private fun gate(id: String) = gates[(folder(id).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun released(id: String) = File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()
    private fun file(id: String, name: String) = AtomicFile(File(folder(id), name))
    private fun <T> load(id: String, name: String, decode: (String) -> T): T? {
        if (released(id)) return null
        val target = file(id, name)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return target.openRead().bufferedReader().use { decode(it.readText()) }
    }
    private fun put(id: String, name: String, bytes: ByteArray) {
        check(folder(id).isDirectory || folder(id).mkdirs())
        val target = file(id, name); val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (error: Throwable) { target.failWrite(output); throw error }
    }
    override suspend fun stage(id: String, seed: HandleFileSeed): HandleFileInput = withContext(Dispatchers.IO + NonCancellable) {
        gate(id).withLock {
            check(!released(id))
            load(id, "input.json") { GSON.fromJsonObject<HandleFileInput>(it).getOrThrow() }?.let { return@withLock it }
            seed.payload?.let { data ->
                val bytes = when (data) {
                    is File -> data.readBytes()
                    is ByteArray -> data
                    is String -> data.toByteArray()
                    else -> GSON.toJson(data).toByteArray()
                }
                put(id, "payload.bin", bytes)
            }
            val input = seed.otherActionsJson?.let { raw ->
                seed.input.copy(otherActions = GSON.fromJsonArray<HandleFileChoice>(raw).getOrNull().orEmpty())
            } ?: seed.input
            put(id, "input.json", GSON.toJson(input).toByteArray()); input
        }
    }
    override suspend fun input(id: String): HandleFileInput? = withContext(Dispatchers.IO) {
        gate(id).withLock { load(id, "input.json") { GSON.fromJsonObject<HandleFileInput>(it).getOrThrow() } }
    }
    override suspend fun bytes(id: String): ByteArray = withContext(Dispatchers.IO) {
        gate(id).withLock {
            if (released(id)) throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
            val target = file(id, "payload.bin")
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
            target.openRead().use { it.readBytes() }
        }
    }
    override suspend fun read(id: String): HandleFileCheckpoint? = withContext(Dispatchers.IO) {
        gate(id).withLock { load(id, "state.json") { GSON.fromJsonObject<HandleFileCheckpoint>(it).getOrThrow() } }
    }
    override suspend fun write(id: String, value: HandleFileCheckpoint): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(id).withLock {
            if (released(id) || (load(id, "state.json") { GSON.fromJsonObject<HandleFileCheckpoint>(it).getOrThrow() }?.revision ?: -1) > value.revision) return@withLock
            put(id, "state.json", GSON.toJson(value).toByteArray())
        }
    }
    override suspend fun release(id: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        gate(id).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(directory, "$id.released")); val output = marker.startWrite()
            try { marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            val owned = folder(id); check(!owned.exists() || owned.deleteRecursively()); check(!owned.exists())
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
}
