package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RssFavoriteConfigAction {
    Save,
    Delete,
}

data class RssFavoriteConfigDraft(
    val originalTitle: String?,
    val originalGroup: String?,
    val title: String = originalTitle.orEmpty(),
    val group: String = originalGroup.orEmpty(),
    val revision: Long = 0,
    val action: RssFavoriteConfigAction? = null,
)

interface RssFavoriteConfigRepository {
    suspend fun load(id: String): RssFavoriteConfigDraft

    suspend fun write(id: String, draft: RssFavoriteConfigDraft)
}

/** The reading/video hosts own Room writes and their current article synchronization. */
class FileRssFavoriteConfigRepository(context: Context) : RssFavoriteConfigRepository {
    private val directory = File(context.applicationContext.filesDir, "rss-favorite-config")

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(directory, "$id.json"))
    }

    private fun read(id: String): RssFavoriteConfigDraft =
        GSON.fromJsonObject<RssFavoriteConfigDraft>(
                file(id).openRead().bufferedReader().use { it.readText() }
            )
            .getOrThrow()

    override suspend fun load(id: String): RssFavoriteConfigDraft =
        withContext(Dispatchers.IO) {
            pending[id]?.await()
            locks.getOrPut(id) { Mutex() }.withLock { read(id) }
        }

    override suspend fun write(id: String, draft: RssFavoriteConfigDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            pending[id]?.await()
            locks
                .getOrPut(id) { Mutex() }
                .withLock {
                    if (read(id).revision > draft.revision) return@withLock
                    writeFile(directory, id, draft)
                }
        }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun stage(context: Context, title: String?, group: String?): String {
            val id = UUID.randomUUID().toString()
            val directory = File(context.applicationContext.filesDir, "rss-favorite-config")
            val job =
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        writeFile(directory, id, RssFavoriteConfigDraft(title, group))
                    } finally {
                        pending.remove(id)
                    }
                }
            pending[id] = job
            job.start()
            return id
        }

        private fun writeFile(directory: File, id: String, draft: RssFavoriteConfigDraft) {
            directory.mkdirs()
            val file = AtomicFile(File(directory, "$id.json"))
            val output = file.startWrite()
            try {
                output.write(GSON.toJson(draft).toByteArray())
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
