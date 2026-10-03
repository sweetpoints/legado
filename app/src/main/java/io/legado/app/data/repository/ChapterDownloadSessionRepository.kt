package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.entities.Book
import io.legado.app.model.download.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChapterDownloadSessionRepository {
    suspend fun create(
        book: Book,
        mode: ChapterDownloadMode,
        initialChapter: Int,
        chapterCount: Int,
    ): String

    suspend fun read(ticket: String): ChapterDownloadSession?

    suspend fun write(ticket: String, value: ChapterDownloadSession)

    suspend fun book(value: ChapterDownloadSession): Book

    suspend fun release(ticket: String)
}

class FileChapterDownloadSessionRepository(
    context: Context,
    private val directory: File =
        File(context.applicationContext.filesDir, "chapter-download-sessions"),
    private val afterCreate: suspend () -> Unit = {},
) : ChapterDownloadSessionRepository {
    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }

    private fun gate(file: File) =
        gates[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun closed(file: File) =
        File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }

    private fun load(file: File): ChapterDownloadSession? {
        if (closed(file) || (!file.exists() && !File(file.path + ".bak").exists())) return null
        return AtomicFile(file).openRead().bufferedReader().use {
            GSON.fromJsonObject<ChapterDownloadSession>(it.readText()).getOrThrow()
        }
    }

    private fun save(file: File, value: ChapterDownloadSession) {
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

    override suspend fun create(
        book: Book,
        mode: ChapterDownloadMode,
        initialChapter: Int,
        chapterCount: Int,
    ): String {
        val id = UUID.randomUUID().toString()
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                val file = path(id)
                gate(file).withLock {
                    check(!closed(file))
                    save(
                        file,
                        ChapterDownloadSession(
                            GSON.toJson(book),
                            mode,
                            initialChapter,
                            chapterCount,
                        ),
                    )
                    afterCreate()
                }
                id
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { release(id) }
            throw error
        }
    }

    override suspend fun read(ticket: String) =
        withContext(Dispatchers.IO) {
            val file = path(ticket)
            gate(file).withLock { load(file) }
        }

    override suspend fun write(ticket: String, value: ChapterDownloadSession): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = path(ticket)
            gate(file).withLock {
                val previous = checkNotNull(load(file)) { "Chapter download session closed" }
                if (value.revision >= previous.revision) save(file, value)
            }
        }

    override suspend fun book(value: ChapterDownloadSession): Book =
        withContext(Dispatchers.IO) { GSON.fromJsonObject<Book>(value.bookJson).getOrThrow() }

    override suspend fun release(ticket: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = path(ticket)
            gate(file).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(file.path + ".closed"))
                val output = marker.startWrite()
                try {
                    output.write(1)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                AtomicFile(file).delete()
                check(
                    listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
                        .none(File::exists)
                ) {
                    "Unable to release chapter download session"
                }
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
