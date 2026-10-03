package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal interface BookSearchDraftRepository {
    suspend fun open(session: String): BookSearchDraft
    suspend fun write(session: String, draft: BookSearchDraft)
    suspend fun release(session: String)
}
/** This search owner uses private files and a terminal tombstone to fence late writers. */
internal class FileBookSearchDraftRepository(context: Context) : BookSearchDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "book-search-drafts")
    private fun file(session: String): File { require(session.matches(Regex("[A-Za-z0-9-]{1,64}"))); return File(directory, "$session.json") }
    private fun lock(file: File) = locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]
    private fun closed(file: File) = File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }
    private fun read(file: File): BookSearchDraft = AtomicFile(file).openRead().bufferedReader().use { GSON.fromJson(it, BookSearchDraft::class.java) }
    private fun save(file: File, value: BookSearchDraft) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    override suspend fun open(session: String) = withContext(Dispatchers.IO) {
        val file = file(session); lock(file).withLock {
            check(!closed(file)) { "Book search draft closed" }
            if (file.exists() || File(file.path + ".bak").exists()) read(file) else BookSearchDraft().also { save(file, it) }
        }
    }
    override suspend fun write(session: String, draft: BookSearchDraft): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val file = file(session); lock(file).withLock {
            check(!closed(file) && (file.exists() || File(file.path + ".bak").exists())) { "Book search draft closed" }
            if (draft.revision >= read(file).revision) save(file, draft)
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val file = file(session); lock(file).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(file.path + ".closed")); val output = marker.startWrite()
            try { output.write("closed".toByteArray()); marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            AtomicFile(file).delete()
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) { "Unable to release book search draft" }
        }
    }
    private companion object { val locks = Array(64) { Mutex() } }
}
