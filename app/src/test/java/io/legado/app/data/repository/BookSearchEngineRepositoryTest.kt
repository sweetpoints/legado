package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchEngineRepositoryTest {
    private class Engine(override val resolvedScope: String, val callback: BookSearchEngineCallback) : BookSearchEngine {
        val calls = mutableListOf<Pair<Long,String>>(); var pauses = 0; var resumes = 0; var closed = false
        override fun search(id: Long, key: String) { calls += id to key; callback.started() }
        override fun pause() { pauses++ }
        override fun resume() { resumes++ }
        override fun close() { closed = true }
    }
    private class Factory : BookSearchEngineFactory {
        val engines = mutableListOf<Engine>(); val callers = mutableListOf<Thread>()
        override fun create(scope: String, callback: BookSearchEngineCallback): BookSearchEngine { callers += Thread.currentThread(); return Engine(scope, callback).also { engines += it } }
    }
    @Test fun queryReplacementRejectsEveryLateCallbackAndSnapshotsMergedOrigins() = runTest {
        val factory = Factory(); val repository = DefaultBookSearchEngineRepository(factory, StandardTestDispatcher(testScheduler))
        repository.search("old", "one"); val old = factory.engines.single(); old.callback.results(listOf(SearchBook("old")))
        repository.search("new", "two"); val current = factory.engines.last(); assertTrue(old.closed)
        old.callback.started(); old.callback.progress(99, 99); old.callback.results(listOf(SearchBook("late"))); old.callback.finished(true, false); old.callback.canceled(IllegalStateException("late"))
        assertEquals("new", repository.state.value.key); assertTrue(repository.state.value.searching); assertEquals(0, repository.state.value.searched); assertNull(repository.state.value.error); assertTrue(repository.state.value.results.isEmpty())
        val row = SearchBook("new", origin = "a", name = "Title").apply { addOrigin("b") }; current.callback.results(listOf(row)); row.origins.clear(); row.name = "changed"; current.callback.progress(2, 4); current.callback.finished(false, true)
        assertEquals("Title", repository.state.value.results.single().name); assertEquals(listOf("a", "b"), repository.state.value.results.single().origins); assertEquals(2, repository.state.value.searched); assertEquals(4, repository.state.value.total); repository.close()
    }
    @Test fun nextPageUsesSameEngineIdentityAndDoesNotRepeatRunningOrExhaustedPage() = runTest {
        val factory = Factory(); val repository = DefaultBookSearchEngineRepository(factory, StandardTestDispatcher(testScheduler))
        repository.search("query", "scope"); val engine = factory.engines.single(); repository.nextPage(); assertEquals(1, engine.calls.size)
        engine.callback.finished(false, true); repository.nextPage(); assertEquals(2, engine.calls.size); assertEquals(engine.calls[0], engine.calls[1])
        engine.callback.finished(false, false); repository.nextPage(); assertEquals(2, engine.calls.size); repository.close()
    }
    @Test fun manualStopRetainsResultsButResumeCreatesFreshOwnerAndPauseState() = runTest {
        val factory = Factory(); val repository = DefaultBookSearchEngineRepository(factory, StandardTestDispatcher(testScheduler))
        repository.pause(); repository.search("query", "scope"); val old = factory.engines.single(); assertEquals(1, old.pauses)
        old.callback.results(listOf(SearchBook("kept"))); repository.stop(); old.callback.results(listOf(SearchBook("late"))); assertFalse(repository.state.value.searching); assertEquals("kept", repository.state.value.results.single().bookUrl)
        repository.nextPage(); val current = factory.engines.last(); assertNotSame(old, current); assertTrue(old.closed); assertEquals(1, current.pauses); assertEquals("kept", repository.state.value.results.single().bookUrl)
        repository.resume(); assertEquals(1, current.resumes); current.callback.results(listOf(SearchBook("fresh"))); assertEquals("fresh", repository.state.value.results.single().bookUrl); repository.close()
    }
    @Test fun factoryRunsOnActualIoAndTerminalCloseNeverPublishesLateStateOrCreatesNewOwner() = runBlocking {
        val caller = Thread.currentThread(); val factory = Factory(); val repository = DefaultBookSearchEngineRepository(factory)
        repository.search("query", "scope"); assertTrue(factory.callers.all { it !== caller }); val engine = factory.engines.single(); val before = repository.state.value
        repository.close(); engine.callback.progress(1, 2); engine.callback.canceled(IllegalStateException("late")); repository.search("new", "new"); repository.nextPage()
        assertEquals(before, repository.state.value); assertEquals(1, factory.engines.size); assertTrue(engine.closed)
    }
    @Test fun acceptedStopAndCloseStillReleaseEngineInAnAlreadyCanceledCaller() = runTest {
        val factory = Factory(); val repository = DefaultBookSearchEngineRepository(factory, StandardTestDispatcher(testScheduler))
        repository.search("query", "scope"); val first = factory.engines.single()
        val stop = launch { currentCoroutineContext().cancel(); repository.stop() }; stop.join()
        assertTrue(first.closed); assertFalse(repository.state.value.searching)
        repository.nextPage(); val next = factory.engines.last()
        val close = launch { currentCoroutineContext().cancel(); repository.close() }; close.join()
        assertTrue(next.closed); val size = factory.engines.size; repository.search("ignored", "scope"); assertEquals(size, factory.engines.size)
    }

}
