package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class AppChangeCoverStore(context: Context, private val database: AppDatabase = appDb,
    private val searchBooks: suspend (BookSource, String) -> List<SearchBook> = { source, name -> WebBook.searchBookAwait(source, name, shouldBreak = { it > 0 }) },
) : ChangeCoverStore {
    private val directory = File(context.applicationContext.filesDir, "change-cover-sessions")
    override suspend fun cached(target: ChangeCoverTarget) = database.searchBookDao.getEnableHasCover(target.name, target.author)
        .map { ChangeCoverItem("book:${it.bookUrl}", it.origin, it.originName, it.coverUrl.orEmpty(), it.originOrder) }
    override suspend fun sources() = database.bookSourceDao.allEnabledPart.map { it.bookSourceUrl }
    override suspend fun rule(target: ChangeCoverTarget) = BookCover.searchCover(Book(name = target.name, author = target.author))
    override suspend fun search(target: ChangeCoverTarget, source: String): ChangeCoverItem? {
        val entity = database.bookSourceDao.getBookSource(source) ?: return null
        if (entity.getSearchRule().coverUrl.isNullOrBlank()) return null
        val book = searchBooks(entity, target.name).firstOrNull() ?: return null
        if (book.name != target.name || book.author != target.author || book.coverUrl.isNullOrEmpty()) return null
        currentCoroutineContext().ensureActive()
        database.searchBookDao.insert(book)
        return ChangeCoverItem("book:${book.bookUrl}", book.origin, book.originName, book.coverUrl.orEmpty(), book.originOrder)
    }
    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return AtomicFile(File(directory, "$session.json"))
    }
    private fun lock(session: String) = locks.getOrPut(file(session).baseFile.absolutePath) { Mutex() }
    private fun readFile(session: String): ChangeCoverSnapshot? {
        val stream = try { file(session).openRead() } catch (_: java.io.FileNotFoundException) { return null }
        return GSON.fromJsonObject<ChangeCoverSnapshot>(stream.bufferedReader().use { it.readText() }).getOrThrow()
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }
    override suspend fun write(session: String, snapshot: ChangeCoverSnapshot) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if ((readFile(session)?.revision ?: Long.MIN_VALUE) > snapshot.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs())
            val atomic = file(session); val stream = atomic.startWrite()
            try { stream.write(GSON.toJson(snapshot).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
    override fun threadCount() = AppConfig.threadCount
    override fun log(message: String, error: Throwable) { AppLog.put("$message\n${error.localizedMessage}", error) }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
