package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.sortUrls
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RssSourceDebugSnapshot(val key: String, val name: String, val json: String)
data class RssSourceDebugSort(val name: String, val url: String) { val query get() = "$name::$url" }
data class RssSourceDebugEvent(val state: Int, val text: String)
data class RssSourceDebugRecord(val sourceKey: String, val query: String = "", val help: Boolean = true,
    val output: String = "", val listHtml: String? = null, val contentHtml: String? = null,
    val running: Boolean = false, val revision: Long = 0)
interface RssSourceDebugLease {
    suspend fun run(query: String)
    fun close()
    /** Includes parser children and JS execution before a successor can own the global channel. */
    suspend fun awaitStopped()
}
interface RssSourceDebugRepository {
    suspend fun load(key: String): RssSourceDebugSnapshot?
    suspend fun sorts(source: RssSourceDebugSnapshot): List<RssSourceDebugSort>
    suspend fun acquire(source: RssSourceDebugSnapshot, event: (RssSourceDebugEvent) -> Unit): RssSourceDebugLease?
    suspend fun read(session: String): RssSourceDebugRecord?
    suspend fun write(session: String, record: RssSourceDebugRecord)
}
class AppRssSourceDebugRepository(context: Context, private val database: AppDatabase = appDb,
    private val directory: File = File(context.applicationContext.filesDir, "rss-source-debug"),
) : RssSourceDebugRepository {
    override suspend fun load(key: String) = withContext(Dispatchers.IO) {
        database.rssSourceDao.getByKey(key)?.let { RssSourceDebugSnapshot(it.sourceUrl, it.sourceName, GSON.toJson(it)) }
    }
    private fun parseSource(snapshot: RssSourceDebugSnapshot) = GSON.fromJsonObject<RssSource>(snapshot.json).getOrThrow().also {
        require(it.sourceUrl == snapshot.key) { "Invalid debug snapshot" }
    }
    override suspend fun sorts(source: RssSourceDebugSnapshot) = withContext(Dispatchers.IO) {
        parseSource(source).sortUrls().filter { it.second.isNotBlank() }.map { RssSourceDebugSort(it.first, it.second) }
    }
    override suspend fun acquire(source: RssSourceDebugSnapshot, event: (RssSourceDebugEvent) -> Unit): RssSourceDebugLease? = withContext(Dispatchers.IO + NonCancellable) {
        val released = AtomicBoolean(false); val started = AtomicBoolean(false)
        val scopeJob = SupervisorJob(); val scope = CoroutineScope(scopeJob + Dispatchers.IO)
        val terminal = CompletableDeferred<Unit>()
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                if (!released.get()) {
                    event(RssSourceDebugEvent(state, msg))
                    if (state == -1 || state == 1000) terminal.complete(Unit)
                }
            }
        }
        if (!Debug.tryAcquireCallback(callback)) { scopeJob.cancel(); return@withContext null }
        object : RssSourceDebugLease {
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
    private fun readFile(session: String): RssSourceDebugRecord? {
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<RssSourceDebugRecord>(it.readText()).getOrThrow() }
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, record: RssSourceDebugRecord) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if ((readFile(session)?.revision ?: -1) <= record.revision) {
                directory.mkdirs(); val file = file(session); val output = file.startWrite()
                try { output.write(GSON.toJson(record.copy(output = record.output.takeLast(20_000))).toByteArray()); file.finishWrite(output) }
                catch (error: Throwable) { file.failWrite(output); throw error }
            }
        }
    }
    private fun lock(session: String) = locks.getOrPut(File(directory, session).absolutePath) { Mutex() }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
