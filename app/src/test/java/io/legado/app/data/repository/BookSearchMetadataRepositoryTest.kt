package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*

class BookSearchMetadataRepositoryTest {
    private class Store : BookSearchMetadataStore {
        val callers = mutableListOf<Thread>(); val queries = mutableListOf<String>(); val keywords = mutableListOf(SearchKeyword("old", 4, 1))
        val shelf = mutableListOf(Book(bookUrl = "url", name = "Title", author = "Author"), Book(bookUrl = "excluded", name = "Excluded", type = BookType.notShelf))
        val read = mutableListOf(ReadRecordBook("Read", "Author")); val enabledGroups = mutableListOf("group")
        fun touch() { synchronized(callers) { callers += Thread.currentThread() } }
        override fun history(query: String): Flow<List<SearchKeyword>> { touch(); queries += query; return flowOf(keywords) }
        override fun suggestions(query: String): Flow<List<Book>> { touch(); queries += query; return flowOf(shelf) }
        override fun books(): Flow<List<Book>> { touch(); return flowOf(shelf) }
        override fun records(): Flow<List<ReadRecordBook>> { touch(); return flowOf(read) }
        override fun groups(): Flow<List<String>> { touch(); return flowOf(enabledGroups) }
        override fun hasNamedBook(name: String): Boolean { touch(); return shelf.any { it.name == name } }
        override fun saveHistory(word: String, timestamp: Long) { touch(); val previous = keywords.find { it.word == word }; keywords.removeAll { it.word == word }; keywords += SearchKeyword(word, (previous?.usage ?: 0) + 1, timestamp) }
        override fun deleteHistory(word: String) { touch(); keywords.removeAll { it.word == word } }
        override fun clearHistory() { touch(); keywords.clear() }
    }
    @Test fun allStoreEntryPointsRunOnIoAndSnapshotsDetachMutableRoomEntities() = runBlocking {
        val caller = Thread.currentThread(); val store = Store(); val repository = DefaultBookSearchMetadataRepository(store, clock = { 9 })
        val history = repository.history(" raw ").first(); val suggestions = repository.suggestions(" raw ").first(); val membership = repository.membership().first(); val groups = repository.groups().first()
        assertTrue(repository.hasNamedBook("Title")); repository.saveHistory("old"); repository.deleteHistory("none")
        assertTrue(store.callers.isNotEmpty()); assertTrue(store.callers.all { it !== caller }); assertEquals(listOf(" raw ", " raw "), store.queries)
        store.keywords[0].word = "changed"; store.shelf[0].name = "changed"; store.read[0].bookName = "changed"; store.enabledGroups.clear()
        assertEquals("old", history.single().word); assertEquals("Title", suggestions.first().name); assertEquals(listOf("group"), groups)
        assertTrue(membership.onShelf("url", "none", "none")); assertTrue(membership.onShelf("different", "Title", "Author")); assertFalse(membership.onShelf("excluded", "Excluded", "")); assertTrue(membership.hasRead("Read", "Author"))
    }
    @Test fun blankSuggestionsDoNotQueryStoreAndHistoryPreservesRawQuery() = runBlocking {
        val store = Store(); val repository = DefaultBookSearchMetadataRepository(store)
        assertTrue(repository.suggestions("  ").first().isEmpty()); assertTrue(store.queries.isEmpty())
        repository.history("  ").first(); assertEquals(listOf("  "), store.queries)
    }
    @Test fun acceptedHistoryWritesIncrementUsageUseInjectedTimeAndDeleteOnlyExactWord() = runBlocking {
        val store = Store(); val repository = DefaultBookSearchMetadataRepository(store, clock = { 123 })
        repository.saveHistory("old"); repository.saveHistory("new"); assertEquals(5, store.keywords.first { it.word == "old" }.usage); assertEquals(123L, store.keywords.first().lastUseTime)
        repository.deleteHistory("o"); assertEquals(2, store.keywords.size); repository.deleteHistory("old"); assertEquals(listOf("new"), repository.history("").first().map { it.word }); repository.clearHistory(); assertTrue(repository.history("").first().isEmpty())
    }
}
