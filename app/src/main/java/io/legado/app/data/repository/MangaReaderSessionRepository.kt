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

/** Public reader input is captured once; full URLs live on private disk rather than SavedState. */
@Keep
data class MangaReaderLaunch(
    val bookUrl: String? = null,
    val inBookshelf: Boolean = true,
    val chapterChanged: Boolean = false,
)

@Keep
enum class MangaNativeKind {
    BookInfo,
    Catalog,
    ImageDirectory,
    ChapterBrowser,
    ExternalBrowser,
    ChangeSource,
}

@Keep
enum class MangaNativePhase {
    Pending,
    Claimed,
    Complete,
    Cancelled,
}

/** Payload fields are deliberately flat so restored JSON retains their entire original values. */
@Keep
data class MangaNativeRequest(
    val ticket: String,
    val kind: MangaNativeKind,
    val phase: MangaNativePhase = MangaNativePhase.Pending,
    val bookUrl: String? = null,
    val imageUrl: String? = null,
    val title: String? = null,
    val author: String? = null,
    val sourceOrigin: String? = null,
    val sourceName: String? = null,
    val sourceType: Int? = null,
)

@Keep
data class MangaReaderSession(
    val revision: Long,
    val launch: MangaReaderLaunch,
    val menuVisible: Boolean = false,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val nativeRequests: List<MangaNativeRequest> = emptyList(),
)

interface MangaReaderSessionRepository {
    suspend fun read(session: String): MangaReaderSession?

    suspend fun write(session: String, value: MangaReaderSession)

    suspend fun release(session: String)
}

/**
 * Atomic checkpoints are independent for each reader and cannot be revived after owner release. The
 * persisted DTOs and enums keep their Gson names across minified release updates.
 */
class FileMangaReaderSessionRepository(
    private val directory: File = File(appCtx.filesDir, "manga-reader-state")
) : MangaReaderSessionRepository {
    private fun path(session: String): File {
        require(UUID.fromString(session).toString() == session)
        return File(directory, "$session.json")
    }

    private fun gate(session: String): Mutex =
        gates[(path(session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun released(session: String): Boolean =
        File(directory, "$session.released").exists() ||
            File(directory, "$session.released.bak").exists()

    private fun readFile(session: String): MangaReaderSession? {
        if (released(session)) return null
        val atomic = AtomicFile(path(session))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader().use { reader ->
            GSON.fromJsonObject<MangaReaderSession>(reader.readText()).getOrThrow()
        }
    }

    override suspend fun read(session: String): MangaReaderSession? =
        withContext(Dispatchers.IO) { gate(session).withLock { readFile(session) } }

    override suspend fun write(session: String, value: MangaReaderSession): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(session).withLock {
                // A late writer must not erase a newer accepted native receipt or revive an owner.
                if (released(session) || (readFile(session)?.revision ?: -1) >= value.revision) {
                    return@withLock
                }
                check(directory.isDirectory || directory.mkdirs())
                val atomic = AtomicFile(path(session))
                val output = atomic.startWrite()
                try {
                    output.write(GSON.toJson(value).toByteArray())
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(session).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$session.released"))
                val output = marker.startWrite()
                try {
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                // The tombstone is durable before deletion, so a queued write cannot resurrect it.
                val body = path(session)
                AtomicFile(body).delete()
                check(
                    listOf(body, File(body.path + ".bak"), File(body.path + ".new")).none {
                        it.exists()
                    }
                )
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
