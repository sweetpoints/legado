package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ManagedFileOpen(val token: String, val uri: String)

data class FileManagementDraft(
    val directory: String? = null,
    val query: String = "",
    val navigation: ManagedFileOpen? = null,
    val revision: Long = 0,
)

interface FileManagementDraftRepository {
    suspend fun read(ticket: String): FileManagementDraft?

    suspend fun write(ticket: String, draft: FileManagementDraft)

    suspend fun release(ticket: String)
}

class AtomicFileManagementDraftRepository(
    context: Context,
    private val directory: File =
        File(context.applicationContext.filesDir, "file-management-drafts"),
) : FileManagementDraftRepository {
    private fun body(ticket: String): AtomicFile {
        require(runCatching { UUID.fromString(ticket) }.isSuccess)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    private fun closed(ticket: String): Boolean {
        body(ticket)
        return File(directory, "$ticket.closed").exists() ||
            File(directory, "$ticket.closed.bak").exists()
    }

    private fun gate(ticket: String) =
        gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun readBody(ticket: String): FileManagementDraft? {
        if (closed(ticket)) return null
        val body = body(ticket)
        if (!body.baseFile.exists() && !File(body.baseFile.path + ".bak").exists()) return null
        return body.openRead().bufferedReader().use {
            GSON.fromJsonObject<FileManagementDraft>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(ticket: String) =
        withContext(Dispatchers.IO) { gate(ticket).withLock { readBody(ticket) } }

    override suspend fun write(ticket: String, draft: FileManagementDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                check(!closed(ticket)) { "File manager is closed" }
                if ((readBody(ticket)?.revision ?: -1) > draft.revision) return@withLock
                directory.mkdirs()
                val body = body(ticket)
                val stream = body.startWrite()
                try {
                    stream.write(GSON.toJson(draft).toByteArray())
                    body.finishWrite(stream)
                } catch (error: Throwable) {
                    body.failWrite(stream)
                    throw error
                }
            }
        }

    override suspend fun release(ticket: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                directory.mkdirs()
                if (!closed(ticket)) {
                    val fence = AtomicFile(File(directory, "$ticket.closed"))
                    val stream = fence.startWrite()
                    try {
                        stream.write(1)
                        fence.finishWrite(stream)
                    } catch (error: Throwable) {
                        fence.failWrite(stream)
                        throw error
                    }
                }
                val body = body(ticket)
                body.delete()
                File(body.baseFile.path + ".bak").delete()
                File(body.baseFile.path + ".new").delete()
                check(
                    listOf(
                            body.baseFile,
                            File(body.baseFile.path + ".bak"),
                            File(body.baseFile.path + ".new"),
                        )
                        .none { it.exists() }
                ) {
                    "Cannot remove file manager draft"
                }
            }
        }

    companion object {
        private val gates = Array(64) { Mutex() }
    }
}
