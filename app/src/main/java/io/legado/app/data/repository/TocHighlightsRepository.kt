package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.model.book.tocHighlightBodyPosition
import io.legado.app.model.book.tocHighlightChapterIndex
import io.legado.app.model.book.tocHighlightColor
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

data class TocHighlightsParameters(
    val bookUrl: String = "",
    val search: String? = null,
    val chapter: Int = 0,
    val supported: Boolean = true,
)

data class TocHighlightRow(
    val id: Long,
    val chapterIndex: Int?,
    val chapter: String,
    val original: String,
    val note: String,
    val color: Int,
)

data class TocHighlightTarget(val highlight: BookHighlight, val chapterIndex: Int?)

data class TocHighlightsCheckpoint(val parameters: TocHighlightsParameters, val revision: Long)

interface TocHighlightsRepository {
    suspend fun checkpoint(session: String): TocHighlightsCheckpoint?

    suspend fun checkpoint(session: String, value: TocHighlightsCheckpoint)

    suspend fun release(session: String)

    fun observe(parameters: TocHighlightsParameters): Flow<List<TocHighlightRow>>

    suspend fun resolve(parameters: TocHighlightsParameters, id: Long): TocHighlightTarget?
}

class RoomTocHighlightsRepository(
    private val database: AppDatabase = appDb,
    private val directory: File = File(appCtx.filesDir, "toc-highlight-state"),
) : TocHighlightsRepository {
    private fun file(session: String): AtomicFile {
        require(UUID.fromString(session).toString() == session)
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun released(session: String) =
        File(directory, "$session.released").exists() ||
            File(directory, "$session.released.bak").exists()

    private fun read(session: String): TocHighlightsCheckpoint? {
        if (released(session)) return null
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<TocHighlightsCheckpoint>(it.readText()).getOrThrow()
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

    override suspend fun checkpoint(session: String, value: TocHighlightsCheckpoint): Unit =
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

    override fun observe(parameters: TocHighlightsParameters): Flow<List<TocHighlightRow>> {
        if (!parameters.supported) return kotlinx.coroutines.flow.flowOf(emptyList())
        val flow =
            if (parameters.search.isNullOrBlank())
                database.bookHighlightDao.flowByBook(parameters.bookUrl)
            else database.bookHighlightDao.flowSearch(parameters.bookUrl, parameters.search)
        return flow
            .map { highlights ->
                val indexes =
                    database.bookChapterDao.getChapterList(parameters.bookUrl).associate {
                        it.url to it.index
                    }
                highlights
                    .sortedWith(
                        compareBy(
                            { tocHighlightChapterIndex(it, indexes) ?: Int.MAX_VALUE },
                            ::tocHighlightBodyPosition,
                            BookHighlight::time,
                        )
                    )
                    .map { row ->
                        TocHighlightRow(
                            row.time,
                            tocHighlightChapterIndex(row, indexes),
                            preview(row.chapterName),
                            preview(row.bookText),
                            preview(row.note),
                            tocHighlightColor(row),
                        )
                    }
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun resolve(
        parameters: TocHighlightsParameters,
        id: Long,
    ): TocHighlightTarget? =
        withContext(Dispatchers.IO) {
            if (!parameters.supported) return@withContext null
            val highlight =
                database.bookHighlightDao
                    .getByBook(parameters.bookUrl)
                    .find { it.time == id }
                    ?.copy() ?: return@withContext null
            val indexes =
                database.bookChapterDao.getChapterList(parameters.bookUrl).associate {
                    it.url to it.index
                }
            TocHighlightTarget(highlight, tocHighlightChapterIndex(highlight, indexes))
        }

    private fun preview(text: String): String {
        // TextView's previous singleLine transformation kept embedded newlines on one line.
        val end =
            if (text.length <= 512) text.length else if (text[511].isHighSurrogate()) 511 else 512
        return text.substring(0, end).replace('\n', ' ').replace('\r', '\uFEFF')
    }
}
