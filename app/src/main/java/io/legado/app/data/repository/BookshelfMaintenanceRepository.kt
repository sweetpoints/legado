package io.legado.app.data.repository

import android.content.Context
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.AutoTask
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

internal interface BookshelfMaintenanceStore {
    fun read(id: String): Book?
    fun transaction(block: () -> Unit)
    fun snapshot(book: Book)
    fun delete(books: List<Book>)
    fun deleteResources(book: Book, original: Boolean)
    fun clearCache(book: Book)
    fun exportSources(): File
    fun createTasks(books: List<Book>, cron: String): Int
}
internal interface BookshelfMaintenanceRepository {
    suspend fun delete(ids: List<String>, original: Boolean): Int
    suspend fun clearCache(ids: List<String>): Int
    suspend fun exportSources(): File
    suspend fun updateCandidates(ids: List<String>): List<Book>
    suspend fun createTasks(ids: List<String>, cron: String): Int
}
/** IDs are resolved on IO at the time the user's confirmed operation is accepted. */
internal class DefaultBookshelfMaintenanceRepository(private val store: BookshelfMaintenanceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookshelfMaintenanceRepository {
    override suspend fun delete(ids: List<String>, original: Boolean): Int = withContext(io) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            var deleted = emptyList<Book>()
            store.transaction {
                deleted = ids.distinct().mapNotNull(store::read)
                deleted.forEach(store::snapshot)
                store.delete(deleted)
            }
            deleted.forEach { store.deleteResources(it, original) }
            deleted.size
        }
    }
    override suspend fun clearCache(ids: List<String>): Int = withContext(io) {
        var cleared = 0
        ids.distinct().forEach { id -> currentCoroutineContext().ensureActive(); store.read(id)?.let { store.clearCache(it); cleared++ } }
        cleared
    }
    override suspend fun exportSources(): File {
        var owned: File? = null
        try {
            val result = withContext(io) { store.exportSources().also { owned = it } }
            owned = null // Ownership transfers only after the caller receives the prepared file.
            return result
        } catch (error: Exception) {
            val abandoned = owned
            if (abandoned != null) withContext(io + NonCancellable) {
                if (abandoned.exists() && !abandoned.delete()) error.addSuppressed(IllegalStateException("Unable to release abandoned bookshelf export"))
            }
            throw error
        }
    }
    override suspend fun updateCandidates(ids: List<String>): List<Book> = withContext(io) {
        ids.distinct().mapNotNull(store::read).filter { !it.isLocal && it.canUpdate }.map { it.copy() }
    }
    override suspend fun createTasks(ids: List<String>, cron: String): Int = withContext(io) {
        val normalized = cron.trim()
        require(CronSchedule.parse(normalized) != null) { "Invalid book update schedule" }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            store.createTasks(ids.distinct().mapNotNull(store::read).filter { !it.isLocal && it.canUpdate }, normalized)
        }
    }
}
internal class AppBookshelfMaintenanceStore(context: Context) : BookshelfMaintenanceStore {
    private val application = context.applicationContext
    override fun read(id: String) = appDb.bookDao.getBook(id)
    override fun transaction(block: () -> Unit) = appDb.runInTransaction(block)
    override fun snapshot(book: Book) = book.saveReadRecordSnapshot()
    override fun delete(books: List<Book>) { if (books.isNotEmpty()) appDb.bookDao.delete(*books.toTypedArray()) }
    override fun deleteResources(book: Book, original: Boolean) {
        if (book.isLocal) LocalBook.deleteBook(book, original)
        else SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, appDb.bookSourceDao.getBookSource(book.origin), book)
    }
    override fun clearCache(book: Book) = BookHelp.clearCache(book)
    override fun exportSources(): File {
        val directory = File(application.filesDir, "bookshelf-management-exports").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "${UUID.randomUUID()}.json")
        try { file.outputStream().buffered().use { GSON.writeToOutputStream(it, appDb.bookDao.getAllUseBookSource()) }; return file }
        catch (error: Exception) { file.delete(); throw error }
    }
    override fun createTasks(books: List<Book>, cron: String): Int {
        if (books.isEmpty()) return 0
        val tasks = AutoTask.buildBookUpdateTasks(books, AutoTask.all(), cron) { application.getString(R.string.auto_task_book_update_name, it.name) }
        return AutoTask.importRules(tasks, application).size
    }
}
