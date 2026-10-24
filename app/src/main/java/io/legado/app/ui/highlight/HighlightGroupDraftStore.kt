package io.legado.app.ui.highlight

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@androidx.annotation.Keep
internal data class HighlightGroupDraft(
    val stage: HighlightGroupStage = HighlightGroupStage.None,
    val source: String? = null,
    val name: String = "",
    val start: Int = name.length,
    val end: Int = name.length,
    val refresh: Boolean = false,
    val revision: Long = 0,
)
internal interface HighlightGroupDraftStore {
    suspend fun read(ticket: String): HighlightGroupDraft?
    suspend fun write(ticket: String, value: HighlightGroupDraft)
    suspend fun release(ticket: String)
}
internal object MemoryHighlightGroupDraftStore : HighlightGroupDraftStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, HighlightGroupDraft>()
    override suspend fun read(ticket: String) = values[ticket]
    override suspend fun write(ticket: String, value: HighlightGroupDraft) { values[ticket] = value }
    override suspend fun release(ticket: String) { values.remove(ticket) }
}
/** Bundle carries only a random ticket; complete raw UTF16 names and selection stay in private files. */
internal class FileHighlightGroupDraftStore(context: Context) : HighlightGroupDraftStore {
    private val directory = File(context.applicationContext.filesDir, "highlight-group-sessions")
    private fun file(ticket: String): AtomicFile { require(runCatching { UUID.fromString(ticket) }.isSuccess); return AtomicFile(File(directory, "$ticket.json")) }
    private fun fence(ticket: String) = File(directory, "$ticket.closed")
    private fun gate(ticket: String) = gates[(ticket.hashCode() and Int.MAX_VALUE) % gates.size]
    override suspend fun read(ticket: String) = withContext(Dispatchers.IO) {
        gate(ticket).withLock {
            val body = file(ticket)
            if (fence(ticket).exists() || !body.baseFile.exists() && !File(body.baseFile.path + ".bak").exists()) null
            else body.openRead().bufferedReader().use { GSON.fromJsonObject<HighlightGroupDraft>(it.readText()).getOrThrow() }
        }
    }
    override suspend fun write(ticket: String, value: HighlightGroupDraft) = withContext(Dispatchers.IO + NonCancellable) {
        gate(ticket).withLock {
            val body = file(ticket); check(!fence(ticket).exists()); directory.mkdirs()
            val old = if (body.baseFile.exists() || File(body.baseFile.path + ".bak").exists()) body.openRead().bufferedReader().use { GSON.fromJsonObject<HighlightGroupDraft>(it.readText()).getOrThrow() } else null
            if (old != null && old.revision > value.revision) return@withLock
            val out = body.startWrite()
            try { out.write(GSON.toJson(value).toByteArray(Charsets.UTF_8)); body.finishWrite(out) }
            catch (error: Throwable) { body.failWrite(out); throw error }
        }
    }
    override suspend fun release(ticket: String) = withContext(Dispatchers.IO + NonCancellable) {
        gate(ticket).withLock { val body = file(ticket); directory.mkdirs(); fence(ticket).writeText("closed"); body.delete() }
    }
    private companion object { val gates = Array(32) { Mutex() } }
}
