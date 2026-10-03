package io.legado.app.data.repository

import io.legado.app.model.bookshelf.BookshelfManagementDraft
import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal interface BookshelfManagementDraftRepository {
    suspend fun open(session: String): BookshelfManagementDraft
    suspend fun write(session: String, draft: BookshelfManagementDraft)
    suspend fun release(session: String)
}
/** Selection URLs, form text and operation payloads never enter SavedState; a closed owner cannot resurrect files. */
internal class FileBookshelfManagementDraftRepository(context: Context) : BookshelfManagementDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "bookshelf-management-drafts")
    private val exportDirectory = File(context.applicationContext.filesDir, "bookshelf-management-exports")
    private fun file(session: String): File { require(session.matches(Regex("[A-Za-z0-9-]{1,64}"))); return File(directory, "$session.json") }
    private fun lock(file: File) = locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]
    private fun closed(file: File) = File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }
    private fun read(file: File): BookshelfManagementDraft = AtomicFile(file).openRead().bufferedReader().use { GSON.fromJson(it, BookshelfManagementDraft::class.java) }
    private fun save(file: File, value: BookshelfManagementDraft) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    override suspend fun open(session: String) = withContext(Dispatchers.IO) {
        val file = file(session); lock(file).withLock {
            check(!closed(file)) { "Bookshelf management draft closed" }
            if (file.exists() || File(file.path + ".bak").exists()) read(file) else BookshelfManagementDraft().also { save(file, it) }
        }
    }
    override suspend fun write(session: String, draft: BookshelfManagementDraft): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val file = file(session); lock(file).withLock {
            check(!closed(file) && (file.exists() || File(file.path + ".bak").exists())) { "Bookshelf management draft closed" }
            if (draft.revision >= read(file).revision) save(file, draft)
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val file = file(session); lock(file).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val marker = AtomicFile(File(file.path + ".closed")); val output = marker.startWrite()
            try { output.write("closed".toByteArray()); marker.finishWrite(output) } catch (error: Throwable) { marker.failWrite(output); throw error }
            if (file.exists() || File(file.path + ".bak").exists()) read(file).exports.forEach { path ->
                val export = File(path)
                check(export.parentFile?.canonicalFile == exportDirectory.canonicalFile && export.name.matches(Regex("[A-Za-z0-9-]{1,64}\\.json"))) { "Invalid owned bookshelf export" }
                check(!export.exists() || export.delete()) { "Unable to release owned bookshelf export" }
            }
            AtomicFile(file).delete()
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) { "Unable to release bookshelf management draft" }
        }
    }
    private companion object { val locks = Array(64) { Mutex() } }
}
