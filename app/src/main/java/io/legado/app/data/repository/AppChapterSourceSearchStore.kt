package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.book.ChangeSourceResultOptions
import io.legado.app.model.webBook.WebBook
import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import io.legado.app.utils.internString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class AppChapterSourceSearchStore(private val database: AppDatabase = appDb) : ChapterSourceSearchStore {
    private val books = ConcurrentHashMap<String, Book>()
    private val tocs = ConcurrentHashMap<String, List<BookChapter>>()
    private val chapterCount = AtomicInteger()
    private val sourceNames = ConcurrentHashMap<String, String>()
    override suspend fun cached(request: ChapterSourceSearchRequest): List<ChapterSourceSearchRow> {
        val author = if (request.checkAuthor) request.author else ""
        val rows = if (request.query.isEmpty()) database.searchBookDao.changeSourceByGroup(request.name, author, request.group)
            else database.searchBookDao.changeSourceSearch(request.name, author, request.query, request.group)
        return rows.map(::row)
    }
    override suspend fun sources(request: ChapterSourceSearchRequest): ChapterSourceSearchSources {
        val requested = if (request.group.isBlank()) database.bookSourceDao.allEnabledPart
            else database.bookSourceDao.getEnabledPartByGroup(request.group)
        val selected = if (request.group.isNotBlank() && requested.isEmpty()) database.bookSourceDao.allEnabledPart else requested
        sourceNames.clear(); selected.forEach { sourceNames[it.bookSourceUrl] = it.bookSourceName }
        return ChapterSourceSearchSources(selected.map { it.bookSourceUrl }, if (selected !== requested) "" else request.group)
    }
    override suspend fun reset(previous: List<ChapterSourceSearchRow>) {
        if (previous.isNotEmpty()) database.searchBookDao.delete(*previous.map { GSON.fromJson(it.json, SearchBook::class.java) }.toTypedArray())
        books.clear(); tocs.clear(); chapterCount.set(0)
    }
    override suspend fun search(request: ChapterSourceSearchRequest, source: String) = searchResults(request, source).toList()
    override fun searchResults(request: ChapterSourceSearchRequest, source: String) = flow {
        val configuration = database.bookSourceDao.getBookSource(source) ?: return@flow
        val results = withContext(SuppressSourceNavigation) {
            WebBook.searchBookAwait(configuration, request.name, filter = { name, author, _ ->
                name == request.name && (!request.checkAuthor || author.contains(request.author))
            })
        }
        results.forEach { result ->
            val loaded = withContext(SuppressSourceNavigation) {
                if (request.loadInfo || request.loadToc || request.loadWordCount) load(request, configuration, result.toBook()) else row(result)
            }
            // Sequential emit waits for persistence/publication before the next result can fail.
            emit(loaded)
        }
    }
    override suspend fun measure(request: ChapterSourceSearchRequest, row: ChapterSourceSearchRow): ChapterSourceSearchRow = withContext(SuppressSourceNavigation) {
        val source = database.bookSourceDao.getBookSource(row.origin) ?: error("书源不存在")
        load(request, source, GSON.fromJson(row.json, SearchBook::class.java).toBook())
    }
    private suspend fun load(request: ChapterSourceSearchRequest, source: BookSource, book: Book): ChapterSourceSearchRow {
        if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, book)
        if (!request.loadToc && !request.loadWordCount) return row(book.toSearchBook())
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        chapters.forEach { it.internString() }
        if (chapterCount.get() < 30000) { chapterCount.addAndGet(chapters.size); tocs[book.primaryStr()] = chapters }
        books[book.primaryStr()] = book; book.releaseHtmlData()
        if (!request.loadWordCount) return row(book.toSearchBook())
        val old = request.originalBookJson?.let { GSON.fromJson(it, Book::class.java) }
        val index = if (request.fromReader && old != null) BookHelp.getDurChapter(old, chapters) else chapters.lastIndex
        val chapter = chapters[index]
        val title = chapter.title.trim().let { if (it.length > 20) it.substring(0, 20) + "…" else it }
        val started = System.currentTimeMillis()
        val count = try {
            val raw = WebBook.getContentAwait(source, book, chapter, chapters.getOrNull(index + 1)?.url, false)
            val processed = if (old != null) ContentProcessor.get(old).getContent(old, chapter, raw, false).toString() else raw
            processed.length to "[${index + 1}] $title\n字数：${processed.length}"
        } catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { -1 to "[${index + 1}] $title\n获取字数失败：${error.localizedMessage}" }
        return row(book.toSearchBook().apply {
            chapterWordCount = count.first; chapterWordCountText = count.second
            respondTime = (System.currentTimeMillis() - started).toInt()
        })
    }
    override suspend fun persist(row: ChapterSourceSearchRow) { database.searchBookDao.insert(GSON.fromJson(row.json, SearchBook::class.java)) }
    override suspend fun reference(request: ChapterSourceSearchRequest): Int? {
        if (request.filterMode != ChangeSourceResultOptions.FILTER_RELATIVE) return null
        val book = request.originalBookJson?.let { GSON.fromJson(it, Book::class.java) } ?: return null
        val index = if (request.fromReader) book.durChapterIndex else book.totalChapterNum - 1
        if (index < 0) return null
        val chapter = database.bookChapterDao.getChapter(book.bookUrl, index) ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        return runCatching { ContentProcessor.get(book).getContent(book, chapter, content, false).toString().length }.getOrNull()?.takeIf { it > 0 }
    }
    override suspend fun sourceName(origin: String) = sourceNames[origin] ?: origin
    suspend fun targetBook(row: ChapterSourceSearchRow): Book = books[row.origin + row.id]?.copy()
        ?: GSON.fromJson(row.json, SearchBook::class.java).toBook()
    suspend fun cachedToc(book: Book): List<BookChapter>? = tocs[book.primaryStr()]?.map { it.copy() }
    fun rememberToc(book: Book, toc: List<BookChapter>) { tocs[book.primaryStr()] = toc.map { it.copy() }; books[book.primaryStr()] = book.copy() }
    override fun sourceScore(origin: String) = SourceConfig.getSourceScore(origin)
    override fun threadCount() = AppConfig.threadCount
    override fun log(error: Throwable) { AppLog.put("换源搜索出错\n${error.localizedMessage}", error) }
    private fun row(book: SearchBook): ChapterSourceSearchRow {
        book.releaseHtmlData()
        return ChapterSourceSearchRow(book.bookUrl, book.origin, book.originName, book.name, book.author,
        book.getDisplayLastChapterTitle(), book.chapterWordCountText, book.chapterWordCount, book.respondTime, book.originOrder,
        SourceConfig.getBookScore(book.origin, book.name, book.author), book.type, GSON.toJson(book))
    }
}
