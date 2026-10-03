package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BookMemoSnapshot(val content: String, val updatedAt: Long)

data class BookMemoDraft(
    val bookUrl: String,
    val content: String,
    val editing: Boolean,
    val revision: Long,
)

interface BookMemoRepository {
    fun observe(bookUrl: String): kotlinx.coroutines.flow.Flow<BookMemoSnapshot?>

    suspend fun save(bookUrl: String, content: String): BookMemoSnapshot

    suspend fun readDraft(id: String): BookMemoDraft?

    suspend fun writeDraft(id: String, draft: BookMemoDraft)
}

class RoomBookMemoRepository(context: Context, private val database: AppDatabase = appDb) :
    BookMemoRepository {
    private val directory = File(context.applicationContext.filesDir, "book-memo-drafts")

    override fun observe(bookUrl: String) =
        database.bookMemoDao
            .flow(bookUrl)
            .map { it?.let { memo -> BookMemoSnapshot(memo.content, memo.updatedAt) } }
            .flowOn(Dispatchers.IO)

    override suspend fun save(bookUrl: String, content: String) =
        withContext(Dispatchers.IO) {
            database.bookMemoDao.save(bookUrl, content)
            val memo = checkNotNull(database.bookMemoDao.get(bookUrl))
            BookMemoSnapshot(memo.content, memo.updatedAt)
        }

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(directory, "$id.json"))
    }

    override suspend fun readDraft(id: String): BookMemoDraft? =
        withContext(Dispatchers.IO) {
            val value = file(id)
            if (!value.baseFile.exists() && !File(value.baseFile.path + ".bak").exists())
                return@withContext null
            GSON.fromJsonObject<BookMemoDraft>(
                    value.openRead().bufferedReader().use { it.readText() }
                )
                .getOrThrow()
        }

    override suspend fun writeDraft(id: String, draft: BookMemoDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            locks
                .getOrPut(id) { Mutex() }
                .withLock {
                    if ((readDraft(id)?.revision ?: -1) > draft.revision) return@withLock
                    val value = file(id)
                    val output = value.startWrite()
                    try {
                        output.write(GSON.toJson(draft).toByteArray())
                        value.finishWrite(output)
                    } catch (error: Throwable) {
                        value.failWrite(output)
                        throw error
                    }
                }
        }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
