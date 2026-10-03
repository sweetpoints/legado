package io.legado.app.data.repository

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

@Keep
data class TocHostSession(
    val bookUrl: String,
    val query: String,
    val revision: Long,
    val owner: String? = null,
)

interface TocHostSessionRepository {
    suspend fun read(session: String): TocHostSession?

    suspend fun claim(session: String, bookUrl: String?, owner: String): TocHostSession

    /** False means the file belongs to a newer owner, revision, or released session. */
    suspend fun write(session: String, value: TocHostSession): Boolean

    suspend fun release(session: String, owner: String? = null)
}

class FileTocHostSessionRepository(
    private val directory: File = File(appCtx.filesDir, "toc-host-state")
) : TocHostSessionRepository {
    private fun file(session: String): AtomicFile {
        require(UUID.fromString(session).toString() == session)
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun released(session: String) =
        File(directory, "$session.released").exists() ||
            File(directory, "$session.released.bak").exists()

    private fun readFile(session: String): TocHostSession? {
        if (released(session)) return null
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<TocHostSession>(it.readText()).getOrThrow()
        }
    }

    private fun lock(session: String): Mutex {
        require(UUID.fromString(session).toString() == session)
        val index =
            (File(directory, session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size
        return gates[index]
    }

    override suspend fun read(session: String) =
        withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }

    override suspend fun claim(session: String, bookUrl: String?, owner: String): TocHostSession =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                check(!released(session)) { "目录会话已关闭" }
                val previous = readFile(session)
                val target = bookUrl ?: previous?.bookUrl ?: error("目录会话不存在")
                val value =
                    TocHostSession(
                        bookUrl = target,
                        query = previous?.takeIf { it.bookUrl == target }?.query.orEmpty(),
                        revision = (previous?.revision ?: -1L) + 1L,
                        owner = owner,
                    )
                writeFile(session, value)
                value
            }
        }

    override suspend fun write(session: String, value: TocHostSession): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if (released(session)) return@withLock false
                val previous = readFile(session)
                if (previous != null) {
                    if (previous.owner != value.owner || previous.bookUrl != value.bookUrl) {
                        return@withLock false
                    }
                    if (previous.revision > value.revision) return@withLock false
                    if (previous.revision == value.revision) return@withLock previous == value
                }
                writeFile(session, value)
                true
            }
        }

    private fun writeFile(session: String, value: TocHostSession) {
        check(directory.isDirectory || directory.mkdirs())
        val file = file(session)
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    override suspend fun release(session: String, owner: String?): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if (readFile(session)?.owner != owner) return@withLock
                check(directory.isDirectory || directory.mkdirs())
                val fence = AtomicFile(File(directory, "$session.released"))
                val output = fence.startWrite()
                try {
                    output.write(1)
                    fence.finishWrite(output)
                } catch (error: Throwable) {
                    fence.failWrite(output)
                    throw error
                }
                file(session).delete()
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
