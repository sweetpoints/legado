package io.legado.app.data.repository

import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class SearchScopeRepositoryTest {
    @Test fun immutableRowsKeepNamesOrderAndDisabledSourcesWithoutSharingRoomEntities() = runBlocking {
        val row = BookSourcePart(bookSourceUrl = "disabled", bookSourceName = "A:B", enabled = false)
        val store = Fake().apply { rows.value = listOf(row, BookSourcePart(bookSourceUrl = "second", bookSourceName = "Second")) }
        val loaded = DefaultSearchScopeRepository(store).sources("").first()
        row.bookSourceName = "Changed"
        assertEquals(listOf("disabled", "second"), loaded.map { it.url })
        assertEquals("A:B", loaded.first().name)
    }
    @Test fun queryIsForwardedUnmodifiedAndDatabaseUpdatesRemainLive() = runBlocking {
        val store = Fake(); val repository = DefaultSearchScopeRepository(store)
        val flow = repository.sources(" Group % ")
        assertEquals(" Group % ", store.query)
        val collected = mutableListOf<List<SearchScopeSource>>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { flow.take(2).toList(collected) }
        yield(); withTimeout(5000) { while (collected.isEmpty()) yield() }
        store.rows.value = listOf(BookSourcePart(bookSourceUrl = "new", bookSourceName = "New"))
        withTimeout(5000) { job.join() }
        assertEquals("New", collected.last().single().name)
    }
    @Test fun groupSnapshotsAreDetachedAndOrderIsPreserved() = runBlocking {
        val store = Fake(); val loaded = DefaultSearchScopeRepository(store).groups()
        store.groups.clear(); assertEquals(listOf("B", "A"), loaded)
    }
    @Test fun blockingGroupLoadAndFlowCollectionUseInjectedIoThread() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val store = Fake(); val repository = DefaultSearchScopeRepository(store, io)
            repository.groups(); repository.sources("").first()
            assertEquals(2, store.threads.size)
            assertTrue(store.threads.all { it === store.threads.first() })
            assertNotSame(Thread.currentThread(), store.threads.first())
        }
    }
    private class Fake : SearchScopeStore {
        val groups = mutableListOf("B", "A"); val rows = MutableStateFlow(emptyList<BookSourcePart>())
        val threads = mutableListOf<Thread>(); var query = ""
        override suspend fun enabledGroups(): List<String> { threads += Thread.currentThread(); return groups }
        override fun sources(query: String): Flow<List<BookSourcePart>> {
            this.query = query
            return flow { threads += Thread.currentThread(); emitAll(rows) }
        }
    }
}
