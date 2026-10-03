package io.legado.app.data.repository

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.installPersistentCover
import io.legado.app.help.book.networkCoverForPersistence
import io.legado.app.help.book.networkCoverSourceOrigin
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.bookshelf.*
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal interface BookshelfCoverStore {
    fun read(id: String): Book?
    suspend fun download(book: Book, url: String): String
    fun installIfUnchanged(book: Book, path: String): Boolean
    fun restoreNetworkIfUnchanged(book: Book): Boolean
    fun restoreSourceIfUnchanged(book: Book): Boolean
    fun failure(book: Book, error: Exception)
}
internal interface BookshelfCoverRepository {
    fun run(ids: List<String>, action: ShelfCoverAction): Flow<ShelfCoverEvent>
}
/** Fresh cover snapshots are committed with the existing DAO compare-and-set predicates. */
internal class DefaultBookshelfCoverRepository(private val store: BookshelfCoverStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookshelfCoverRepository {
    override fun run(ids: List<String>, action: ShelfCoverAction): Flow<ShelfCoverEvent> = flow {
        val unique = ids.distinct(); var saved = 0; var skipped = 0; var failed = 0
        unique.forEachIndexed { index, id ->
            currentCoroutineContext().ensureActive()
            val book = store.read(id)
            if (book == null) { skipped++; return@forEachIndexed }
            emit(ShelfCoverEvent.Progress(index + 1, unique.size))
            when (action) {
                ShelfCoverAction.PersistNetwork -> {
                    val url = book.networkCoverForPersistence()
                    if (url == null) { skipped++; return@forEachIndexed }
                    try {
                        val path = store.download(book, url); currentCoroutineContext().ensureActive()
                        if (store.installIfUnchanged(book, path)) saved++ else skipped++
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { currentCoroutineContext().ensureActive(); failed++; store.failure(book, error) }
                }
                ShelfCoverAction.RestoreNetwork -> if (book.persistedCoverUrl == null || !store.restoreNetworkIfUnchanged(book)) skipped++ else saved++
                ShelfCoverAction.RestoreSource -> if (book.customCoverUrl.isNullOrEmpty() && book.persistedCoverUrl.isNullOrEmpty() || !store.restoreSourceIfUnchanged(book)) skipped++ else saved++
            }
        }
        currentCoroutineContext().ensureActive(); emit(ShelfCoverEvent.Completed(ShelfCoverSummary(saved, skipped, failed)))
    }.flowOn(io).buffer(0)
}
internal class AppBookshelfCoverStore(context: Context, private val database: AppDatabase = appDb) : BookshelfCoverStore {
    private val application = context.applicationContext
    override fun read(id: String) = database.bookDao.getBook(id)
    override suspend fun download(book: Book, url: String): String {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
        book.networkCoverSourceOrigin()?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
        val target = ImageLoader.loadFile(application, url).apply(options).submit()
        try {
            val downloaded = runInterruptible { target.get() }; currentCoroutineContext().ensureActive()
            val validation = Glide.with(application).load(downloaded).submit(1, 1)
            try { runInterruptible { validation.get() } } finally { Glide.with(application).clear(validation) }
            currentCoroutineContext().ensureActive()
            return installPersistentCover(downloaded, File(application.externalFiles, "covers")).absolutePath
        } finally { Glide.with(application).clear(target) }
    }
    override fun installIfUnchanged(book: Book, path: String) = database.bookDao.updatePersistedCoverUrlIfUnchanged(
        book.bookUrl, book.origin, book.coverUrl, book.customCoverUrl, book.persistedCoverUrl, path) == 1
    override fun restoreNetworkIfUnchanged(book: Book) = database.bookDao.clearPersistedCoverUrlIfUnchanged(book.bookUrl, book.persistedCoverUrl!!) == 1
    override fun restoreSourceIfUnchanged(book: Book) = database.bookDao.clearCoverOverridesIfUnchanged(book.bookUrl, book.customCoverUrl, book.persistedCoverUrl) == 1
    override fun failure(book: Book, error: Exception) { AppLog.put("保存封面失败: ${book.name}\n${error.localizedMessage}", error) }
}
