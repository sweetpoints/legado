package io.legado.app.data.repository

import androidx.core.net.toUri
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.AutoTask
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class BookDetailPreferences(
    val deleteAlert: Boolean,
    val deleteOriginal: Boolean,
    val uploadImported: Boolean,
    val remoteConfigured: Boolean,
)

enum class BookDetailPreference {
    DeleteAlert,
    DeleteOriginal,
    UploadImported,
}

data class BookDetailRefreshInput(val book: BookDetailBook, val warning: String? = null)

data class BookDetailDownload(val book: BookDetailBook? = null, val uri: String? = null)

data class BookDetailVariable(val key: String, val value: String?, val comment: String)

data class BookDetailUpdateTask(val existingId: String?, val json: String?)

class BookDetailUploadConflict : IllegalStateException("Remote book exists; confirm overwrite")

class BookDetailRemoteMissing : IllegalStateException("WebDAV is not configured")

class BookDetailRemoteDeleteFailed : IllegalStateException("Failed to delete remote book")

/**
 * Existing parser, archive, cache and WebDAV APIs remain the engine boundary, without Activity
 * ownership.
 */
interface BookDetailServiceEngine {
    suspend fun refresh(book: Book, source: BookSource?)

    suspend fun remoteExists(book: Book): Boolean

    suspend fun upload(book: Book, overwrite: Boolean)

    suspend fun deleteRemote(book: Book): Boolean

    suspend fun deleteLocal(book: Book, deleteOriginal: Boolean)

    suspend fun clearCache(book: Book)

    suspend fun download(
        book: Book,
        source: BookSource,
        file: BookDetailWebFile,
    ): BookDetailDownload

    suspend fun archiveEntries(uri: String): List<String>

    suspend fun importArchive(book: Book, uri: String, entry: String): Book
}

object DefaultBookDetailServiceEngine : BookDetailServiceEngine {
    override suspend fun refresh(book: Book, source: BookSource?) {
        if (book.isLocal) {
            book.tocUrl = ""
            book.getRemoteUrl()?.let { url ->
                val remote =
                    (AppWebDav.defaultBookWebDav ?: throw BookDetailRemoteMissing()).getRemoteBook(
                        url
                    )
                if (remote == null) book.origin = BookType.localTag
                else if (
                    remote.lastModify > book.lastCheckTime && LocalBook.downloadRemoteBook(book)
                )
                    book.lastCheckTime = remote.lastModify
            }
        } else source?.let { book.originName = it.bookSourceName }
    }

    override suspend fun remoteExists(book: Book) =
        (AppWebDav.defaultBookWebDav ?: throw BookDetailRemoteMissing()).hasRemoteBook(book)

    override suspend fun upload(book: Book, overwrite: Boolean) =
        (AppWebDav.defaultBookWebDav ?: throw BookDetailRemoteMissing()).uploadWithoutPersist(
            book,
            overwrite,
        )

    override suspend fun deleteRemote(book: Book) =
        (AppWebDav.defaultBookWebDav ?: throw BookDetailRemoteMissing()).delete(book)

    override suspend fun deleteLocal(book: Book, deleteOriginal: Boolean) {
        LocalBook.deleteBook(book, deleteOriginal)
    }

    override suspend fun clearCache(book: Book) {
        BookHelp.clearCache(book)
    }

    override suspend fun download(
        book: Book,
        source: BookSource,
        file: BookDetailWebFile,
    ): BookDetailDownload {
        return if (file.supported) {
            val local =
                LocalBook.importFileOnLine(file.url, book.getExportFileName(file.suffix), source)
            BookDetailDownload(book = BookDetailBook.from(LocalBook.mergeBook(local, book)))
        } else
            BookDetailDownload(
                uri =
                    LocalBook.saveBookFile(file.url, book.getExportFileName(file.suffix), source)
                        .toString()
            )
    }

    override suspend fun archiveEntries(uri: String) =
        ArchiveUtils.getArchiveFilesName(uri.toUri()) { AppPattern.bookFileRegex.matches(it) }

    override suspend fun importArchive(book: Book, uri: String, entry: String): Book {
        val local =
            LocalBook.importArchiveFile(
                    uri.toUri(),
                    book.getExportFileName(entry.substringAfterLast('.')),
                ) {
                    it.contains(entry)
                }
                .first()
        return LocalBook.mergeBook(local, book)
    }
}

interface BookDetailServicesRepository {
    suspend fun preferences(): BookDetailPreferences

    suspend fun preference(field: BookDetailPreference, value: Boolean): BookDetailPreferences

    suspend fun refreshInput(
        book: BookDetailBook,
        source: BookDetailSource?,
    ): BookDetailRefreshInput

