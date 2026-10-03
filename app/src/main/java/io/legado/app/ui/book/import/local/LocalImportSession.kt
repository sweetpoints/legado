package io.legado.app.ui.book.import.local

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
internal enum class LocalImportNativeKind {
    Folder,
    Storage,
    Permission,
    Read,
}

@Keep
internal data class LocalImportNative(
    val nonce: String,
    val kind: LocalImportNativeKind,
    val argument: String? = null,
    val book: Book? = null,
    val claimed: Boolean = false,
)

@Keep
internal data class LocalImportCheckpoint(
    val revision: Long = 0,
    val root: String? = null,
    val directories: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val recursive: Boolean = false,
    val pending: LocalImportNative? = null,
    val importNonce: String? = null,
    val importAccepted: Boolean = false,
    val importedIds: Set<String> = emptySet(),
)

internal interface LocalImportSessions {
    suspend fun read(ticket: String): LocalImportCheckpoint?

    suspend fun write(ticket: String, value: LocalImportCheckpoint): Boolean

    suspend fun close(ticket: String)
}

/** Provider paths, unrestricted search text and Book navigation snapshots never enter Bundle. */
internal class FileLocalImportSessions(private val directory: File) : LocalImportSessions {
    private fun body(ticket: String): AtomicFile {
        require(UUID.fromString(ticket).toString() == ticket)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    private fun gate(ticket: String) =
        gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun closed(ticket: String) =
        File(directory, "$ticket.closed").exists() || File(directory, "$ticket.closed.bak").exists()

    private fun readBody(ticket: String): LocalImportCheckpoint? {
        if (closed(ticket)) return null
        val file = body(ticket)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<LocalImportCheckpoint>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(ticket: String) =
        withContext(Dispatchers.IO) {
            gate(ticket).withLock { readBody(ticket) }
        }

    override suspend fun write(ticket: String, value: LocalImportCheckpoint) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                check(!closed(ticket)) { "Local import session is closed" }
                if ((readBody(ticket)?.revision ?: -1) >= value.revision) return@withLock false
                check(directory.isDirectory || directory.mkdirs())
                val file = body(ticket)
                val output = file.startWrite()
                try {
                    output.write(GSON.toJson(value).toByteArray())
                    file.finishWrite(output)
                    true
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun close(ticket: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$ticket.closed"))
                val output = marker.startWrite()
                try {
                    output.write(1)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                body(ticket).delete()
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}

internal class LocalImportSession(
    private val ticket: String,
    private val sessions: LocalImportSessions,
    private val create: Boolean,
) {
    private val writes = Mutex()
    private var snapshot: LocalImportCheckpoint? = null

    suspend fun load() = writes.withLock { snapshot ?: read().also { snapshot = it } }

    private suspend fun read() =
        sessions.read(ticket)
            ?: if (create) LocalImportCheckpoint()
            else error("Local import session is unavailable; reopen import")

    suspend fun update(
        change: (LocalImportCheckpoint) -> LocalImportCheckpoint
    ): LocalImportCheckpoint =
        withContext(NonCancellable) {
            writes.withLock {
                val current = snapshot ?: read()
                val changed = change(current)
                if (changed == current) return@withLock current.also { snapshot = it }
                val next = changed.copy(revision = current.revision + 1)
                if (!sessions.write(ticket, next)) {
                    snapshot = null
                    error("Local import session receipt was superseded")
                }
                next.also { snapshot = it }
            }
        }

    suspend fun close() = writes.withLock { sessions.close(ticket) }
}
