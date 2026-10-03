package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Large preview text remains on disk; saved state retains only the session ID. */
data class CodeDialogDraft(val original: String, val alternate: String?, val revision: Long)

interface CodeDialogRepository {
    suspend fun read(id: String): CodeDialogDraft?

    suspend fun write(id: String, draft: CodeDialogDraft)
}

class AtomicCodeDialogRepository(context: Context) : CodeDialogRepository {
    private val directory = File(context.applicationContext.filesDir, "code-dialog-drafts")

    private fun file(id: String): AtomicFile {
        UUID.fromString(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法保存代码草稿" }
        return AtomicFile(File(directory, "$id.json"))
    }

    private fun lock(id: String) =
        locks.getOrPut(File(directory, "$id.json").absolutePath) { Mutex() }

    private fun readFile(id: String): CodeDialogDraft? {
        val stream =
            try {
                file(id).openRead()
            } catch (_: java.io.FileNotFoundException) {
                return null
            }
        return GSON.fromJsonObject<CodeDialogDraft>(stream.bufferedReader().use { it.readText() })
            .getOrThrow()
    }

    override suspend fun read(id: String) =
        withContext(Dispatchers.IO) { lock(id).withLock { readFile(id) } }

    override suspend fun write(id: String, draft: CodeDialogDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(id).withLock {
                if ((readFile(id)?.revision ?: Long.MIN_VALUE) > draft.revision) return@withLock
                val target = file(id)
                val output = target.startWrite()
                try {
                    output.write(GSON.toJson(draft).toByteArray())
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            }
        }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
