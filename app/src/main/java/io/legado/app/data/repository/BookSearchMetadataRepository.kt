package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

interface BookSearchMetadataStore {
    fun history(query: String): Flow<List<SearchKeyword>>
    fun suggestions(query: String): Flow<List<Book>>
    fun books(): Flow<List<Book>>
    fun records(): Flow<List<ReadRecordBook>>
    fun groups(): Flow<List<String>>
    fun hasNamedBook(name: String): Boolean
    fun saveHistory(word: String, timestamp: Long)
    fun deleteHistory(word: String)
    fun clearHistory()
}
interface BookSearchMetadataRepository {
    fun history(query: String): Flow<List<BookSearchHistory>>
    fun suggestions(query: String): Flow<List<BookSearchSuggestion>>
    fun membership(): Flow<BookSearchMembership>
    fun groups(): Flow<List<String>>
    suspend fun hasNamedBook(name: String): Boolean
    suspend fun saveHistory(word: String)
    suspend fun deleteHistory(word: String)
    suspend fun clearHistory()
}
class DefaultBookSearchMetadataRepository(private val store: BookSearchMetadataStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis) : BookSearchMetadataRepository {
    override fun history(query: String): Flow<List<BookSearchHistory>> = flow { emitAll(store.history(query)) }
        .map { rows -> rows.map { BookSearchHistory(it.word, it.usage, it.lastUseTime) } }.flowOn(io)
    override fun suggestions(query: String): Flow<List<BookSearchSuggestion>> = if (query.isBlank()) flowOf(emptyList())
        else flow { emitAll(store.suggestions(query)) }.map { rows -> rows.map { BookSearchSuggestion(it.bookUrl, it.name, it.author) } }.flowOn(io)
    override fun membership(): Flow<BookSearchMembership> = combine(
        flow { emitAll(store.books()) }, flow { emitAll(store.records()) }) { books, records ->
        val keys = buildSet { books.filterNot { it.isNotShelf }.forEach { add("${it.name}-${it.author}"); add(it.name); add(it.bookUrl) } }
        BookSearchMembership(keys, ReadRecordIndex.of(records))
    }.flowOn(io)
    override fun groups(): Flow<List<String>> = flow { emitAll(store.groups()) }.map { it.toList() }.flowOn(io)
    override suspend fun hasNamedBook(name: String): Boolean = withContext(io) { store.hasNamedBook(name) }
    override suspend fun saveHistory(word: String) = withContext(io + NonCancellable) { store.saveHistory(word, clock()) }
    override suspend fun deleteHistory(word: String) = withContext(io + NonCancellable) { store.deleteHistory(word) }
    override suspend fun clearHistory() = withContext(io + NonCancellable) { store.clearHistory() }
}
class AppBookSearchMetadataStore(private val database: AppDatabase = appDb) : BookSearchMetadataStore {
    override fun history(query: String) = if (query.isBlank()) database.searchKeywordDao.flowByTime() else database.searchKeywordDao.flowSearch(query)
    override fun suggestions(query: String) = database.bookDao.flowSearch(query)
    override fun books() = database.bookDao.flowAll()
    override fun records() = database.readRecordDao.flowBooks()
    override fun groups() = database.bookSourceDao.flowEnabledGroups()
    override fun hasNamedBook(name: String) = database.bookDao.findByName(name).isNotEmpty()
    override fun saveHistory(word: String, timestamp: Long) = database.runInTransaction {
        val previous = database.searchKeywordDao.get(word)
        if (previous == null) database.searchKeywordDao.insert(SearchKeyword(word, 1, timestamp))
        else database.searchKeywordDao.update(previous.copy(usage = previous.usage + 1, lastUseTime = timestamp))
    }
    override fun deleteHistory(word: String) { database.searchKeywordDao.get(word)?.let { database.searchKeywordDao.delete(it) } }
    override fun clearHistory() = database.searchKeywordDao.deleteAll()
}
