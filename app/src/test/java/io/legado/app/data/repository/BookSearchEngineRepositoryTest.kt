package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchEngineRepositoryTest {
    private class Engine(
        override val resolvedScope: String,
        val callback: BookSearchEngineCallback,
    ) : BookSearchEngine {
        val calls = mutableListOf<Pair<Long, String>>()
        var pauses = 0
        var resumes = 0
        var closed = false

        override fun search(id: Long, key: String) {
            calls += id to key
            callback.started()
        }

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun close() {
            closed = true
        }
    }

    private class Factory : BookSearchEngineFactory {
        val engines = mutableListOf<Engine>()
        val callers = mutableListOf<Thread>()

        override fun create(scope: String, callback: BookSearchEngineCallback): BookSearchEngine {
            callers += Thread.currentThread()
            return Engine(scope, callback).also { engines += it }
        }
    }

    @Test
    fun queryReplacementRejectsEveryLateCallbackAndSnapshotsMergedOrigins() = runTest {
        val factory = Factory()
        val repository =
            DefaultBookSearchEngineRepository(
                factory,
                StandardTestDispatcher(testScheduler),
            )
        repository.search("old", "one")
        val previous = factory.engines.single()
        previous.callback.results(listOf(SearchBook("old")))

        repository.search("new", "two")
        val current = factory.engines.last()
        assertTrue(previous.closed)
        previous.callback.started()
        previous.callback.progress(99, 99)
        previous.callback.results(listOf(SearchBook("late")))
        previous.callback.finished(true, false)
        previous.callback.canceled(IllegalStateException("late"))
        assertEquals("new", repository.state.value.key)
        assertTrue(repository.state.value.searching)
        assertEquals(0, repository.state.value.searched)
        assertNull(repository.state.value.error)
        assertTrue(repository.state.value.results.isEmpty())

        val book = SearchBook("new", origin = "a", name = "Title").apply { addOrigin("b") }
        current.callback.results(listOf(book))
        book.origins.clear()
        book.name = "changed"
        current.callback.progress(2, 4)
        current.callback.finished(false, true)
        assertEquals("Title", repository.state.value.results.single().name)
        assertEquals(listOf("a", "b"), repository.state.value.results.single().origins)
        assertEquals(2, repository.state.value.searched)
        assertEquals(4, repository.state.value.total)
        repository.close()
    }

    @Test
    fun nextPageUsesSameEngineIdentityAndDoesNotRepeatRunningOrExhaustedPage() = runTest {
        val factory = Factory()
        val repository =
            DefaultBookSearchEngineRepository(
                factory,
                StandardTestDispatcher(testScheduler),
            )
        repository.search("query", "scope")
        val engine = factory.engines.single()
        repository.nextPage()
        assertEquals(1, engine.calls.size)

        engine.callback.finished(false, true)
        repository.nextPage()
        assertEquals(2, engine.calls.size)
        assertEquals(engine.calls[0], engine.calls[1])

        engine.callback.finished(false, false)
        repository.nextPage()
        assertEquals(2, engine.calls.size)
        repository.close()
    }

    @Test
    fun manualStopRetainsResultsButResumeCreatesFreshOwnerAndPauseState() = runTest {
        val factory = Factory()
        val repository =
            DefaultBookSearchEngineRepository(
                factory,
                StandardTestDispatcher(testScheduler),
            )
        repository.pause()
        repository.search("query", "scope")
        val previous = factory.engines.single()
        assertEquals(1, previous.pauses)
        previous.callback.results(listOf(SearchBook("kept")))
        repository.stop()
        previous.callback.results(listOf(SearchBook("late")))
        assertFalse(repository.state.value.searching)
        assertEquals("kept", repository.state.value.results.single().bookUrl)

        repository.nextPage()
        val current = factory.engines.last()
        assertNotSame(previous, current)
        assertTrue(previous.closed)
        assertEquals(1, current.pauses)
        assertEquals("kept", repository.state.value.results.single().bookUrl)
        repository.resume()
        assertEquals(1, current.resumes)
        current.callback.results(listOf(SearchBook("fresh")))
        assertEquals("fresh", repository.state.value.results.single().bookUrl)
        repository.close()
    }

    @Test
    fun factoryRunsOnActualIoAndTerminalCloseNeverPublishesLateStateOrCreatesNewOwner() =
        runBlocking {
            val caller = Thread.currentThread()
            val factory = Factory()
            val repository = DefaultBookSearchEngineRepository(factory)
            repository.search("query", "scope")
            assertTrue(factory.callers.all { it !== caller })
            val engine = factory.engines.single()
            val snapshot = repository.state.value
            repository.close()
            engine.callback.progress(1, 2)
            engine.callback.canceled(IllegalStateException("late"))
            repository.search("new", "new")
            repository.nextPage()
            assertEquals(snapshot, repository.state.value)
            assertEquals(1, factory.engines.size)
            assertTrue(engine.closed)
        }

    @Test
    fun acceptedStopAndCloseStillReleaseEngineInAnAlreadyCanceledCaller() = runTest {
        val factory = Factory()
        val repository =
            DefaultBookSearchEngineRepository(
                factory,
                StandardTestDispatcher(testScheduler),
            )
        repository.search("query", "scope")
        val first = factory.engines.single()
        val stopping = launch {
            currentCoroutineContext().cancel()
            repository.stop()
        }
        stopping.join()
        assertTrue(first.closed)
        assertFalse(repository.state.value.searching)

        repository.nextPage()
        val next = factory.engines.last()
        val closing = launch {
            currentCoroutineContext().cancel()
            repository.close()
        }
        closing.join()
        assertTrue(next.closed)
        val engineCount = factory.engines.size
        repository.search("ignored", "scope")
        assertEquals(engineCount, factory.engines.size)
    }
}
