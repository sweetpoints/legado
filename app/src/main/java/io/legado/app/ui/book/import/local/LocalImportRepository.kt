package io.legado.app.ui.book.import.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.cacheLocalUri
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.list
import io.legado.app.utils.putPrefInt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LocalImportSettings(
    val root: String?,
    val storage: String?,
    val sort: Int,
    val fileNameScript: String,
    val storageHelp: String,
)

internal data class LocalImportRow(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val onShelf: Boolean,
)

internal data class LocalImportFile(val row: LocalImportRow, val document: FileDoc)

internal data class LocalImportResult(
    val importedIds: Set<String>,
    val importedBooks: Int,
    val requestedFiles: Int,
    val groupError: String?,
)

internal interface LocalImportOperations {
    suspend fun settings(): LocalImportSettings

    suspend fun setRoot(value: String)

    suspend fun setStorage(value: String)

    suspend fun privateStorage(): String

    suspend fun setSort(value: Int)

    suspend fun setScript(value: String)

    suspend fun root(value: String): FileDoc

    suspend fun list(directory: FileDoc): List<LocalImportFile>

    suspend fun scan(directory: FileDoc, emit: suspend (List<LocalImportFile>) -> Unit)

    suspend fun canAddGroup(): Boolean

    suspend fun importFiles(files: List<FileDoc>, groupName: String?): LocalImportResult

    suspend fun deleteFiles(files: List<FileDoc>)

    suspend fun archiveEntries(document: FileDoc): List<String>

    suspend fun archiveBook(name: String): Book?

    suspend fun importArchive(document: FileDoc, name: String): Book?

    suspend fun readBook(document: FileDoc): Book?
}

/** All provider metadata, parser work, preferences and Room access stay on IO. */
internal class LocalImportRepository(private val context: Context) : LocalImportOperations {
    override suspend fun settings() =
        withContext(Dispatchers.IO) {
            LocalImportSettings(
                AppConfig.importBookPath,
                AppConfig.defaultBookTreeUri,
                context.getPrefInt(PreferKey.localBookImportSort),
                AppConfig.bookImportFileName.orEmpty(),
                context.assets.open("storageHelp.md").bufferedReader().use { it.readText() },
            )
        }

    override suspend fun setRoot(value: String) =
        withContext(Dispatchers.IO) {
            AppConfig.importBookPath = value
        }

    override suspend fun setStorage(value: String) =
        withContext(Dispatchers.IO) {
            AppConfig.defaultBookTreeUri = value
        }

    override suspend fun privateStorage(): String =
        withContext(Dispatchers.IO) {
            Uri.fromFile(File(context.filesDir, "books")).toString().also {
                AppConfig.defaultBookTreeUri = it
            }
        }

    override suspend fun setSort(value: Int) =
        withContext(Dispatchers.IO) {
            context.putPrefInt(PreferKey.localBookImportSort, value)
        }

    override suspend fun setScript(value: String) =
        withContext(Dispatchers.IO) {
            AppConfig.bookImportFileName = value
        }

    override suspend fun root(value: String): FileDoc =
        withContext(Dispatchers.IO) {
            val uri = if (value.isUri()) value.toUri() else Uri.fromFile(File(value))
            val document =
                if (uri.isContentScheme()) {
                    val tree = requireNotNull(DocumentFile.fromTreeUri(context, uri))
                    require(tree.isDirectory)
                    FileDoc.fromDocumentFile(tree)
                } else {
                    val directory = File(requireNotNull(uri.path))
                    require(directory.isDirectory)
                    FileDoc.fromFile(directory)
                }
            require(document.isDir && document.name.isNotEmpty()) { "Directory is unavailable" }
            document
        }

    private fun shelfFiles() =
        ImportBookShelfFiles(
            appDb.bookDao.localBookFileNames,
            appDb.bookDao.localBookAlternateOrigins,
        )

    private fun file(document: FileDoc, shelf: ImportBookShelfFiles) =
        LocalImportFile(
            LocalImportRow(
                document.uri.toString(),
                document.name,
                document.isDir,
                document.size,
                document.lastModified,
                !document.isDir && document.name in shelf,
            ),
            document,
        )

