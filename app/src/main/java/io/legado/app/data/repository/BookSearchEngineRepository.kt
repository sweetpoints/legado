package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.BookSearchResult
import io.legado.app.model.webBook.BookSearchSession
import io.legado.app.model.webBook.SearchModel
import io.legado.app.model.webBook.SearchScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface BookSearchEngineCallback {
    fun started()

    fun progress(searched: Int, total: Int)

    fun results(rows: List<SearchBook>)

    fun finished(empty: Boolean, hasMore: Boolean)

    fun canceled(error: Throwable?)
}

interface BookSearchEngine {
    val resolvedScope: String

    fun search(id: Long, key: String)

    fun pause()

    fun resume()

    fun close()
}

fun interface BookSearchEngineFactory {
    fun create(scope: String, callback: BookSearchEngineCallback): BookSearchEngine
}

interface BookSearchEngineRepository {
    val state: StateFlow<BookSearchSession>

    suspend fun search(key: String, scope: String)

    suspend fun nextPage()

    suspend fun stop()

    fun pause()

    fun resume()

    suspend fun close()
}

/**
 * IO entry to the existing search engine; each query owns its callbacks and mutable legacy scope.
 */
class DefaultBookSearchEngineRepository(
    private val factory: BookSearchEngineFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookSearchEngineRepository {
    private val commands = Mutex()
    private val lock = Any()
    private val sequence = AtomicLong()
    private val mutableState = MutableStateFlow(BookSearchSession())
    override val state = mutableState.asStateFlow()

    private var engine: BookSearchEngine? = null
    private var active = true
    private var closed = false

    private fun update(
        generation: Long,
        transform: (BookSearchSession) -> BookSearchSession,
    ) =
        synchronized(lock) {
            if (!closed && generation == sequence.get()) {
                mutableState.value = transform(mutableState.value)
            }
        }

    override suspend fun search(key: String, scope: String) {
        start(key, scope, retainResults = false)
    }

    private suspend fun start(key: String, scope: String, retainResults: Boolean) {
        val generation =
            synchronized(lock) {
                if (closed) return
                sequence.incrementAndGet()
            }
        commands.withLock {
            withContext(io) {
                currentCoroutineContext().ensureActive()
                if (generation != sequence.get()) return@withContext

                val previous =
                    synchronized(lock) {
                        engine.also { engine = null }
                    }
                previous?.close()
                update(generation) { current ->
                    BookSearchSession(
                        generation = generation,
                        key = key,
                        scope = scope,
                        results = if (retainResults) current.results else emptyList(),
                    )
                }
                if (key.isEmpty()) return@withContext

                val callback = createCallback(generation)
                val created = factory.create(scope, callback)
                val accepted =
                    synchronized(lock) {
                        if (closed || generation != sequence.get()) {
                            false
                        } else {
                            engine = created
                            if (!active) created.pause()
                            true
                        }
                    }
                if (!accepted) {
                    created.close()
                    return@withContext
                }
                try {
                    created.search(generation, key)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(generation) { current ->
                        current.copy(
                            searching = false,
                            error = error.localizedMessage ?: error.toString(),
                        )
                    }
                }
            }
        }
    }

    private fun createCallback(generation: Long): BookSearchEngineCallback {
        return object : BookSearchEngineCallback {
            override fun started() =
                update(generation) { current ->
                    current.copy(
                        searching = true,
                        finishedEmpty = false,
                        error = null,
                        scope = synchronized(lock) { engine?.resolvedScope ?: current.scope },
                    )
                }

            override fun progress(searched: Int, total: Int) =
                update(generation) { current ->
                    current.copy(searched = searched, total = total)
                }

            override fun results(rows: List<SearchBook>) {
                val immutableResults = rows.map(BookSearchResult::from)
                update(generation) { current -> current.copy(results = immutableResults) }
            }

            override fun finished(empty: Boolean, hasMore: Boolean) =
                update(generation) { current ->
                    current.copy(searching = false, finishedEmpty = empty, hasMore = hasMore)
                }

            override fun canceled(error: Throwable?) =
                update(generation) { current ->
                    current.copy(
                        searching = false,
                        error = error?.localizedMessage ?: error?.toString(),
                    )
                }
        }
    }

    override suspend fun nextPage() {
        val restart =
            synchronized(lock) {
                val current = mutableState.value
                if (!closed && engine == null && current.key.isNotEmpty() && current.hasMore) {
                    current
                } else {
                    null
                }
            }
        if (restart != null) {
            start(restart.key, restart.scope, retainResults = true)
            return
        }
        commands.withLock {
            withContext(io) {
                currentCoroutineContext().ensureActive()
                val request =
                    synchronized(lock) {
                        val current = mutableState.value
                        if (
                            closed || current.searching || !current.hasMore || current.key.isEmpty()
                        ) {
                            null
                        } else {
                            engine to current
                        }
                    }
                request?.first?.let { ownedEngine ->
                    try {
                        ownedEngine.search(request.second.generation, request.second.key)
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        update(request.second.generation) { current ->
                            current.copy(
                                searching = false,
                                error = error.localizedMessage ?: error.toString(),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        val generation =
            synchronized(lock) {
                if (closed) return
                sequence.incrementAndGet().also { nextGeneration ->
                    mutableState.value =
                        mutableState.value.copy(
                            generation = nextGeneration,
                            searching = false,
                        )
                }
            }
        // Once stop owns this query, caller cancellation must not leak its executor resources.
        withContext(NonCancellable) {
            commands.withLock {
                withContext(io) {
                    val previous =
                        synchronized(lock) {
                            if (generation != sequence.get()) null
                            else engine.also { engine = null }
                        }
                    previous?.close()
                }
            }
        }
    }

    override fun pause() =
        synchronized(lock) {
            active = false
            engine?.pause() ?: Unit
        }

    override fun resume() =
        synchronized(lock) {
            active = true
            engine?.resume() ?: Unit
        }

    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sequence.incrementAndGet()
        }
        withContext(NonCancellable) {
            commands.withLock {
                withContext(io) {
                    val previous = synchronized(lock) { engine.also { engine = null } }
                    previous?.close()
                }
            }
        }
    }
}

class AppBookSearchEngineFactory(private val owner: CoroutineScope) : BookSearchEngineFactory {
    override fun create(scope: String, callback: BookSearchEngineCallback): BookSearchEngine {
        val searchScope = SearchScope(scope)
        val engine =
            SearchModel(
                owner,
                object : SearchModel.CallBack {
                    override fun getSearchScope() = searchScope

                    override fun onSearchStart() = callback.started()

                    override fun onSearchProgress(searched: Int, total: Int) =
                        callback.progress(searched, total)

                    override fun onSearchSuccess(searchBooks: List<SearchBook>) =
                        callback.results(searchBooks)

                    override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) =
                        callback.finished(isEmpty, hasMore)

                    override fun onSearchCancel(exception: Throwable?) =
                        callback.canceled(exception)
                },
            )
        return object : BookSearchEngine {
            override val resolvedScope: String
                get() = searchScope.toString()

            override fun search(id: Long, key: String) = engine.search(id, key)

            override fun pause() = engine.pause()

            override fun resume() = engine.resume()

            override fun close() = engine.close()
        }
    }
}
