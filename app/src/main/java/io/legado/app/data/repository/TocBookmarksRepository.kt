package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

data class TocBookmarksParameters(
    val name: String = "",
    val author: String = "",
    val search: String? = null,
    val chapter: Int = 0,
)

data class TocBookmarkRow(
    val id: Long,
    val chapterIndex: Int,
    val chapter: String,
    val original: String,
    val content: String,
)

data class TocBookmarksCheckpoint(val parameters: TocBookmarksParameters, val revision: Long)

interface TocBookmarksRepository {
    suspend fun checkpoint(session: String): TocBookmarksCheckpoint?

    suspend fun checkpoint(session: String, value: TocBookmarksCheckpoint)

    suspend fun release(session: String)

    fun observe(parameters: TocBookmarksParameters): Flow<List<TocBookmarkRow>>

    suspend fun resolve(parameters: TocBookmarksParameters, id: Long): Bookmark?
}

class RoomTocBookmarksRepository(
    private val database: AppDatabase = appDb,
    private val directory: File = File(appCtx.filesDir, "toc-bookmark-state"),
) : TocBookmarksRepository {
    private fun file(session: String): AtomicFile {
        require(UUID.fromString(session).toString() == session)
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun released(session: String) =
        File(directory, "$session.released").exists() ||
            File(directory, "$session.released.bak").exists()

    private fun read(session: String): TocBookmarksCheckpoint? {
        if (released(session)) return null
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<TocBookmarksCheckpoint>(it.readText()).getOrThrow()
        }
    }

    private fun lock(session: String): Mutex {
        require(UUID.fromString(session).toString() == session)
        val index =
            (File(directory, session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size
        return gates[index]
    }

    override suspend fun checkpoint(session: String) =
        withContext(Dispatchers.IO) { lock(session).withLock { read(session) } }

    override suspend fun checkpoint(session: String, value: TocBookmarksCheckpoint): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if (released(session)) return@withLock
                if ((read(session)?.revision ?: -1) > value.revision) return@withLock
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
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
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

    override fun observe(parameters: TocBookmarksParameters): Flow<List<TocBookmarkRow>> {
        val flow =
            if (parameters.search.isNullOrBlank())
                database.bookmarkDao.flowByBook(parameters.name, parameters.author)
            else
                database.bookmarkDao.flowSearch(
                    parameters.name,
                    parameters.author,
                    parameters.search,
                )
        return flow
            .map { rows ->
                rows.map { row ->
                    TocBookmarkRow(
                        row.time,
                        row.chapterIndex,
                        preview(row.chapterName),
                        preview(row.bookText),
                        preview(row.content),
                    )
                }
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun resolve(parameters: TocBookmarksParameters, id: Long) =
        withContext(Dispatchers.IO) {
            database.bookmarkDao
                .getByBook(parameters.name, parameters.author)
                .find { it.time == id }
                ?.copy()
        }

    private fun preview(text: String): String {
        // TextView's previous singleLine transformation kept embedded newlines on one line.
        val end =
            if (text.length <= 512) text.length else if (text[511].isHighSurrogate()) 511 else 512
        return text.substring(0, end).replace('\n', ' ').replace('\r', '\uFEFF')
    }
}
