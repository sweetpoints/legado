package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class BookSourceDebugSnapshot(val key: String, val name: String, val json: String, val keyword: String = "我的")
data class BookSourceDebugSort(val name: String, val url: String) { val query get() = "$name::$url" }
data class BookSourceDebugEvent(val state: Int, val text: String)
data class BookSourceDebugRecord(val sourceKey: String, val query: String = "", val help: Boolean = true,
    val output: String = "", val searchHtml: String? = null, val bookHtml: String? = null, val tocHtml: String? = null, val contentHtml: String? = null,
    val running: Boolean = false, val revision: Long = 0)
interface BookSourceDebugLease {
    suspend fun run(query: String)
    fun close()
    /** Includes parser children and JS execution before a successor can own the global channel. */
    suspend fun awaitStopped()
}
interface BookSourceDebugRepository {
    suspend fun load(key: String): BookSourceDebugSnapshot?
    suspend fun sorts(source: BookSourceDebugSnapshot, refresh: Boolean = false): List<BookSourceDebugSort>
    suspend fun acquire(source: BookSourceDebugSnapshot, event: (BookSourceDebugEvent) -> Unit): BookSourceDebugLease?
    suspend fun read(session: String): BookSourceDebugRecord?
    suspend fun write(session: String, record: BookSourceDebugRecord)
    suspend fun release(session: String)
}
class AppBookSourceDebugRepository(context: Context, private val database: AppDatabase = appDb,
    private val directory: File = File(context.applicationContext.filesDir, "book-source-debug"),
) : BookSourceDebugRepository {
    override suspend fun load(key: String) = withContext(Dispatchers.IO) {
        database.bookSourceDao.getBookSource(key)?.let { BookSourceDebugSnapshot(it.bookSourceUrl, it.bookSourceName, GSON.toJson(it), it.ruleSearch?.checkKeyWord?.takeIf(String::isNotBlank) ?: "我的") }
    }
    private fun parseSource(snapshot: BookSourceDebugSnapshot) = GSON.fromJsonObject<BookSource>(snapshot.json).getOrThrow().also {
        require(it.bookSourceUrl == snapshot.key) { "Invalid debug snapshot" }
    }
    override suspend fun sorts(source: BookSourceDebugSnapshot, refresh: Boolean) = withContext(Dispatchers.IO) {
        val parsed = parseSource(source)
        if (refresh) parsed.clearExploreKindsCache()
        parsed.exploreKinds().filter { !it.url.isNullOrBlank() }.map { BookSourceDebugSort(it.title, it.url.orEmpty()) }
    }
    override suspend fun acquire(source: BookSourceDebugSnapshot, event: (BookSourceDebugEvent) -> Unit): BookSourceDebugLease? = withContext(Dispatchers.IO + NonCancellable) {
        val released = AtomicBoolean(false); val started = AtomicBoolean(false)
        val scopeJob = SupervisorJob(); val scope = CoroutineScope(scopeJob + Dispatchers.IO)
        val terminal = CompletableDeferred<Unit>()
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                if (!released.get()) {
                    event(BookSourceDebugEvent(state, msg))
                    if (state == -1 || state == 1000) terminal.complete(Unit)
                }
            }
        }
        if (!Debug.tryAcquireCallback(callback)) { scopeJob.cancel(); return@withContext null }
        object : BookSourceDebugLease {
            override suspend fun run(query: String) {
                check(!released.get() && started.compareAndSet(false, true)) { "Debug lease is not active" }
                try {
                    withContext(Dispatchers.IO) { currentCoroutineContext().ensureActive(); Debug.startDebug(scope, parseSource(source), query) }
                    terminal.await()
                } finally { close(); withContext(NonCancellable) { awaitStopped() } }
            }
            override fun close() {
                if (released.compareAndSet(false, true)) { Debug.cancelDebug(callback); scopeJob.cancel() }
            }
            override suspend fun awaitStopped() { scopeJob.join() }
        }
    }
    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess) { "Invalid debug session" }
        return AtomicFile(File(directory, "$session.json"))
    }
    private fun readFile(session: String): BookSourceDebugRecord? {
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<BookSourceDebugRecord>(it.readText()).getOrThrow() }
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, record: BookSourceDebugRecord) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            check(!isClosed(session)) { "Debug session is closed" }
            if ((readFile(session)?.revision ?: -1) <= record.revision) {
                directory.mkdirs(); val file = file(session); val output = file.startWrite()
                try { output.write(GSON.toJson(record.copy(output = record.output.takeLast(20_000))).toByteArray()); file.finishWrite(output) }
                catch (error: Throwable) { file.failWrite(output); throw error }
            }
        }
    }
    private fun isClosed(session: String): Boolean {
        val marker = closedFile(session).baseFile
        return marker.exists() || File(marker.path + ".bak").exists()
    }
    private fun closedFile(session: String): AtomicFile {
        file(session) // UUID validation before forming any path.
        return AtomicFile(File(directory, "$session.closed"))
    }
    override suspend fun release(session: String) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            directory.mkdirs()
            val fence = closedFile(session)
            if (!fence.baseFile.exists()) {
                val stream = fence.startWrite()
                try { stream.write(1); fence.finishWrite(stream) }
                catch (error: Throwable) { fence.failWrite(stream); throw error }
            }
            val body = file(session); body.delete()
            File(body.baseFile.path + ".bak").delete(); File(body.baseFile.path + ".new").delete()
            check(listOf(body.baseFile, File(body.baseFile.path + ".bak"), File(body.baseFile.path + ".new")).none { it.exists() }) { "Cannot remove debug session" }
        }
    }
    private fun lock(session: String): Mutex {
        val path = file(session).baseFile.canonicalPath
        return locks[(path.hashCode() and Int.MAX_VALUE) % locks.size]
    }
    companion object { private val locks = Array(64) { Mutex() } }
}
