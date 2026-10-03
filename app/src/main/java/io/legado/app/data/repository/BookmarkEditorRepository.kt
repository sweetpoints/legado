package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BookmarkEditorSeed(
    val time: Long,
    val bookName: String,
    val bookAuthor: String,
    val chapterIndex: Int,
    val chapterPos: Int,
    val chapterName: String,
    val bookText: String,
    val content: String,
    val editPos: Int = -1,
) {
    fun bookmark() =
        Bookmark(
            time,
            bookName,
            bookAuthor,
            chapterIndex,
            chapterPos,
            chapterName,
            bookText,
            content,
        )

    companion object {
        fun from(bookmark: Bookmark, editPos: Int = -1) =
            BookmarkEditorSeed(
                bookmark.time,
                bookmark.bookName,
                bookmark.bookAuthor,
                bookmark.chapterIndex,
                bookmark.chapterPos,
                bookmark.chapterName,
                bookmark.bookText,
                bookmark.content,
                editPos,
            )
    }
}

data class BookmarkEditorDraft(
    val seed: BookmarkEditorSeed,
    val bookText: String = seed.bookText,
    val content: String = seed.content,
    val revision: Long = 0,
    val finished: Boolean = false,
)

interface BookmarkEditorRepository {
    suspend fun load(id: String): BookmarkEditorDraft

    suspend fun write(id: String, draft: BookmarkEditorDraft)

    suspend fun commit(id: String, draft: BookmarkEditorDraft, delete: Boolean)
}

class FileBookmarkEditorRepository(context: Context, private val database: AppDatabase = appDb) :
    BookmarkEditorRepository {
    private val directory = File(context.applicationContext.filesDir, "bookmark-editor")

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(directory, "$id.json"))
    }

    private fun read(id: String) =
        GSON.fromJsonObject<BookmarkEditorDraft>(
                file(id).openRead().bufferedReader().use { it.readText() }
            )
            .getOrThrow()

    override suspend fun load(id: String): BookmarkEditorDraft =
        withContext(Dispatchers.IO) {
            pending[id]?.await()
            locks
                .getOrPut(id) { Mutex() }
                .withLock {
                    seeds[id]?.let { seed ->
                        writeFile(directory, id, BookmarkEditorDraft(seed))
                        seeds.remove(id, seed)
                    }
                    read(id)
                }
        }

    override suspend fun write(id: String, draft: BookmarkEditorDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            pending[id]?.await()
            locks
                .getOrPut(id) { Mutex() }
                .withLock {
                    val previous = read(id)
                    if (previous.finished || previous.revision > draft.revision) return@withLock
                    writeFile(directory, id, draft.copy(seed = previous.seed))
                }
        }

    override suspend fun commit(id: String, draft: BookmarkEditorDraft, delete: Boolean) =
        withContext(Dispatchers.IO + NonCancellable) {
            pending[id]?.await()
            locks
                .getOrPut(id) { Mutex() }
                .withLock {
                    val previous = read(id)
                    if (previous.finished) return@withLock
                    val bookmark =
                        previous.seed
                            .bookmark()
                            .copy(bookText = draft.bookText, content = draft.content)
                    if (delete) {
                        check(previous.seed.editPos >= 0)
                        database.bookmarkDao.delete(bookmark)
                    } else database.bookmarkDao.insert(bookmark)
                    writeFile(directory, id, draft.copy(seed = previous.seed, finished = true))
                }
        }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
        // Keep immutable constructor data until the first durable write succeeds.
        private val seeds = ConcurrentHashMap<String, BookmarkEditorSeed>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun stage(context: Context, seed: BookmarkEditorSeed): String {
            val id = UUID.randomUUID().toString()
            val directory = File(context.applicationContext.filesDir, "bookmark-editor")
            seeds[id] = seed
            val job =
                scope.async<Unit>(start = CoroutineStart.LAZY) {
                    try {
                        locks
                            .getOrPut(id) { Mutex() }
                            .withLock {
                                writeFile(directory, id, BookmarkEditorDraft(seed))
                                seeds.remove(id, seed)
                            }
                    } finally {
                        pending.remove(id)
                    }
                }
            pending[id] = job
            job.start()
            return id
        }

        private fun writeFile(directory: File, id: String, draft: BookmarkEditorDraft) {
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