    suspend fun remoteExists(book: BookDetailBook): Boolean

    suspend fun upload(book: BookDetailBook, overwrite: Boolean): BookDetailBook

    suspend fun delete(
        book: BookDetailBook,
        deleteOriginal: Boolean,
        deleteRemote: Boolean,
    ): BookDetailBook

    suspend fun clearCache(book: BookDetailBook)

    suspend fun download(
        book: BookDetailBook,
        source: BookDetailSource?,
        file: BookDetailWebFile,
    ): BookDetailDownload

    suspend fun archiveEntries(uri: String): List<String>

    suspend fun importArchive(book: BookDetailBook, uri: String, entry: String): BookDetailBook

    suspend fun variable(
        book: BookDetailBook,
        source: BookDetailSource?,
        sourceVariable: Boolean,
        comment: String,
    ): BookDetailVariable

    suspend fun sourceVariable(source: BookDetailSource?, key: String, value: String?)

    suspend fun updateTask(book: BookDetailBook, name: String): BookDetailUpdateTask
}

/**
 * Mutating implementations must transfer the accepted result inside their own commit dispatcher,
 * before any cancellable dispatcher return hop. The legacy API remains independently compatible.
 */
interface BookDetailAcceptedServicesRepository : BookDetailServicesRepository {
    suspend fun upload(
        book: BookDetailBook,
        overwrite: Boolean,
        accepted: suspend (BookDetailBook) -> Unit,
    ): BookDetailBook

    suspend fun delete(
        book: BookDetailBook,
        deleteOriginal: Boolean,
        deleteRemote: Boolean,
        accepted: suspend (BookDetailBook) -> Unit,
    ): BookDetailBook

    suspend fun download(
        book: BookDetailBook,
        source: BookDetailSource?,
        file: BookDetailWebFile,
        accepted: suspend (BookDetailDownload) -> Unit,
    ): BookDetailDownload

    suspend fun importArchive(
        book: BookDetailBook,
        uri: String,
        entry: String,
        accepted: suspend (BookDetailBook) -> Unit,
    ): BookDetailBook
}

