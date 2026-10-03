package io.legado.app.data.repository

import io.legado.app.data.preferences.ContentSearchOptions
import io.legado.app.data.preferences.ContentSearchOptionsRepository
import io.legado.app.data.preferences.ProcessContentSearchOptionsRepository
import io.legado.app.model.book.ContentSearchMatch
import io.legado.app.model.book.findContentSearchMatches
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer

internal data class ContentSearchBook(val url: String, val title: String, val currentChapter: Int, val local: Boolean, val json: String)
internal data class ContentSearchChapter(val index: Int, val fileName: String, val json: String)
internal data class ContentSearchChapterText(val index: Int, val title: String, val content: String)
internal data class ContentSearchLoaded(val book: ContentSearchBook, val cacheNames: Set<String>)
internal data class ContentSearchUpdate(val results: List<ContentSearchMatch>, val running: Boolean, val searchedChapters: Int, val totalChapters: Int)
internal data class ContentSearchSession(val bookUrl: String, val query: String = "", val results: List<ContentSearchMatch> = emptyList(),
    val options: ContentSearchOptions = ContentSearchOptions(), val position: Int = 0, val searchOpen: Boolean = true,
    val initialSubmit: Boolean = false, val pendingResult: String? = null, val finished: Boolean = false, val revision: Long = 0)
internal class ContentSearchSessionClosedException : IllegalStateException("全文搜索会话已关闭")
internal interface ContentSearchStore {
    suspend fun book(url: String): ContentSearchBook?
    suspend fun cacheNames(book: ContentSearchBook): Set<String>
    suspend fun chapters(book: ContentSearchBook): List<ContentSearchChapter>
    suspend fun process(book: ContentSearchBook, chapter: ContentSearchChapter, replace: Boolean): ContentSearchChapterText?
    suspend fun read(session: String): ContentSearchSession?
    suspend fun create(session: String, snapshot: ContentSearchSession): ContentSearchSession
    suspend fun write(session: String, snapshot: ContentSearchSession)
    suspend fun release(session: String)
}
internal interface ContentSearchRepository {
    suspend fun load(url: String): ContentSearchLoaded
    fun search(book: ContentSearchBook, query: String, cacheNames: () -> Set<String>): Flow<ContentSearchUpdate>
    suspend fun read(session: String): ContentSearchSession?
    suspend fun create(session: String, snapshot: ContentSearchSession): ContentSearchSession
    suspend fun write(session: String, snapshot: ContentSearchSession)
    suspend fun release(session: String)
}
internal class DefaultContentSearchRepository(private val store: ContentSearchStore,
    private val options: ContentSearchOptionsRepository = ProcessContentSearchOptionsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO) : ContentSearchRepository {
    override suspend fun load(url: String) = withContext(io) {
        val book = requireNotNull(store.book(url)) { "书籍不存在" }
        ContentSearchLoaded(book, store.cacheNames(book).toSet())
    }
    override fun search(book: ContentSearchBook, query: String, cacheNames: () -> Set<String>): Flow<ContentSearchUpdate> = flow {
        require(query.isNotBlank())
        val chapters = store.chapters(book); currentCoroutineContext().ensureActive()
        val results = mutableListOf<ContentSearchMatch>()
        emit(ContentSearchUpdate(emptyList(), true, 0, chapters.size))
        chapters.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            if (book.local || chapter.fileName in cacheNames()) {
                val settings = options.current()
                val text = store.process(book, chapter, settings.replace); currentCoroutineContext().ensureActive()
                text?.let { results += findContentSearchMatches(it.content, query, it.index, it.title, settings.regex) }
            }
            currentCoroutineContext().ensureActive()
            emit(ContentSearchUpdate(results.toList(), true, index + 1, chapters.size))
        }
        emit(ContentSearchUpdate(results.toList(), false, chapters.size, chapters.size))
    }.flowOn(io).buffer(0)
    override suspend fun read(session: String) = withContext(io) { store.read(session) }
    override suspend fun create(session: String, snapshot: ContentSearchSession) = withContext(io + NonCancellable) { store.create(session, snapshot) }
    override suspend fun write(session: String, snapshot: ContentSearchSession) = withContext(io + NonCancellable) { store.write(session, snapshot) }
    override suspend fun release(session: String) = withContext(io + NonCancellable) { store.release(session) }
}
