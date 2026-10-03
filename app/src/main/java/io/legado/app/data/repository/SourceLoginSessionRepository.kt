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

interface SourceLoginSessionRepository {
    suspend fun read(id: String): SourceLoginRequest?

    suspend fun write(id: String, request: SourceLoginRequest)

    suspend fun release(id: String)
}

class FileSourceLoginSessionRepository(
    private val directory: File = File(appCtx.filesDir, "source-login-entry")
) : SourceLoginSessionRepository {
    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }

    private fun gate(id: String) =
        gates[(path(id).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun released(id: String) =
        File(directory, "$id.released").exists() || File(directory, "$id.released.bak").exists()

    override suspend fun read(id: String): SourceLoginRequest? =
        withContext(Dispatchers.IO) {
            gate(id).withLock {
                if (released(id)) return@withLock null
                val file = AtomicFile(path(id))
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                    return@withLock null
                file.openRead().bufferedReader().use {
                    GSON.fromJsonObject<SourceLoginRequest>(it.readText()).getOrThrow()
                }
            }
        }

    override suspend fun write(id: String, request: SourceLoginRequest): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(id).withLock {
                if (released(id)) return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val file = AtomicFile(path(id))
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
                val file = path(id)
                AtomicFile(file).delete()
                check(
                    listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none {
                        it.exists()
                    }
                )
            }
        }

    companion object {
        private val gates = Array(64) { Mutex() }
    }
}
