package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.*
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import kotlinx.coroutines.*

internal interface RemoteLibraryReadingStore {
    fun showHelpInitially(): Boolean = false

    fun storageUri(): String?

    fun storageHelp(): String

    fun storageUri(value: String)

    fun archive(name: String): Boolean

    fun bookByFile(name: String): Book?

    fun readBook(id: String): Book?

    fun archiveUri(name: String): String?

    fun archiveNames(uri: String): List<String>

    fun importArchive(uri: String, name: String): Book?
}

internal interface RemoteLibraryReadingRepository {
    suspend fun showHelpInitially(): Boolean = false

    suspend fun storageConfigured(): Boolean

    suspend fun storageHelp(): String

    suspend fun storageUri(value: String)

    suspend fun prepare(entry: RemoteLibraryEntry): RemoteLibraryReadTarget

    suspend fun chooseArchive(uri: String, name: String): RemoteLibraryReadTarget

    suspend fun importArchive(uri: String, name: String): String?

    suspend fun importArchiveWithReceipt(
        uri: String,
        name: String,
        accepted: suspend (String?) -> Unit,
    ) {
        accepted(importArchive(uri, name))
    }

    suspend fun readBook(id: String): Book?
}

internal class DefaultRemoteLibraryReadingRepository(
    private val store: RemoteLibraryReadingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteLibraryReadingRepository {
    override suspend fun showHelpInitially() = withContext(io) { store.showHelpInitially() }

    override suspend fun storageConfigured() =
        withContext(io) { !store.storageUri().isNullOrBlank() }

    override suspend fun storageHelp() = withContext(io) { store.storageHelp() }

    override suspend fun storageUri(value: String) =
        withContext(io + NonCancellable) { store.storageUri(value) }

    override suspend fun prepare(entry: RemoteLibraryEntry): RemoteLibraryReadTarget =
        withContext(io) {
            if (!store.archive(entry.name))
                return@withContext store.bookByFile(entry.name)?.let {
                    RemoteLibraryReadTarget.Open(it.bookUrl)
                } ?: RemoteLibraryReadTarget.None
            if (store.storageUri() == null) return@withContext RemoteLibraryReadTarget.None
            val uri =
                store.archiveUri(entry.name)
                    ?: return@withContext RemoteLibraryReadTarget.DownloadArchive(entry)
            val names = store.archiveNames(uri)
            when (names.size) {
                0 -> RemoteLibraryReadTarget.UnsupportedArchive
                1 -> resolveArchive(uri, names.single())
                else -> RemoteLibraryReadTarget.ChooseArchive(uri, names.toList())
            }
        }

    private fun resolveArchive(uri: String, name: String): RemoteLibraryReadTarget =
        store.bookByFile(name)?.let { RemoteLibraryReadTarget.Open(it.bookUrl) }
            ?: RemoteLibraryReadTarget.ImportArchive(uri, name)

    override suspend fun chooseArchive(uri: String, name: String): RemoteLibraryReadTarget =
        withContext(io) { resolveArchive(uri, name) }

    override suspend fun importArchive(uri: String, name: String): String? =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { store.importArchive(uri, name)?.bookUrl }
        }

    override suspend fun importArchiveWithReceipt(
        uri: String,
        name: String,
        accepted: suspend (String?) -> Unit,
    ) =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { accepted(store.importArchive(uri, name)?.bookUrl) }
        }

    override suspend fun readBook(id: String): Book? =
        withContext(io) { store.readBook(id)?.copy() }
}

internal class AppRemoteLibraryReadingStore(
    context: Context,
    private val database: AppDatabase = appDb,
) : RemoteLibraryReadingStore {
    private val application = context.applicationContext

    override fun showHelpInitially() = !LocalConfig.webDavBookHelpVersionIsLast

    override fun storageUri() = AppConfig.defaultBookTreeUri

    override fun storageHelp() =
        application.assets.open("storageHelp.md").use { String(it.readBytes()) }

    override fun storageUri(value: String) {
        AppConfig.defaultBookTreeUri = value
    }

    override fun archive(name: String) = ArchiveUtils.isArchive(name)

    override fun bookByFile(name: String) = database.bookDao.getBookByFileName(name)

    override fun readBook(id: String) = database.bookDao.getBook(id)

    override fun archiveUri(name: String): String? =
        AppConfig.defaultBookTreeUri?.let {
            FileDoc.fromUri(Uri.parse(it), true).find(name)?.uri?.toString()
        }

    override fun archiveNames(uri: String) =
        ArchiveUtils.getArchiveFilesName(Uri.parse(uri)) { it.matches(AppPattern.bookFileRegex) }

    override fun importArchive(uri: String, name: String) =
        LocalBook.importArchiveFile(Uri.parse(uri), name) { it.contains(name) }.firstOrNull()
}
