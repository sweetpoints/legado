package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID

data class RssArticlesSession(val parameters: RssArticlesParameters, val initialized: Boolean = false,
    val page: Int = 1, val nextUrl: String? = null, val retry: String? = null, val order: Long = 0, val revision: Long, val hasMore: Boolean = false)
interface RssArticlesSessionRepository {
    suspend fun read(session: String): RssArticlesSession?
    suspend fun write(session: String, value: RssArticlesSession)
    suspend fun release(session: String)
}
class FileRssArticlesSessionRepository(private val directory: File = File(appCtx.filesDir, "rss-articles-state")) : RssArticlesSessionRepository {
    private fun path(session: String): File { require(UUID.fromString(session).toString() == session); return File(directory, "$session.json") }
    private fun lock(session: String): Mutex = gates[(path(session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    private fun released(session: String): Boolean = File(directory, "$session.released").exists() || File(directory, "$session.released.bak").exists()
    private fun readFile(session: String): RssArticlesSession? {
        if (released(session)) return null
        val file = AtomicFile(path(session)); if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<RssArticlesSession>(it.readText()).getOrThrow() }
    }
    override suspend fun read(session: String): RssArticlesSession? = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, value: RssArticlesSession): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if (released(session)) return@withLock
            if ((readFile(session)?.revision ?: -1) > value.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs()); val file = AtomicFile(path(session)); val output = file.startWrite()
            try { output.write(GSON.toJson(value).toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            check(directory.isDirectory || directory.mkdirs()); val fence = AtomicFile(File(directory, "$session.released")); val output = fence.startWrite()
            try { output.write(1); fence.finishWrite(output) }
            catch (error: Throwable) { fence.failWrite(output); throw error }
            val file=path(session);AtomicFile(file).delete()
            check(listOf("", ".bak", ".new").none { File(file.path+it).exists() }) { "Unable to remove RSS article session" }
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
}
