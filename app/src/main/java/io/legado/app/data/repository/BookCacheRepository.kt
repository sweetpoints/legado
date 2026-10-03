package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.startService
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BookCacheItem(
    val key: String,
    val name: String,
    val author: String,
    val local: Boolean,
    val total: Int,
    val current: Int,
    val last: Int,
)

data class BookCacheGroup(val id: Long, val name: String)

data class BookCacheScan(val chapters: Set<String>, val total: Int)

data class BookCacheRuntime(
    val running: Boolean,
    val downloading: Set<String>,
    val exportProgress: Map<String, Int>,
    val exportMessages: Map<String, String>,
)

data class BookCachePreferences(
    val replace: Boolean = false,
    val custom: Boolean = false,
    val noChapterName: Boolean = false,
    val webDav: Boolean = false,
    val pictures: Boolean = false,
    val parallel: Boolean = false,
    val type: Int = 0,
    val charset: String = "UTF-8",
    val fileName: String? = null,
    val episodeFileName: String? = null,
) {
    val exportType: String
        get() =
            when (type) {
                1 -> "epub"
                2 -> "pdf"
                else -> "txt"
            }

    val customEpub: Boolean
        get() = custom && type == 1
}

enum class BookCachePreference {
    Replace,
    Custom,
    NoChapterName,
    WebDav,
    Pictures,
    Parallel,
    Type,
    Charset,
    FileName,
    EpisodeFileName,
}

data class BookCacheSectionDraft(
    val path: String,
    val all: Boolean,
    val size: String,
    val scope: String,
    val name: String,
    val revision: Long,
)

data class BookCacheFolderResult(val path: String?)

private data class BookCachePayload(
    val keys: List<String>,
    val section: BookCacheSectionDraft? = null,
    val folderResult: BookCacheFolderResult? = null,
)

data class BookCacheExport(
    val keys: List<String>,
    val path: String,
    val type: String,
    val size: Int? = null,
    val scope: String? = null,
)

interface BookCacheRepository {
    fun books(group: Long): Flow<List<BookCacheItem>>

    fun groups(): Flow<List<BookCacheGroup>>

    suspend fun scan(key: String): BookCacheScan?

    suspend fun runtime(): BookCacheRuntime

    suspend fun preferences(): BookCachePreferences

    suspend fun preferences(value: BookCachePreferences, fields: Set<BookCachePreference>)

    suspend fun cachedPath(): String?

    suspend fun rememberPath(path: String)

    suspend fun writable(path: String): Boolean

    suspend fun download(keys: List<String>, afterCurrent: Boolean)

    suspend fun stopDownloads()

    suspend fun toggleDownload(key: String)

    suspend fun export(request: BookCacheExport)

    suspend fun stage(keys: List<String>): String

    suspend fun staged(ticket: String): List<String>

    suspend fun readSection(ticket: String): BookCacheSectionDraft?

    suspend fun writeSection(ticket: String, draft: BookCacheSectionDraft)

    suspend fun folderResult(ticket: String): BookCacheFolderResult?

    suspend fun folderResult(ticket: String, result: BookCacheFolderResult): Boolean

    suspend fun release(ticket: String)

    suspend fun episodeName(key: String, script: String): String?

    suspend fun validEpisodeName(script: String): Boolean
}

