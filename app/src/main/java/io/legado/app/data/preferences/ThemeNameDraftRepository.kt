package io.legado.app.data.preferences

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ThemeNameDraft(val value: String = "", val revision: Long = 0)

internal interface ThemeNameDraftRepository {
    suspend fun open(session: String): ThemeNameDraft

    suspend fun write(session: String, draft: ThemeNameDraft)

    suspend fun release(session: String)
}

/** Arbitrary-length user-entered names stay in the private storage, never a Fragment Bundle. */
internal class FileThemeNameDraftRepository(context: Context) : ThemeNameDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "theme-settings-drafts")

    private fun file(session: String): File {
        require(session.matches(Regex("[A-Za-z0-9-]{1,64}")))
        return File(directory, "$session.json")
    }

    private fun closed(file: File) =
        File(file.path + ".closed").exists() || File(file.path + ".closed.bak").exists()

    private fun lock(file: File) =
        locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun read(file: File) =
        AtomicFile(file).openRead().bufferedReader().use {
            GSON.fromJson(it, ThemeNameDraft::class.java)
        }

    private fun save(file: File, value: ThemeNameDraft) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    override suspend fun open(session: String) =
        withContext(Dispatchers.IO) {
            val file = file(session)
            lock(file).withLock {
                check(!closed(file)) { "Theme draft closed" }
                if (file.exists() || File(file.path + ".bak").exists()) read(file)
                else ThemeNameDraft().also { save(file, it) }
            }
        }

    override suspend fun write(session: String, draft: ThemeNameDraft): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(!closed(file) && (file.exists() || File(file.path + ".bak").exists())) {
                    "Theme draft closed"
                }
                if (draft.revision >= read(file).revision) save(file, draft)
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(directory.isDirectory || directory.mkdirs())
                if (!closed(file)) {
                    val fence = AtomicFile(File(file.path + ".closed"))
                    val output = fence.startWrite()
                    try {
                        output.write(1)
                        fence.finishWrite(output)
                    } catch (error: Throwable) {
                        fence.failWrite(output)
                        throw error
                    }
                }
                AtomicFile(file).delete()
                check(listOf("", ".bak", ".new").none { File(file.path + it).exists() }) {
                    "Unable to remove theme draft"
                }
            }
        }

    private companion object {
        val locks = Array(64) { Mutex() }
    }
}
