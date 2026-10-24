package io.legado.app.ui.book.toc.rule

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.SavedStateHandle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@androidx.annotation.Keep
data class TxtTocManagementSession(val values: Map<String, String> = emptyMap(), val revision: Long = 0)
interface TxtTocManagementSessionRepository {
    suspend fun read(ticket: String): TxtTocManagementSession?
    suspend fun write(ticket: String, state: TxtTocManagementSession)
    suspend fun release(ticket: String) {}
}
/** Private application files own queries, selections and effect payloads, never saved Bundles. */
internal class FileTxtTocManagementSessionRepository(context: Context) : TxtTocManagementSessionRepository {
    private val directory = File(context.applicationContext.filesDir, "txt-toc-management-sessions")
    private fun file(ticket: String): AtomicFile {
        UUID.fromString(ticket)
        return AtomicFile(File(directory, "$ticket.json"))
    }
    private fun gate(ticket: String) = gates[(file(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun closed(ticket: String) = File(directory, "$ticket.closed").let { it.exists() || File(it.path + ".bak").exists() }
    private fun readFile(ticket: String): TxtTocManagementSession? {
        if (closed(ticket)) return null
        val file = file(ticket)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<TxtTocManagementSession>(it.readText()).getOrThrow() }
    }
    override suspend fun read(ticket: String) = withContext(Dispatchers.IO) { gate(ticket).withLock { readFile(ticket) } }
    override suspend fun write(ticket: String, state: TxtTocManagementSession) = withContext(Dispatchers.IO + NonCancellable) {
        gate(ticket).withLock {
            check(!closed(ticket)) { "TXT session is closed" }
            if ((readFile(ticket)?.revision ?: -1) > state.revision) return@withLock
            directory.mkdirs()
            val file = file(ticket); val stream = file.startWrite()
            try { stream.write(GSON.toJson(state).toByteArray()); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }
    override suspend fun release(ticket: String) = withContext(Dispatchers.IO + NonCancellable) {
        gate(ticket).withLock {
            directory.mkdirs()
            val fence = AtomicFile(File(directory, "$ticket.closed")); val stream = fence.startWrite()
            try { stream.write(1); fence.finishWrite(stream) } catch (error: Throwable) { fence.failWrite(stream); throw error }
            file(ticket).delete()
        }
    }
    companion object { private val gates = Array(64) { Mutex() } }
}

internal fun txtTocSessionSnapshot(saved: SavedStateHandle, revision: Long) = TxtTocManagementSession(
    saved.keys().associateWith { key ->
        val value = saved.get<Any?>(key)
        val kind = when (value) { is String -> "s"; is Boolean -> "b"; is Long -> "l"; is Int -> "i"; is ArrayList<*> -> "a"; null -> "n"; else -> error("Unsupported TXT private state") }
        kind + GSON.toJson(value)
    }, revision,
)
internal fun txtTocRestoreSession(saved: SavedStateHandle, session: TxtTocManagementSession) {
    session.values.forEach { (key, encoded) ->
        require(key.startsWith("txt.toc.management."))
        val text = encoded.drop(1)
        val value: Any? = when (encoded.first()) {
            's' -> GSON.fromJson(text, String::class.java)
            'b' -> GSON.fromJson(text, Boolean::class.java)
            'l' -> GSON.fromJson(text, Long::class.java)
            'i' -> GSON.fromJson(text, Int::class.java)
            'a' -> ArrayList(GSON.fromJson(text, Array<Long>::class.java).toList())
            'n' -> null
            else -> error("Invalid TXT private state")
        }
        saved[key] = value
    }
}

/** Fragment arguments carry only this capability; the reader regex stays in private storage. */
internal object TxtTocPickerSeed {
    private val directory get() = File(splitties.init.appCtx.filesDir, "txt-toc-picker-seeds")
    private fun file(ticket: String): AtomicFile { UUID.fromString(ticket); return AtomicFile(File(directory, "$ticket.txt")) }
    fun create(value: String?): String? {
        if (value == null) return null
        val ticket = UUID.randomUUID().toString(); directory.mkdirs()
        val file = file(ticket); val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
        return ticket
    }
    suspend fun read(ticket: String): String = withContext(Dispatchers.IO) { file(ticket).openRead().bufferedReader().use { it.readText() } }
    suspend fun release(ticket: String) = withContext(Dispatchers.IO + NonCancellable) { file(ticket).delete() }
}
