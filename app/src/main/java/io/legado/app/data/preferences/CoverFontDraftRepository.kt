package io.legado.app.data.preferences

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CoverFontInput(val id: String, val path: String)

internal data class CoverFontDraft(val input: CoverFontInput? = null, val revision: Long = 0)

internal interface CoverFontDraftRepository {
    suspend fun open(session: String): CoverFontDraft

    suspend fun write(session: String, draft: CoverFontDraft)

    suspend fun release(session: String)
}

/** Font paths can be large input values; only the session/ticket lives in SavedState. */
internal class FileCoverFontDraftRepository(context: Context) : CoverFontDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "cover-font-inputs")

    private fun file(session: String): File {
        require(session.matches(Regex("[A-Za-z0-9-]{1,64}")))
        return File(directory, "$session.json")
    }

    private fun lock(file: File) =
        locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun closed(file: File) =
        File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }

    private fun read(file: File): CoverFontDraft =
        AtomicFile(file).openRead().bufferedReader().use {
            GSON.fromJson(it, CoverFontDraft::class.java)
        }

    private fun save(file: File, value: CoverFontDraft) {
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
                check(!closed(file)) { "Cover font input closed" }
                if (file.exists() || File(file.path + ".bak").exists()) read(file)
                else CoverFontDraft().also { save(file, it) }
            }
        }

    override suspend fun write(session: String, draft: CoverFontDraft): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(!closed(file) && (file.exists() || File(file.path + ".bak").exists())) {
                    "Cover font input closed"
                }
                if (draft.revision >= read(file).revision) save(file, draft)
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(file.path + ".closed"))
                val output = marker.startWrite()
                try {
                    output.write("closed".toByteArray())
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                AtomicFile(file).delete()
                check(
                    !file.exists() &&
                        !File(file.path + ".bak").exists() &&
                        !File(file.path + ".new").exists()
                ) {
                    "Unable to release welcome image input"
                }
            }
        }

    private companion object {
        val locks = Array(64) { Mutex() }
    }
}