/** The existing download/export services remain the owners of their work. */
class RoomBookCacheRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val sort: (Long) -> Int = AppConfig::getBookSortByGroupId,
    private val files: (io.legado.app.data.entities.Book) -> Set<String> =
        BookHelp::getChapterFiles,
    private val directory: File = File(context.applicationContext.filesDir, "book-cache-export"),
    private val beforeSectionWrite: (Long) -> Unit = {},
) : BookCacheRepository {
    private val context = context.applicationContext
    private val commands = Mutex()

    private fun payload(ticket: String): AtomicFile {
        require(UUID.fromString(ticket).toString() == ticket)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    override suspend fun stage(keys: List<String>): String =
        withContext(IO) {
            val ticket = UUID.randomUUID().toString()
            directory.mkdirs()
            val file = payload(ticket)
            val stream = file.startWrite()
            try {
                stream.write(
                    GSON.toJson(BookCachePayload(keys.distinct())).toByteArray(Charsets.UTF_8)
                )
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
            ticket
        }

    private fun gate(ticket: String): Mutex =
        ticketGates[
            (payload(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) %
                ticketGates.size]

    override suspend fun staged(ticket: String): List<String> =
        withContext(IO) {
            gate(ticket).withLock {
                val text =
                    payload(ticket).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
                GSON.fromJsonObject<BookCachePayload>(text).getOrThrow().keys.toList()
            }
        }

    override suspend fun readSection(ticket: String): BookCacheSectionDraft? =
        withContext(IO) {
            gate(ticket).withLock {
                payload(ticket).openRead().use {
                    GSON.fromJsonObject<BookCachePayload>(it.readBytes().toString(Charsets.UTF_8))
                        .getOrThrow()
                        .section
                }
            }
        }

    override suspend fun writeSection(ticket: String, draft: BookCacheSectionDraft) =
        withContext(IO) {
            gate(ticket).withLock {
                val file = payload(ticket)
                val current =
                    file.openRead().use {
                        GSON.fromJsonObject<BookCachePayload>(
                                it.readBytes().toString(Charsets.UTF_8)
                            )
                            .getOrThrow()
                    }
                if ((current.section?.revision ?: -1) >= draft.revision) return@withLock
                beforeSectionWrite(draft.revision)
                val stream = file.startWrite()
                try {
                    stream.write(
                        GSON.toJson(current.copy(section = draft)).toByteArray(Charsets.UTF_8)
                    )
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }
        }

    override suspend fun folderResult(ticket: String): BookCacheFolderResult? =
        withContext(IO) {
            gate(ticket).withLock {
                val file = payload(ticket)
                if (!file.baseFile.exists()) null
                else
                    file.openRead().use {
                        GSON.fromJsonObject<BookCachePayload>(
                                it.readBytes().toString(Charsets.UTF_8)
                            )
                            .getOrThrow()
                            .folderResult
                    }
            }
        }

    override suspend fun folderResult(ticket: String, result: BookCacheFolderResult): Boolean =
        withContext(IO) {
            gate(ticket).withLock {
                val file = payload(ticket)
                if (!file.baseFile.exists()) return@withLock false
                val current =
                    file.openRead().use {
                        GSON.fromJsonObject<BookCachePayload>(
                                it.readBytes().toString(Charsets.UTF_8)
                            )
                            .getOrThrow()
                    }
                if (current.folderResult != null) return@withLock false
                val stream = file.startWrite()
                try {
                    stream.write(
                        GSON.toJson(current.copy(folderResult = result)).toByteArray(Charsets.UTF_8)
                    )
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
                true
            }
        }

    override suspend fun release(ticket: String) =
        withContext(IO) { gate(ticket).withLock { payload(ticket).delete() } }

    override fun books(group: Long) =
        database.bookDao
            .flowByGroup(group)
            .map { books ->
                val eligible = books.filterNot { it.isAudio }
                val ordered =
                    when (sort(group)) {
                        1 -> eligible.sortedByDescending { it.latestChapterTime }
                        2 -> eligible.sortedWith { a, b -> a.name.cnCompare(b.name) }
                        3 -> eligible.sortedBy { it.order }
                        4 ->
                            eligible.sortedByDescending {
                                max(it.latestChapterTime, it.durChapterTime)
                            }
                        else -> eligible.sortedByDescending { it.durChapterTime }
                    }
                ordered.map {
                    BookCacheItem(
                        it.bookUrl,
                        it.name,
                        it.getRealAuthor(),
                        it.isLocal,
                        it.totalChapterNum,
                        it.durChapterIndex,
                        it.lastChapterIndex,
                    )
                }
            }
            .flowOn(IO)

    override fun groups() =
        database.bookGroupDao
            .flowAll()
            .map { groups ->
                groups.map { BookCacheGroup(it.groupId, it.groupName) }
            }
            .flowOn(IO)

    override suspend fun scan(key: String): BookCacheScan? =
        withContext(IO) {
            val book = database.bookDao.getBook(key) ?: return@withContext null
            if (book.isLocal) return@withContext BookCacheScan(emptySet(), book.totalChapterNum)
            val names = files(book)
            if (names.isEmpty()) return@withContext BookCacheScan(emptySet(), book.totalChapterNum)
            val chapters = database.bookChapterDao.getChapterList(key)
            BookCacheScan(
                chapters.filter { it.isVolume || it.getFileName() in names }.map { it.url }.toSet(),
                chapters.size,
            )
        }

    override suspend fun runtime() =
        withContext(IO) {
            BookCacheRuntime(
                CacheBook.isRun,
                CacheBook.cacheBookMap.entries.filter { !it.value.isStop() }.map { it.key }.toSet(),
                ExportBookService.exportProgress.toMap(),
                ExportBookService.exportMsg.toMap(),
            )
        }

    override suspend fun preferences() =
        withContext(IO) {
            BookCachePreferences(
                AppConfig.exportUseReplace,
                AppConfig.enableCustomExport,
                AppConfig.exportNoChapterName,
                AppConfig.exportToWebDav,
                AppConfig.exportPictureFile,
                AppConfig.parallelExportBook,
                AppConfig.exportType,
                AppConfig.exportCharset,
                AppConfig.bookExportFileName,
                AppConfig.episodeExportFileName,
            )
        }

    override suspend fun preferences(
        value: BookCachePreferences,
        fields: Set<BookCachePreference>,
    ) =
        withContext(IO) {
            commands.withLock {
                if (BookCachePreference.Replace in fields)
                    AppConfig.exportUseReplace = value.replace
                if (BookCachePreference.Custom in fields)
                    AppConfig.enableCustomExport = value.custom
                if (BookCachePreference.NoChapterName in fields)
                    AppConfig.exportNoChapterName = value.noChapterName
                if (BookCachePreference.WebDav in fields) AppConfig.exportToWebDav = value.webDav
                if (BookCachePreference.Pictures in fields)
                    AppConfig.exportPictureFile = value.pictures
                if (BookCachePreference.Parallel in fields)
                    AppConfig.parallelExportBook = value.parallel
                if (BookCachePreference.Type in fields) AppConfig.exportType = value.type
                if (BookCachePreference.Charset in fields) AppConfig.exportCharset = value.charset
                if (BookCachePreference.FileName in fields)
                    AppConfig.bookExportFileName = value.fileName
                if (BookCachePreference.EpisodeFileName in fields)
                    AppConfig.episodeExportFileName = value.episodeFileName
            }
        }

    override suspend fun cachedPath(): String? =
        withContext(IO) { ACache.get().getAsString("exportBookPath") }

    override suspend fun rememberPath(path: String) =
        withContext(IO) { commands.withLock { ACache.get().put("exportBookPath", path) } }

    override suspend fun writable(path: String) =
        withContext(IO) { FileDoc.fromDir(path).checkWrite() }

    override suspend fun download(keys: List<String>, afterCurrent: Boolean) =
        withContext(IO) {
            commands.withLock {
                keys.distinct().forEach { key ->
                    database.bookDao
                        .getBook(key)
                        ?.takeUnless { it.isAudio || it.isLocal }
                        ?.let { book ->
                            CacheBook.start(
                                context,
                                book,
                                if (afterCurrent) book.durChapterIndex else 0,
                                book.lastChapterIndex,
                            )
                        }
                }
            }
        }

    override suspend fun stopDownloads() =
        withContext(IO) { commands.withLock { CacheBook.stop(context) } }

    override suspend fun toggleDownload(key: String) =
        withContext(IO) {
            commands.withLock {
                val book = database.bookDao.getBook(key) ?: return@withLock
                if (book.isLocal || book.isAudio) return@withLock
                if (CacheBook.cacheBookMap[key]?.isStop() == false) CacheBook.remove(context, key)
                else CacheBook.start(context, book, 0, book.lastChapterIndex)
            }
        }

    override suspend fun export(request: BookCacheExport) =
        withContext(IO) {
            commands.withLock {
                request.keys.distinct().forEach { key ->
                    if (database.bookDao.getBook(key) != null)
                        context.startService<ExportBookService> {
                            action = IntentAction.start
                            putExtra("bookUrl", key)
                            putExtra("exportType", request.type)
                            putExtra("exportPath", request.path)
                            request.size?.let { putExtra("epubSize", it) }
                            request.scope?.let { putExtra("epubScope", it) }
                        }
                }
            }
        }

    private companion object {
        val ticketGates = Array(64) { Mutex() }
    }

    override suspend fun validEpisodeName(script: String) =
        withContext(IO) {
            script.isNotEmpty() && tryParesExportFileName(script)
        }

    override suspend fun episodeName(key: String, script: String): String? =
        withContext(IO) {
            if (script.isEmpty() || !tryParesExportFileName(script)) return@withContext null
            database.bookDao.getBook(key)?.getExportFileName("epub", 1, script)
        }
}
