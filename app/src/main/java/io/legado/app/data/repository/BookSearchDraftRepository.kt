package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal interface BookSearchDraftRepository {
    suspend fun open(session: String): BookSearchDraft
    suspend fun write(session: String, draft: BookSearchDraft)
    suspend fun release(session: String)
}

/** This search owner uses private files and a terminal tombstone to fence late writers. */
internal class FileBookSearchDraftRepository(context: Context) : BookSearchDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "book-search-drafts")

    private fun file(session: String): File {
        require(session.matches(Regex("[A-Za-z0-9-]{1,64}")))
        return File(directory, "$session.json")
    }

    private fun lock(file: File): Mutex {
        val index = (file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size
        return locks[index]
    }

    private fun closed(file: File): Boolean {
        val marker = File(file.path + ".closed")
        return marker.exists() || File(marker.path + ".bak").exists()
    }

    private fun read(file: File): BookSearchDraft {
        return AtomicFile(file).openRead().bufferedReader().use { reader ->
            GSON.fromJson(reader, BookSearchDraft::class.java)
        }
    }

    private fun save(file: File, draft: BookSearchDraft) {
        check(directory.isDirectory || directory.mkdirs())
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    override suspend fun open(session: String): BookSearchDraft = withContext(Dispatchers.IO) {
        val sessionFile = file(session)
        lock(sessionFile).withLock {
            check(!closed(sessionFile)) { "Book search draft closed" }
            if (sessionFile.exists() || File(sessionFile.path + ".bak").exists()) {
                read(sessionFile)
            } else {
                BookSearchDraft().also { draft -> save(sessionFile, draft) }
            }
        }
    }

    override suspend fun write(session: String, draft: BookSearchDraft): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val sessionFile = file(session)
            lock(sessionFile).withLock {
                val exists = sessionFile.exists() || File(sessionFile.path + ".bak").exists()
                check(!closed(sessionFile) && exists) { "Book search draft closed" }
                // A writer from the previous Activity cannot replace a newer durable draft.
                if (draft.revision >= read(sessionFile).revision) {
                    save(sessionFile, draft)
                }
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val sessionFile = file(session)
            lock(sessionFile).withLock {
                check(directory.isDirectory || directory.mkdirs())
                // Persist closure before deletion so a late writer cannot recreate this session.
                val marker = AtomicFile(File(sessionFile.path + ".closed"))
                val output = marker.startWrite()
                try {
                    output.write("closed".toByteArray())
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                AtomicFile(sessionFile).delete()
                check(
                    !sessionFile.exists() &&
                        !File(sessionFile.path + ".bak").exists() &&
                        !File(sessionFile.path + ".new").exists()
                ) { "Unable to release book search draft" }
            }
        }

    private companion object {
        // Fixed stripes bound lock memory even when many search sessions have been closed.
        val locks = Array(64) { Mutex() }
    }
}
