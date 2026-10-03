package io.legado.app.data.repository

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

@Keep
data class HandleFileCheckpoint(
    val revision: Long = 0,
    val phase: String = "Choices",
    val draft: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val pending: HandleFilePending? = null,
    val result: String? = null,
    val finished: Boolean = false,
)

@Keep
data class HandleFilePending(
    val action: Int,
    val nonce: String,
    val delivered: Boolean = false,
)

interface HandleFileChoicesSessionRepository {
    suspend fun stage(id: String, seed: HandleFileSeed): HandleFileInput

    suspend fun input(id: String): HandleFileInput?

    suspend fun bytes(id: String): ByteArray

    suspend fun read(id: String): HandleFileCheckpoint?

    suspend fun write(id: String, value: HandleFileCheckpoint)

    suspend fun release(id: String)
}

/** Owns the original one-shot IntentData payload and unrestricted labels, results and drafts. */
class FileHandleFileChoicesSessionRepository(
    private val directory: File = File(appCtx.filesDir, "handle-file-choices")
) : HandleFileChoicesSessionRepository {
    private fun sessionDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, id)
    }

    private fun gate(id: String): Mutex {
        val hash = sessionDirectory(id).canonicalPath.hashCode() and Int.MAX_VALUE
        return gates[hash % gates.size]
    }

    private fun released(id: String): Boolean =
        File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()

    private fun atomicFile(id: String, name: String): AtomicFile =
        AtomicFile(File(sessionDirectory(id), name))

    private fun <T> readJsonOrNull(id: String, name: String, decode: (String) -> T): T? {
        if (released(id)) return null
        val target = atomicFile(id, name)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return target.openRead().bufferedReader().use { reader -> decode(reader.readText()) }
    }

    private fun readInputOrNull(id: String): HandleFileInput? =
        readJsonOrNull(id, "input.json") {
            GSON.fromJsonObject<HandleFileInput>(it).getOrThrow()
        }

    private fun readCheckpointOrNull(id: String): HandleFileCheckpoint? =
        readJsonOrNull(id, "state.json") {
            GSON.fromJsonObject<HandleFileCheckpoint>(it).getOrThrow()
        }

    private fun writeAtomicBytes(id: String, name: String, bytes: ByteArray) {
        val ownedDirectory = sessionDirectory(id)
        check(ownedDirectory.isDirectory || ownedDirectory.mkdirs())
        val target = atomicFile(id, name)
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    override suspend fun stage(id: String, seed: HandleFileSeed): HandleFileInput =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(id).withLock {
                check(!released(id))
                readInputOrNull(id)?.let {
                    return@withLock it
                }
                // IntentData is destructive. Keep its seed retryable until both files are durable.
                seed.payload?.let { payload ->
                    val bytes =
                        when (payload) {
                            is File -> payload.readBytes()
                            is ByteArray -> payload
                            is String -> payload.toByteArray()
                            else -> GSON.toJson(payload).toByteArray()
                        }
                    writeAtomicBytes(id, "payload.bin", bytes)
                }
                val stagedInput =
                    seed.input.copy(
                        sourceFileName = (seed.payload as? File)?.name ?: seed.input.sourceFileName
                    )
                val input =
                    seed.otherActionsJson?.let { json ->
                        stagedInput.copy(
                            otherActions =
                                GSON.fromJsonArray<HandleFileChoice>(json).getOrNull().orEmpty()
                        )
                    } ?: stagedInput
                writeAtomicBytes(id, "input.json", GSON.toJson(input).toByteArray())
                input
            }
        }

    override suspend fun input(id: String): HandleFileInput? =
        withContext(Dispatchers.IO) {
            gate(id).withLock { readInputOrNull(id) }
        }

    override suspend fun bytes(id: String): ByteArray =
        withContext(Dispatchers.IO) {
            gate(id).withLock {
                if (released(id)) throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
                val target = atomicFile(id, "payload.bin")
                if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
                    throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
                }
                target.openRead().use { it.readBytes() }
            }
        }

    override suspend fun read(id: String): HandleFileCheckpoint? =
        withContext(Dispatchers.IO) {
            gate(id).withLock { readCheckpointOrNull(id) }
        }

    override suspend fun write(id: String, value: HandleFileCheckpoint): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(id).withLock {
                val currentRevision = readCheckpointOrNull(id)?.revision ?: -1
                if (released(id) || currentRevision >= value.revision) return@withLock
                writeAtomicBytes(id, "state.json", GSON.toJson(value).toByteArray())
            }
        }

    override suspend fun release(id: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(id).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$id.released"))
                val output = marker.startWrite()
                try {
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                // The durable marker fences late accepted writes before deleting only this owner.
                val ownedDirectory = sessionDirectory(id)
                check(!ownedDirectory.exists() || ownedDirectory.deleteRecursively())
                check(!ownedDirectory.exists())
            }
        }

    private companion object {
        // Fixed stripes share a gate across repository instances without growing per-owner maps.
        val gates = Array(64) { Mutex() }
    }
}