    override suspend fun list(directory: FileDoc): List<LocalImportFile> =
        withContext(Dispatchers.IO) {
            val shelf = shelfFiles()
            requireNotNull(
                    directory.list { item ->
                        !item.name.startsWith(".") && (item.isDir || supported(item.name))
                    }
                )
                .map { file(it, shelf) }
        }

    private fun supported(name: String) =
        name.matches(AppPattern.bookFileRegex) || name.matches(AppPattern.archiveFileRegex)

    override suspend fun scan(directory: FileDoc, emit: suspend (List<LocalImportFile>) -> Unit) =
        withContext(Dispatchers.IO) {
            val shelf = shelfFiles()
            val pending = ArrayDeque<FileDoc>()
            val visited = HashSet<String>()
            pending.add(directory)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeFirst()
                if (!visited.add(current.uri.toString())) continue
                val found = ArrayList<LocalImportFile>()
                requireNotNull(current.list()).forEach { item ->
                    if (item.isDir) pending.add(item)
                    else if (supported(item.name)) found.add(file(item, shelf))
                }
                if (found.isNotEmpty()) emit(found.toList())
            }
        }

    override suspend fun canAddGroup() =
        withContext(Dispatchers.IO) { appDb.bookGroupDao.canAddGroup }

    /** Accepted synchronous parsers and their Room products finish as one bounded receipt. */
    override suspend fun importFiles(files: List<FileDoc>, groupName: String?): LocalImportResult =
        withContext(Dispatchers.IO + NonCancellable) {
            if (groupName != null && !appDb.bookGroupDao.canAddGroup) {
                throw NoStackTraceException(context.getString(R.string.book_group_limit))
            }
            val fileUris = files.map { it.uri }
            val (importedUris, importedBooks) = LocalBook.importFiles(fileUris)
            val groupError = groupName?.let { name ->
                runCatching {
                    appDb.runInTransaction {
                        val groupDao = appDb.bookGroupDao
                        if (!groupDao.canAddGroup) {
                            throw NoStackTraceException(
                                context.getString(R.string.book_group_limit)
                            )
                        }
                        val groupId = groupDao.getUnusedId()
                        groupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
                        groupDao.insert(
                            BookGroup(
                                groupId = groupId,
                                groupName = name,
                                order = groupDao.maxOrder + 1,
                            )
                        )
                        importedBooks
                            .map { it.bookUrl }
                            .chunked(900)
                            .forEach {
                                appDb.bookDao.addGroup(it, groupId)
                            }
                    }
                }
                    .exceptionOrNull()
            }
            LocalImportResult(
                importedUris.mapTo(HashSet()) { it.toString() },
                importedBooks.size,
                fileUris.size,
                groupError?.localizedMessage,
            )
        }

    override suspend fun deleteFiles(files: List<FileDoc>) =
        withContext(Dispatchers.IO + NonCancellable) {
            files.forEach { it.delete() }
        }

    override suspend fun archiveEntries(document: FileDoc): List<String> =
        withContext(Dispatchers.IO) {
            ArchiveUtils.getArchiveFilesName(document) { it.matches(AppPattern.bookFileRegex) }
        }

    override suspend fun archiveBook(name: String): Book? =
        withContext(Dispatchers.IO) {
            appDb.bookDao.getBookByFileName(name)
        }

    override suspend fun importArchive(document: FileDoc, name: String): Book? =
        withContext(Dispatchers.IO + NonCancellable) {
            LocalBook.importArchiveFile(document.uri, name) { it.contains(name) }.firstOrNull()
        }

    override suspend fun readBook(document: FileDoc): Book? =
        withContext(Dispatchers.IO) {
            val filePath = document.toString()
            val book =
                appDb.bookDao.getBook(filePath)
                    ?: appDb.bookDao.getBookByFileName(document.name)
                    ?: return@withContext null
            LocalBook.withParserCacheInvalidated(book.bookUrl, book.originName) {
                book.removeLocalUriCache()
                book.cacheLocalUri(document.uri)
            }
            book
        }
}
