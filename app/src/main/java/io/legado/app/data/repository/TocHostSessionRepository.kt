package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TocHostSession(val bookUrl: String, val query: String, val revision: Long)
interface TocHostSessionRepository {
    suspend fun read(session: String): TocHostSession?
    suspend fun write(session: String, value: TocHostSession)
    suspend fun release(session: String)
}
class FileTocHostSessionRepository(private val directory: File = File(appCtx.filesDir, "toc-host-state")) : TocHostSessionRepository {
    private fun file(session: String): AtomicFile {
        require(UUID.fromString(session).toString() == session); return AtomicFile(File(directory, "$session.json"))
    }
    private fun released(session: String) = File(directory, "$session.released").exists() || File(directory, "$session.released.bak").exists()
    private fun readFile(session: String): TocHostSession? {
        if (released(session)) return null
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<TocHostSession>(it.readText()).getOrThrow() }
    }
    private fun lock(session: String): Mutex {
        require(UUID.fromString(session).toString() == session)
        val index = (File(directory, session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size
        return gates[index]
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, value: TocHostSession): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if (released(session)) return@withLock
            if ((readFile(session)?.revision ?: -1) > value.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs()); val file = file(session); val output = file.startWrite()
            try { output.write(GSON.toJson(value).toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val fence = AtomicFile(File(directory, "$session.released")); val output = fence.startWrite()
            try { output.write(1); fence.finishWrite(output) }
            catch (error: Throwable) { fence.failWrite(output); throw error }
            file(session).delete()
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
}
