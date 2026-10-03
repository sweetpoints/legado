package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.bookshelf.*
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface BookshelfSourceStore {
    fun source(id: String): BookSource?
    fun read(id: String): Book?
    fun delayMillis(): Long
    suspend fun search(source: BookSource, book: Book): Book?
    suspend fun information(source: BookSource, book: Book)
    suspend fun chapters(source: BookSource, book: Book): List<BookChapter>?
    fun commitIfUnchanged(original: Book, replacement: Book, chapters: List<BookChapter>): Boolean
    fun failure(stage: ShelfSourceStage, error: Exception)
}
internal interface BookshelfSourceRepository {
    fun change(ids: List<String>, sourceId: String): Flow<ShelfSourceEvent>
}
/** Network reads are cancelable; only the accepted short transaction survives cancellation. */
internal class DefaultBookshelfSourceRepository(private val store: BookshelfSourceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookshelfSourceRepository {
    override fun change(ids: List<String>, sourceId: String): Flow<ShelfSourceEvent> = flow {
        val source = requireNotNull(store.source(sourceId)) { "Book source no longer exists" }
        val wait = store.delayMillis().coerceAtLeast(0L)
        val unique = ids.distinct(); var changed = 0; var skipped = 0; var failed = 0
        unique.forEachIndexed { index, id ->
            currentCoroutineContext().ensureActive(); emit(ShelfSourceEvent.Progress(index + 1, unique.size))
            val original = store.read(id)
            if (original == null || original.isLocal || original.origin == source.bookSourceUrl) { skipped++; return@forEachIndexed }
            val replacement = try { store.search(source, original).also { currentCoroutineContext().ensureActive() } }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); store.failure(ShelfSourceStage.Search, error); failed++; return@forEachIndexed }
            if (replacement == null) { skipped++; return@forEachIndexed }
            if (replacement.tocUrl.isEmpty()) {
                try { store.information(source, replacement); currentCoroutineContext().ensureActive() }
                catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); store.failure(ShelfSourceStage.Information, error); failed++; return@forEachIndexed }
            }
            val toc = try { store.chapters(source, replacement).also { currentCoroutineContext().ensureActive() } }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); store.failure(ShelfSourceStage.Chapters, error); failed++; null }
            if (toc != null) {
                currentCoroutineContext().ensureActive()
                if (withContext(NonCancellable) { store.commitIfUnchanged(original, replacement, toc) }) changed++ else skipped++
            }
            delay(wait)
        }
        currentCoroutineContext().ensureActive(); emit(ShelfSourceEvent.Completed(changed, skipped, failed))
    }.flowOn(io).buffer(0)
}
internal class AppBookshelfSourceStore : BookshelfSourceStore {
    override fun source(id: String) = appDb.bookSourceDao.getBookSource(id)
    override fun read(id: String) = appDb.bookDao.getBook(id)
    override fun delayMillis() = AppConfig.batchChangeSourceDelay * 1000L
    override suspend fun search(source: BookSource, book: Book): Book? = WebBook.preciseSearchAwait(source, book.name, book.author).getOrThrow()
    override suspend fun information(source: BookSource, book: Book) { WebBook.getBookInfoAwait(source, book) }
    override suspend fun chapters(source: BookSource, book: Book) = WebBook.getChapterListAwait(source, book).getOrThrow()
    override fun commitIfUnchanged(original: Book, replacement: Book, chapters: List<BookChapter>): Boolean {
        var committed = false
        appDb.runInTransaction {
            val latest = appDb.bookDao.getBook(original.bookUrl) ?: return@runInTransaction
            if (latest.origin != original.origin || latest.name != original.name || latest.author != original.author) return@runInTransaction
            latest.migrateTo(replacement, chapters)
            replacement.removeType(BookType.updateError)
            replaceBookAfterSourceChange(latest, replacement, chapters, clearActiveReader = false)
            committed = true
        }
        return committed
    }
    override fun failure(stage: ShelfSourceStage, error: Exception) {
        val title = when (stage) { ShelfSourceStage.Search -> "搜索书籍出错"; ShelfSourceStage.Information -> "获取书籍详情出错"; ShelfSourceStage.Chapters -> "获取目录出错" }
        AppLog.put("$title\n${error.localizedMessage}", error, true)
    }
}