class AppBookDetailServicesRepository(
    private val database: AppDatabase = appDb,
    private val engine: BookDetailServiceEngine = DefaultBookDetailServiceEngine,
    private val snapshotReading: (Book) -> Unit = { it.saveReadRecordSnapshot() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookDetailAcceptedServicesRepository {
    private fun preferencesBody() =
        BookDetailPreferences(
            LocalConfig.bookInfoDeleteAlert,
            LocalConfig.deleteBookOriginal,
            LocalConfig.uploadImportedBookToWebDav,
            AppWebDav.defaultBookWebDav != null,
        )

    override suspend fun preferences() = withContext(io) { preferencesBody() }

    override suspend fun preference(field: BookDetailPreference, value: Boolean) =
        withContext(io) {
            when (field) {
                BookDetailPreference.DeleteAlert -> LocalConfig.bookInfoDeleteAlert = value
                BookDetailPreference.DeleteOriginal -> LocalConfig.deleteBookOriginal = value
                BookDetailPreference.UploadImported ->
                    LocalConfig.uploadImportedBookToWebDav = value
            }
            preferencesBody()
        }

    override suspend fun refreshInput(book: BookDetailBook, source: BookDetailSource?) =
        withContext(io) {
            val native = book.materializeBook()
            var warning: String? = null
            try {
                engine.refresh(native, source?.materializeSource())
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (error is ObjectNotFoundException) native.origin = BookType.localTag
                else warning = error.message ?: error.toString()
            }
            currentCoroutineContext().ensureActive()
            BookDetailRefreshInput(BookDetailBook.from(native), warning)
        }

    override suspend fun remoteExists(book: BookDetailBook) =
        withContext(io) { engine.remoteExists(book.materializeBook()) }

    override suspend fun upload(book: BookDetailBook, overwrite: Boolean): BookDetailBook =
        upload(book, overwrite, {})

    override suspend fun upload(
        book: BookDetailBook,
        overwrite: Boolean,
        accepted: suspend (BookDetailBook) -> Unit,
    ): BookDetailBook =
        withContext(io) {
            val before = database.bookDao.getBook(book.bookUrl) ?: throw BookDetailMissing()
            val baselineOrigin = before.origin
            try {
                engine.upload(before, overwrite)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (
                    !overwrite &&
                        error is WebDavException &&
                        isWebDavOverwriteConflict(error.responseCode)
                )
                    throw BookDetailUploadConflict()
                throw error
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val result =
                    database.runInTransaction<BookDetailBook> {
                        val latest =
                            database.bookDao.getBook(book.bookUrl) ?: throw BookDetailMissing()
                        if (latest.origin != baselineOrigin) throw BookDetailConflict()
                        // Upload changed only remote origin. Reading progress and all unrelated
                        // metadata are reread.
                        latest.origin = before.origin
                        latest.lastCheckTime = clock()
                        database.bookDao.updatePreservingCustomCoverUrl(latest)
                        BookDetailBook.from(latest)
                    }
                accepted(result)
                result
            }
        }

    override suspend fun delete(
        book: BookDetailBook,
        deleteOriginal: Boolean,
        deleteRemote: Boolean,
    ): BookDetailBook = delete(book, deleteOriginal, deleteRemote, {})

    override suspend fun delete(
        book: BookDetailBook,
        deleteOriginal: Boolean,
        deleteRemote: Boolean,
        accepted: suspend (BookDetailBook) -> Unit,
    ): BookDetailBook =
        withContext(io) {
            val preview = book.materializeBook()
            val before = database.bookDao.getBook(book.bookUrl) ?: preview
            if (deleteRemote && !engine.deleteRemote(before)) throw BookDetailRemoteDeleteFailed()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val deleted =
                    database.runInTransaction<Book> {
                        val latest = database.bookDao.getBook(book.bookUrl)
                        if (latest != null) {
                            snapshotReading(latest)
                            database.bookDao.delete(latest)
                        }
                        latest ?: before
                    }
                if (deleted.isLocal) engine.deleteLocal(deleted, deleteOriginal)
                BookDetailBook.from(deleted).also { accepted(it) }
            }
        }

    override suspend fun clearCache(book: BookDetailBook) =
        withContext(io) { engine.clearCache(book.materializeBook()) }

    override suspend fun download(
        book: BookDetailBook,
        source: BookDetailSource?,
        file: BookDetailWebFile,
    ) = download(book, source, file, {})

    override suspend fun download(
        book: BookDetailBook,
        source: BookDetailSource?,
        file: BookDetailWebFile,
        accepted: suspend (BookDetailDownload) -> Unit,
    ) =
        withContext(io) {
            val result =
                engine.download(
                    book.materializeBook(),
                    source?.materializeSource() ?: throw BookDetailNoSource(),
                    file,
                )
            // The engine has accepted/imported the file. Transfer ownership before the cancellable
            // dispatcher return hop.
            withContext(NonCancellable) { accepted(result) }
            currentCoroutineContext().ensureActive()
            result
        }

    override suspend fun archiveEntries(uri: String) =
        withContext(io) { engine.archiveEntries(uri).toList() }

    override suspend fun importArchive(book: BookDetailBook, uri: String, entry: String) =
        importArchive(book, uri, entry, {})

    override suspend fun importArchive(
        book: BookDetailBook,
        uri: String,
        entry: String,
        accepted: suspend (BookDetailBook) -> Unit,
    ) =
        withContext(io) {
            val native = engine.importArchive(book.materializeBook(), uri, entry)
            val result =
                withContext(NonCancellable) { BookDetailBook.from(native).also { accepted(it) } }
            currentCoroutineContext().ensureActive()
            result
        }

    override suspend fun variable(
        book: BookDetailBook,
        source: BookDetailSource?,
        sourceVariable: Boolean,
        comment: String,
    ) =
        withContext(io) {
            val nativeSource = source?.materializeSource() ?: throw BookDetailNoSource()
            val nativeBook = book.materializeBook()
            BookDetailVariable(
                if (sourceVariable) nativeSource.getKey() else nativeBook.bookUrl,
                if (sourceVariable) nativeSource.getVariable() else nativeBook.getCustomVariable(),
                nativeSource.getDisplayVariableComment(comment),
            )
        }

    override suspend fun sourceVariable(source: BookDetailSource?, key: String, value: String?) =
        withContext(io) {
            val original = source?.materializeSource() ?: throw BookDetailNoSource()
            val current =
                database.bookSourceDao.getBookSource(original.bookSourceUrl)
                    ?: throw BookDetailNoSource()
            check(current.getKey() == key) { "Source variable result belongs to another source" }
            current.setVariable(value)
        }

    override suspend fun updateTask(book: BookDetailBook, name: String) =
        withContext(io) {
            val native = book.materializeBook()
            val existing = AutoTask.findBookUpdateTask(AutoTask.all(), native)
            if (existing != null) BookDetailUpdateTask(existing.id, null)
            else BookDetailUpdateTask(null, GSON.toJson(AutoTask.buildBookUpdateTask(native, name)))
        }
}
