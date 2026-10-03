package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.model.browser.BrowserSession
import io.legado.app.utils.GSON
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal class AppBrowserSessionStore(context: Context) : BrowserSessionStore {
    private val directory = File(context.applicationContext.filesDir, "browser-sessions")
    override suspend fun read(session: String) = locked(session) { open(session); readFile(target(session)) }
    override suspend fun create(session: String, seed: BrowserSession) = locked(session) {
        open(session); readFile(target(session)) ?: seed.also { writeFile(target(session), GSON.toJson(it)) }
    }
    override suspend fun write(session: String, snapshot: BrowserSession) = locked(session) {
        open(session); val previous = readFile(target(session)) ?: throw BrowserSessionClosedException()
        if (snapshot.revision >= previous.revision) writeFile(target(session), GSON.toJson(snapshot))
    }
    override suspend fun release(session: String) = locked(session) {
        val marker = marker(session)
        if (!marker.exists() && !File(marker.path + ".bak").exists()) writeFile(marker, "closed")
        val target = target(session); AtomicFile(target).delete()
        check(listOf(target, File(target.path + ".bak"), File(target.path + ".new")).none { it.exists() }) { "无法清理网页会话" }
    }
    private fun open(session: String) {
        val marker = marker(session)
        if (marker.exists() || File(marker.path + ".bak").exists()) throw BrowserSessionClosedException()
    }
    private fun readFile(file: File): BrowserSession? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return GSON.fromJson(AtomicFile(file).readFully().toString(Charsets.UTF_8), BrowserSession::class.java) ?: error("网页会话损坏")
    }
    private fun writeFile(file: File, text: String) {
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) { "无法创建网页会话目录" }
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    private fun target(session: String) = File(directory, "$session.json")
    private fun marker(session: String) = File(directory, "$session.closed")
    private suspend fun <T> locked(session: String, block: () -> T): T {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return stripes[(target(session).canonicalPath.hashCode() and Int.MAX_VALUE) % stripes.size].withLock { block() }
    }
    private companion object { val stripes = Array(64) { Mutex() } }
}
