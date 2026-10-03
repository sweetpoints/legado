package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConfigSearchRequest(val token: String, val query: String)

internal data class ConfigSearchDraft(
    val revision: Long = 0,
    val text: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val searching: Boolean = false,
    val request: ConfigSearchRequest? = null,
    val owner: String? = null,
)

internal interface ConfigSearchSessionRepository {
    suspend fun read(id: String): ConfigSearchDraft

    suspend fun write(id: String, draft: ConfigSearchDraft)

    suspend fun release(id: String)
}

/** Full search text is private; only an opaque owner and small receipts enter SavedState. */
internal class FileConfigSearchSessionRepository(context: Context) : ConfigSearchSessionRepository {
    private val directory = File(context.applicationContext.filesDir, "config-search")

    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }

    private fun gate(file: File) =
        gates[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun closed(file: File) =
        File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }

    private fun load(file: File): ConfigSearchDraft {
        if (!file.exists() && !File(file.path + ".bak").exists()) return ConfigSearchDraft()
        return AtomicFile(file).openRead().bufferedReader().use {
            GSON.fromJson(it, ConfigSearchDraft::class.java)
        }
    }

    override suspend fun read(id: String) =
        withContext(Dispatchers.IO) {
            val file = path(id)
            gate(file).withLock {
                check(!closed(file))
                load(file)
            }
        }

    override suspend fun write(id: String, draft: ConfigSearchDraft): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = path(id)
            gate(file).withLock {
                check(!closed(file))
                if (draft.revision < load(file).revision) return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun release(id: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = path(id)
            gate(file).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(file.path + ".closed"))
                val output = marker.startWrite()
                try {
                    output.write(1)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                AtomicFile(file).delete()
                check(listOf("", ".bak", ".new").none { File(file.path + it).exists() })
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
