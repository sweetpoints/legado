package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Only immutable navigation fields cross into the UI; mutable books/sources stay in the IO pipeline. */
data class BookLinkTarget(val name: String, val author: String, val bookUrl: String)
interface AddBookLinkRepository { suspend fun resolve(session: String, url: String): BookLinkTarget }
interface AddBookLinkStore {
    suspend fun restore(session: String): BookLinkTarget?
    suspend fun existing(url: String): Book?
    suspend fun source(origin: String): BookSource?
    suspend fun baseSource(baseUrl: String): BookSource?
    suspend fun patternSources(): List<BookSource>
    suspend fun details(url: String, source: BookSource): Book
    suspend fun saveSearchBook(book: Book)
    suspend fun complete(session: String, target: BookLinkTarget)
}
class DefaultAddBookLinkRepository(private val store: AddBookLinkStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : AddBookLinkRepository {
    override suspend fun resolve(session: String, url: String): BookLinkTarget = withContext(dispatcher) {
        if (url.isBlank()) throw NoStackTraceException("url不能为空")
        store.restore(session)?.let { return@withContext it }
        store.existing(url)?.let { book ->
            return@withContext BookLinkTarget(book.name, book.author, book.bookUrl).also { store.complete(session, it) }
        }
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: throw NoStackTraceException("书籍地址格式不对")
        val matcher = AnalyzeUrl.paramPattern.matcher(url)
        val origin = if (matcher.find()) GSON.fromJsonObject<AnalyzeUrl.UrlOption>(url.substring(matcher.end()))
            .getOrNull()?.getOrigin() else null
        suspend fun fromSource(source: BookSource): Book? = try { store.details(url, source) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        var book = origin?.let { store.source(it) }?.let { fromSource(it) }
        if (book == null) book = store.baseSource(baseUrl)?.let { fromSource(it) }
        if (book == null) {
            for (source in store.patternSources()) {
                val matches = runCatching { url.matches(source.bookUrlPattern!!.toRegex()) }.getOrDefault(false)
                if (matches) book = fromSource(source)
                if (book != null) break
            }
        }
        val resolved = book ?: throw NoStackTraceException("未找到匹配书源")
        val target = BookLinkTarget(resolved.name, resolved.author, resolved.bookUrl)
        // Complete both writes before recording a result, so a stale SavedState cannot repeat network work.
        withContext(NonCancellable) { store.saveSearchBook(resolved); store.complete(session, target) }
        target
    }
}
class AppAddBookLinkStore(context: Context) : AddBookLinkStore {
    private val context = context.applicationContext
    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "add-book-link/$session.json"))
    }
    override suspend fun restore(session: String): BookLinkTarget? {
        val target = file(session)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return GSON.fromJsonObject<BookLinkTarget>(target.openRead().bufferedReader().use { it.readText() }).getOrThrow()
    }
    override suspend fun existing(url: String) = appDb.bookDao.getBook(url)
    override suspend fun source(origin: String) = appDb.bookSourceDao.getBookSource(origin)
    override suspend fun baseSource(baseUrl: String) = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
    override suspend fun patternSources() = appDb.bookSourceDao.hasBookUrlPattern.mapNotNull {
        runCatching { it.getBookSource() }.getOrNull()
    }
    override suspend fun details(url: String, source: BookSource) = WebBook.getBookInfoAwait(source,
        Book(bookUrl = url, origin = source.bookSourceUrl, originName = source.bookSourceName))
    override suspend fun saveSearchBook(book: Book) { appDb.searchBookDao.insert(book.toSearchBook()) }
    override suspend fun complete(session: String, target: BookLinkTarget) {
        val file = file(session); val stream = file.startWrite()
        try { stream.write(GSON.toJson(target).toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
