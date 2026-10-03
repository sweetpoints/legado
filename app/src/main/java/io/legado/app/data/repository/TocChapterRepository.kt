package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.book.toc.*
import io.legado.app.model.localBook.*
import io.legado.app.model.AudioCacheKey
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.book.*
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.AppLog
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class TocChapterParameters(val book: Book, val search: String? = null, val countWords: Boolean = false)
data class TocChapterCheckpoint(val parameters: TocChapterParameters, val revision: Long, val collapsed: Set<Int> = emptySet(), val pdfCollapsed: Set<Int> = emptySet())
data class TocChapterSnapshot(val chapters: List<BookChapter>, val epub: List<EpubTocNode>?, val pdf: List<PdfOutlineNode>)
data class TocChapterCache(val files: Set<String> = emptySet(), val audio: Set<AudioCacheKey> = emptySet(), val tree: String? = null)
data class TocChapterNavigation(val index: Int, val changed: Boolean, val pdfPage: Int? = null, val volumeIndex: Int? = null, val chapterInVolume: Int? = null)
interface TocChapterRepository {
    suspend fun checkpoint(session: String): TocChapterCheckpoint?
    suspend fun checkpoint(session: String, value: TocChapterCheckpoint)
    suspend fun release(session: String)
    suspend fun load(parameters: TocChapterParameters): TocChapterSnapshot
    suspend fun search(parameters: TocChapterParameters, query: String): List<Int>
    fun titles(book: Book, items: List<TocListItem>): Flow<Pair<String, String>>
    suspend fun title(book: Book, item: TocListItem): String
    suspend fun cache(book: Book): TocChapterCache
    suspend fun resolve(book: Book, chapterUrl: String): TocChapterNavigation?
}
class AppTocChapterRepository(private val database: AppDatabase = appDb,
    private val directory: File = File(appCtx.filesDir, "toc-chapter-state")) : TocChapterRepository {
    private fun file(session: String): AtomicFile {
        require(UUID.fromString(session).toString() == session); return AtomicFile(File(directory, "$session.json"))
    }
    private fun released(session: String) = File(directory, "$session.released").exists() || File(directory, "$session.released.bak").exists()
    private fun read(session: String): TocChapterCheckpoint? {
        if (released(session)) return null
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<TocChapterCheckpoint>(it.readText()).getOrThrow() }
    }
    private fun lock(session: String): Mutex {
        require(UUID.fromString(session).toString() == session)
        val index = (File(directory, session).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size
        return gates[index]
    }
    override suspend fun checkpoint(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { read(session) } }
    override suspend fun checkpoint(session: String, value: TocChapterCheckpoint): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if (released(session)) return@withLock
            if ((read(session)?.revision ?: -1) > value.revision) return@withLock
            check(directory.isDirectory || directory.mkdirs()); val file = file(session); val output = file.startWrite()
            try { output.write(GSON.toJson(value).toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
    override suspend fun release(session: String): Unit = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            check(directory.isDirectory || directory.mkdirs())
            val fence = AtomicFile(File(directory, "$session.released")); val output = fence.startWrite()
            try { output.write(1); fence.finishWrite(output) }
            catch (error: Throwable) { fence.failWrite(output); throw error }
            file(session).delete()
        }
    }
    private companion object { val gates = Array(64) { Mutex() } }
    override suspend fun load(parameters: TocChapterParameters): TocChapterSnapshot = withContext(Dispatchers.IO) {
        val book = parameters.book
        val epub = if (book.isEpub) runCatching { EpubFile.getToc(book) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.put("读取 EPUB 目录失败", it) }.getOrNull() else null
        val pdf = if (book.isPdf) runCatching { PdfOutline.read(book) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.put("读取 PDF 目录失败", it) }.getOrDefault(emptyList()) else emptyList()
        val chapters = database.bookChapterDao.getChapterList(book.bookUrl, 0, book.simulatedTotalChapterNum() - 1)
            .let { if (book.isPdf && book.getReverseToc()) it.asReversed() else it }.map { it.copy() }
        TocChapterSnapshot(chapters, epub, pdf)
    }
    override suspend fun search(parameters: TocChapterParameters, query: String): List<Int> = withContext(Dispatchers.IO) {
        database.bookChapterDao.searchIndexes(parameters.book.bookUrl, query, 0, parameters.book.simulatedTotalChapterNum() - 1)
    }
    override fun titles(book: Book, items: List<TocListItem>): Flow<Pair<String, String>> = kotlinx.coroutines.flow.flow {
        val rules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
        val replaceBook = book.toReplaceBook(); val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
        for (item in items) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val chapter = item.readingChapter?.let { reading -> if (reading.index == item.chapter.index) item.chapter else item.chapter.copy(index = reading.index) } ?: item.chapter
            emit(item.key to chapter.getDisplayTitle(rules, useReplace, replaceBook = replaceBook))
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun title(book: Book, item: TocListItem): String = titles(book, listOf(item)).first().second
    override suspend fun cache(book: Book): TocChapterCache = withContext(Dispatchers.IO) {
        if (book.isPdf) return@withContext TocChapterCache()
        if (!book.isAudio) return@withContext TocChapterCache(files = BookHelp.getChapterFiles(book).toSet())
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val tree = AppConfig.audioCacheTreeUri
            val keys = runCatching { AudioCacheManager.listCachedChapterKeys(tree, book.bookUrl) }.getOrDefault(emptySet())
            if (tree == AppConfig.audioCacheTreeUri) return@withContext TocChapterCache(audio = keys.toSet(), tree = tree)
        }
        @Suppress("UNREACHABLE_CODE") TocChapterCache()
    }
    override suspend fun resolve(book: Book, chapterUrl: String): TocChapterNavigation? = withContext(Dispatchers.IO) {
        val chapters = database.bookChapterDao.getChapterList(book.bookUrl)
        val chapter = chapters.find { it.url == chapterUrl } ?: return@withContext null
        if (!book.isVideo) return@withContext TocChapterNavigation(chapter.index, chapter.index != book.durChapterIndex)
        val volumes = chapters.filter { it.isVolume }; var volumeIndex = 0; var inVolume = 0
        if (volumes.isEmpty()) inVolume = chapter.index else for ((index, volume) in volumes.reversed().withIndex()) {
            if (volume.index <= chapter.index) { volumeIndex = volumes.size - index - 1; inVolume = (chapter.index - volume.index - 1).coerceAtLeast(0); break }
        }
        TocChapterNavigation(chapter.index, chapter.index != book.durChapterIndex, volumeIndex = volumeIndex, chapterInVolume = inVolume)
    }
}
