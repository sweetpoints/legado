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

data class RssCategorySession(
    val request: RssCategoryRequest,
    val draft: String = "",
    val revision: Long,
)

interface RssCategorySessionRepository {
    suspend fun read(session: String): RssCategorySession?

    suspend fun write(session: String, value: RssCategorySession)

    suspend fun release(session: String)
}

class FileRssCategorySessionRepository(
    private val directory: File = File(appCtx.filesDir, "rss-category-state")
) : RssCategorySessionRepository {
    private fun path(session: String): File {
        require(UUID.fromString(session).toString() == session)
        return File(directory, "$session.json")
    }

    private fun lock(session: String): Mutex =
        gates[(path(session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun released(session: String): Boolean =
        File(directory, "$session.released").exists() ||
            File(directory, "$session.released.bak").exists()

    private fun readFile(session: String): RssCategorySession? {
        if (released(session)) return null
        val file = AtomicFile(path(session))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<RssCategorySession>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(session: String): RssCategorySession? =
        withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }

    override suspend fun write(session: String, value: RssCategorySession): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if (released(session)) return@withLock
                if ((readFile(session)?.revision ?: -1) > value.revision) return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val file = AtomicFile(path(session))
                val output = file.startWrite()
                try {
                    output.write(GSON.toJson(value).toByteArray())
                    file.finishWrite(output)
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val fence = AtomicFile(File(directory, "$session.released"))
                val output = fence.startWrite()
                try {
                    output.write(1)
                    fence.finishWrite(output)
                } catch (error: Throwable) {
                    fence.failWrite(output)
                    throw error
                }
                val file = path(session)
                AtomicFile(file).delete()
                check(listOf("", ".bak", ".new").none { File(file.path + it).exists() }) {
                    "Unable to remove RSS category session"
                }
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
