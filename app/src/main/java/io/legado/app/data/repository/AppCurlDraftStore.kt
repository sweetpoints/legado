package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.help.IntentData
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Large commands remain in durable app-private storage; revisions fence old editor instances. */
internal class AppCurlDraftStore(context: Context) : CurlDraftStore {
    private val directory = File(context.applicationContext.filesDir, "curl-converter-drafts")
    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return AtomicFile(File(directory, "$session.json"))
    }
    private fun lock(session: String): Mutex = locks.getOrPut(file(session).baseFile.absolutePath) { Mutex() }
    private fun readFile(session: String): CurlConversionDraft? {
        val file = file(session)
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        return GSON.fromJsonObject<CurlConversionDraft>(input.bufferedReader().use { it.readText() }).getOrThrow()
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, draft: CurlConversionDraft) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if ((readFile(session)?.revision ?: Long.MIN_VALUE) > draft.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs()) { "Unable to save conversion draft" }
            val file = file(session); val output = file.startWrite()
            try { output.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun initial(inputKey: String?): String = IntentData.get<String>(inputKey).orEmpty()
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
