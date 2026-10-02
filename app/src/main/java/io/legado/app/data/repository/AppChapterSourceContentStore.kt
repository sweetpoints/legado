package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal class AppChapterSourceContentStore(context: Context, private val database: AppDatabase = appDb,
    private val search: AppChapterSourceSearchStore = AppChapterSourceSearchStore(database)) : ChapterSourceContentStore {
    private val directory = File(context.applicationContext.filesDir, "chapter-source-sessions")
    override suspend fun original(bookJson: String): List<ChapterSourceChapter> {
        val book = GSON.fromJson(bookJson, Book::class.java)
        return database.bookChapterDao.getChapterList(book.bookUrl).map(::chapter)
    }
    override suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String): ChapterSourceToc = withContext(SuppressSourceNavigation) {
        val book = search.targetBook(row)
        val source = database.bookSourceDao.getBookSource(book.origin) ?: throw NoStackTraceException("书源不存在")
        val chapters = search.cachedToc(book) ?: run {
            if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, book)
            WebBook.getChapterListAwait(source, book).getOrThrow().also { search.rememberToc(book, it) }
        }
        ChapterSourceToc(hash(row.id), GSON.toJson(book), GSON.toJson(source), chapters.map(::chapter),
            BookHelp.getDurChapter(index, title, chapters, searchAllChapterNumbers = true))
    }
    override suspend fun content(toc: ChapterSourceToc, position: Int): String = withContext(SuppressSourceNavigation) {
        val book = GSON.fromJson(toc.bookJson, Book::class.java)
        val source = database.bookSourceDao.getBookSource(book.origin) ?: throw NoStackTraceException("书源不存在")
        val selected = GSON.fromJson(toc.chapters[position].json, BookChapter::class.java)
        val next = toc.chapters.getOrNull(position + 1)?.let { GSON.fromJson(it.json, BookChapter::class.java).url }
        WebBook.getContentAwait(source, book, selected, next, false)
    }
    override suspend fun saveText(bookJson: String, chapter: ChapterSourceChapter, body: String, expectedPreviousHash: String) =
        BookHelp.saveTextIfUnchanged(GSON.fromJson(bookJson, Book::class.java), GSON.fromJson(chapter.json, BookChapter::class.java),
            body, expectedPreviousHash, saveChapterMetadata = true)
    override suspend fun cachedText(bookJson: String, chapter: ChapterSourceChapter) =
        BookHelp.getContent(GSON.fromJson(bookJson, Book::class.java), GSON.fromJson(chapter.json, BookChapter::class.java))
    override suspend fun read(session: String): ChapterSourceSession? = locked(session) {
        readJson(file(session, "state.json"), ChapterSourceSession::class.java)
    }
    override suspend fun write(session: String, snapshot: ChapterSourceSession) = locked(session) {
        val target = file(session, "state.json"); val current = readJson(target, ChapterSourceSession::class.java)
        if (current == null || snapshot.revision >= current.revision) writeJson(target, snapshot)
    }
    override suspend fun receipt(session: String, key: String) = locked(session) { readJson(file(session, "$key.json"), ChapterSourceReceipt::class.java) }
    override suspend fun writeReceipt(session: String, receipt: ChapterSourceReceipt) = locked(session) {
        val target = file(session, "${receipt.key}.json"); val current = readJson(target, ChapterSourceReceipt::class.java)
        // A stale cache completion can never undo an already consumed receipt or committed journal.
        if (current?.consumed != true && !(current?.committed == true && !receipt.committed)) writeJson(target, receipt)
    }
    override suspend fun receipts(session: String): List<ChapterSourceReceipt> = locked(session) {
        file(session, "state.json").parentFile!!.listFiles().orEmpty().map { if (it.name.endsWith(".json.bak")) File(it.path.removeSuffix(".bak")) else it }
            .distinctBy { it.path }.filter { it.extension == "json" && it.name != "state.json" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .mapNotNull { readJson(it, ChapterSourceReceipt::class.java) }
    }
    override suspend fun deleteSource(row: ChapterSourceSearchRow) {
        SourceHelp.deleteBookSource(row.origin)
        database.searchBookDao.delete(GSON.fromJson(row.json, SearchBook::class.java))
    }
    override suspend fun disableSource(row: ChapterSourceSearchRow) {
        database.bookSourceDao.getBookSource(row.origin)?.let { database.bookSourceDao.update(it.copy(enabled = false)) }
    }
    override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) {
        database.bookSourceDao.getBookSource(row.origin)?.let { source ->
            val order = if (top) database.bookSourceDao.minOrder - 1 else database.bookSourceDao.maxOrder + 1
            database.bookSourceDao.update(source.copy(customOrder = order))
            database.searchBookDao.update(GSON.fromJson(row.json, SearchBook::class.java).copy(originOrder = order))
        }
    }
    override suspend fun score(row: ChapterSourceSearchRow, score: Int) { SourceConfig.setBookScore(row.origin, row.name, row.author, score) }
    override suspend fun groups() = database.bookSourceDao.flowEnabledGroups().first().toList()
    private suspend fun <T> locked(session: String, block: () -> T): T {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return locks.getOrPut(File(directory, session).absolutePath) { Mutex() }.withLock { block() }
    }
    private fun file(session: String, name: String): File {
        require(name == "state.json" || name.matches(Regex("[A-Za-z0-9-]+\\.json")))
        return File(File(directory, session).apply { mkdirs() }, name)
    }
    private fun <T> readJson(file: File, type: Class<T>): T? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return GSON.fromJson(AtomicFile(file).readFully().toString(Charsets.UTF_8), type) ?: error("状态数据损坏")
    }
    private fun writeJson(file: File, value: Any) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    private fun chapter(chapter: BookChapter) = ChapterSourceChapter("${chapter.index}-${hash(chapter.url)}", chapter.index, chapter.title, chapter.isVolume, chapter.tag, GSON.toJson(chapter))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
