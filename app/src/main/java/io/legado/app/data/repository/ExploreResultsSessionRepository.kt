package io.legado.app.data.repository

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

@Keep
data class ExploreResultsRequest(
    val sourceUrl: String,
    val title: String,
    val exploreUrl: String,
)

@Keep data class ExploreResultsCategory(val title: String, val url: String)

/** All values are detached from the mutable parser entities, including their complete metadata. */
@Keep
data class ExploreResultsRow(
    val key: String,
    val bookUrl: String,
    val name: String,
    val author: String,
    val origin: String,
    val coverUrl: String?,
    val intro: String?,
    val latestChapter: String?,
    val kinds: List<String>,
    val metadata: String,
)

@Keep
enum class ExploreResultsNotice {
    AlreadyAdding,
    EmptyResults,
}

@Keep
data class ExploreResultsCheckpoint(
    val request: ExploreResultsRequest,
    val revision: Long = 0,
    val selectedCategory: ExploreResultsCategory =
        ExploreResultsCategory(request.title, request.exploreUrl),
    val categories: List<ExploreResultsCategory> = emptyList(),
    val rows: List<ExploreResultsRow> = emptyList(),
    val firstPage: Int = 1,
    val displayedPage: Int = 1,
    val nextPage: Int = 1,
    val hasMore: Boolean = true,
    val error: String? = null,
    val topError: String? = null,
    val scrollKey: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val pendingPage: Int? = null,
    val pendingPrevious: Boolean = false,
    val addRows: List<ExploreResultsRow>? = null,
    val messageId: String? = null,
    val addedCount: Int? = null,
    val skippedCount: Int? = null,
    val message: String? = null,
    val notice: ExploreResultsNotice? = null,
    val detailKey: String? = null,
    val detailNonce: String? = null,
    val detailDelivered: Boolean = false,
    val detailTicket: String? = null,
    val ownedDetailTickets: List<String> = emptyList(),
    val finished: Boolean = false,
)

interface ExploreResultsSessionRepository {
    suspend fun prepare(sourceUrl: String, title: String, exploreUrl: String): String

    suspend fun read(sessionId: String): ExploreResultsCheckpoint?

    suspend fun write(sessionId: String, checkpoint: ExploreResultsCheckpoint): Boolean

    suspend fun release(sessionId: String)
}

/**
 * The prepared UUID is also the result screen's private session; no large input enters its Bundle.
 */
class AppExploreResultsSessionRepository(
    private val directory: File = File(appCtx.filesDir, "explore-results"),
    private val afterPrepare: suspend (String) -> Unit = {},
) : ExploreResultsSessionRepository {
    override suspend fun prepare(sourceUrl: String, title: String, exploreUrl: String): String {
        val sessionId = UUID.randomUUID().toString()
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val initial =
                    ExploreResultsCheckpoint(ExploreResultsRequest(sourceUrl, title, exploreUrl))
                check(write(sessionId, initial))
                afterPrepare(sessionId)
                currentCoroutineContext().ensureActive()
                sessionId
            }
        } catch (error: Throwable) {
            // Returning from IO can be canceled after AtomicFile accepted the input. Until handoff,
            // the creator owns cleanup, including that return window, and never another session.
            withContext(Dispatchers.IO + NonCancellable) { release(sessionId) }
            throw error
        }
    }

    override suspend fun read(sessionId: String): ExploreResultsCheckpoint? =
        withContext(Dispatchers.IO) {
            gate(sessionId).withLock { readLocked(sessionId) }
        }

    override suspend fun write(sessionId: String, checkpoint: ExploreResultsCheckpoint): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(sessionId).withLock {
                if (released(sessionId)) return@withLock false
                val current = readLocked(sessionId)
                if (current != null && current.revision >= checkpoint.revision)
                    return@withLock false
                check(directory.isDirectory || directory.mkdirs())
                val atomic = body(sessionId)
                val output = atomic.startWrite()
                try {
                    output.write(GSON.toJson(checkpoint).toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
                true
            }
        }

    override suspend fun release(sessionId: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(sessionId).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$sessionId.released"))
                val output = marker.startWrite()
                try {
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                // A durable fence precedes removing AtomicFile sidecars; late writers cannot
                // recreate it.
                val base = body(sessionId).baseFile
                listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { file ->
                    check(!file.exists() || file.delete())
                    check(!file.exists())
                }
            }
        }

    private fun readLocked(sessionId: String): ExploreResultsCheckpoint? {
        if (released(sessionId)) return null
        val atomic = body(sessionId)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val json = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return GSON.fromJsonObject<ExploreResultsCheckpoint>(json).getOrThrow()
    }

    private fun body(sessionId: String): AtomicFile {
        require(UUID.fromString(sessionId).toString() == sessionId)
        return AtomicFile(File(directory, "$sessionId.json"))
    }

    private fun released(sessionId: String): Boolean {
        body(sessionId)
        val marker = File(directory, "$sessionId.released")
        return marker.exists() || File(marker.path + ".bak").exists()
    }

    private fun gate(sessionId: String): Mutex {
        val canonicalPath = body(sessionId).baseFile.canonicalPath
        return gates[(canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]
    }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
