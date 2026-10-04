package io.legado.app.ui.book.source.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BookSourceDebugStage {
    Search,
    Book,
    Toc,
    Content,
}

enum class BookSourceDebugIssue {
    Busy,
    Interrupted,
}

data class BookSourceDebugState(
    val name: String = "",
    val keyword: String = "我的",
    val query: String = "",
    val queryStart: Int = 0,
    val queryEnd: Int = 0,
    val output: String = "",
    val help: Boolean = true,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val running: Boolean = false,
    val missing: Boolean = false,
    val closed: Boolean = false,
    val sorts: List<BookSourceDebugSort> = emptyList(),
    val selectedSort: Int = 0,
    val hasSearchHtml: Boolean = false,
    val hasBookHtml: Boolean = false,
    val hasTocHtml: Boolean = false,
    val hasContentHtml: Boolean = false,
    val error: String? = null,
    val issue: BookSourceDebugIssue? = null,
)

private const val PREFIX = "book.debug."

class BookSourceDebugViewModel(
    private val repository: BookSourceDebugRepository,
    private val saved: SavedStateHandle,
    initialKey: String? = null,
    cleanupScope: CoroutineScope? = null,
) : ViewModel() {
    private val key = initialKey
    private val session =
        saved.get<String>(PREFIX + "session")
            ?: UUID.randomUUID().toString().also { saved[PREFIX + "session"] = it }
    private val mutable =
        MutableStateFlow(
            BookSourceDebugState(
                closed = saved[PREFIX + "closed"] ?: false,
                loading = saved.get<Boolean>(PREFIX + "closed") != true,
            )
        )
    val state = mutable.asStateFlow()
    // Independent from the Activity/ViewModel parent: real close cleanup survives onCleared.
    private val cleanup =
        cleanupScope
            ?: CoroutineScope(SupervisorJob() + viewModelScope.coroutineContext.minusKey(Job))
    private val guard = Any()
    private val generation = AtomicLong()
    @Volatile private var stopped = false
    @Volatile private var owner: BookSourceDebugLease? = null
    private var source: BookSourceDebugSnapshot? = null
    private var record: BookSourceDebugRecord? = null
    private var loadJob: Job? = null
    private var runJob: Job? = null
    private var sortsJob: Job? = null
    private var sortsGeneration = 0L
    private val records = Channel<BookSourceDebugRecord>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (record in records) try {
            repository.write(session, record)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            synchronized(guard) {
                if (this@BookSourceDebugViewModel.record?.revision == record.revision) failed(error)
            }
        }
    }

    init {
        if (state.value.closed) {
            stop()
            releaseSession()
        } else load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            try {
                val restored = repository.read(session)
                currentCoroutineContext().ensureActive()
                val snapshot = (key ?: restored?.sourceKey)?.let { repository.load(it) }
                currentCoroutineContext().ensureActive()
                if (snapshot == null) {
                    mutable.value = state.value.copy(loading = false, missing = true)
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                check(restored == null || restored.sourceKey == snapshot.key) {
                    "Invalid debug record"
                }
                val sorts = repository.sorts(snapshot)
                currentCoroutineContext().ensureActive()
                synchronized(guard) {
                    source = snapshot
                    record = restored ?: BookSourceDebugRecord(snapshot.key)
                    val query = record!!.query
                    mutable.value =
                        state.value.copy(
                            name = snapshot.name,
                            keyword = snapshot.keyword,
                            query = query,
                            queryStart =
                                (saved.get<Int>(PREFIX + "queryStart") ?: query.length).coerceIn(
                                    0,
                                    query.length,
                                ),
                            queryEnd =
                                (saved.get<Int>(PREFIX + "queryEnd") ?: query.length).coerceIn(
                                    0,
                                    query.length,
                                ),
                            output = record!!.output.takeLast(20_000),
                            help = record!!.help,
                            loading = false,
                            loaded = true,
                            sorts = sorts,
                            selectedSort =
                                (saved.get<Int>(PREFIX + "sort") ?: 0).coerceIn(
                                    0,
                                    (sorts.size - 1).coerceAtLeast(0),
                                ),
                            hasSearchHtml = record!!.searchHtml != null,
                            hasBookHtml = record!!.bookHtml != null,
                            hasTocHtml = record!!.tocHtml != null,
                            hasContentHtml = record!!.contentHtml != null,
                            issue =
                                if (record!!.running) BookSourceDebugIssue.Interrupted else null,
                        )
                    val exploreError = sorts.firstOrNull()?.takeIf { it.name.startsWith("ERROR:") }
                    if (exploreError != null)
                        mutable.value =
                            state.value.copy(
                                output =
                                    (state.value.output + "\n获取发现出错\n${exploreError.url}")
                                        .trimStart()
                                        .takeLast(20_000),
                                help = false,
                            )
                    if (restored?.running == true || exploreError != null) checkpoint()
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failed(error)
                mutable.value = state.value.copy(loading = false)
            }
        }
    }

    fun retry() {
        if (stopped || state.value.loading) return
        if (!state.value.loaded) {
            mutable.value = state.value.copy(loading = true, error = null)
            load()
        } else
            synchronized(guard) {
                mutable.value = state.value.copy(error = null)
                record?.let { records.trySend(it) }
            }
    }

    private fun checkpoint() {
        val previous = record ?: return
        record =
            previous.copy(
                query = state.value.query,
                help = state.value.help,
                output = state.value.output,
                running = state.value.running,
                revision = previous.revision + 1,
            )
        records.trySend(record!!)
    }

    fun query(text: String, start: Int = text.length, end: Int = start) {
        if (stopped || state.value.closed || !state.value.loaded) return
        synchronized(guard) {
            saved[PREFIX + "queryStart"] = start.coerceIn(0, text.length)
            saved[PREFIX + "queryEnd"] = end.coerceIn(0, text.length)
            mutable.value =
                state.value.copy(
                    query = text,
                    queryStart = start.coerceIn(0, text.length),
                    queryEnd = end.coerceIn(0, text.length),
                )
            checkpoint()
        }
    }

    fun help(value: Boolean) {
        if (stopped || state.value.closed || !state.value.loaded) return
        synchronized(guard) {
            mutable.value = state.value.copy(help = value)
            checkpoint()
        }
    }

    fun sort(index: Int) {
        val sort = state.value.sorts.getOrNull(index) ?: return
        if (sort.name.startsWith("ERROR:")) return
        saved[PREFIX + "sort"] = index
        mutable.value = state.value.copy(selectedSort = index)
        query(sort.query)
        run(sort.query)
    }

    fun run(query: String? = state.value.query) {
        val snapshot = source ?: return
        if (stopped || state.value.closed || !state.value.loaded) return
        val value = query ?: "我的"
        val token = generation.incrementAndGet()
        val previous = runJob
        previous?.cancel()
        owner?.close()
        owner = null
        synchronized(guard) {
            mutable.value =
                state.value.copy(
                    query = value,
                    queryStart = value.length,
                    queryEnd = value.length,
                    output = "",
                    help = false,
                    running = true,
                    error = null,
                    issue = null,
                )
            saved[PREFIX + "queryStart"] = value.length
            saved[PREFIX + "queryEnd"] = value.length
            checkpoint()
        }
        runJob = viewModelScope.launch {
            var lease: BookSourceDebugLease? = null
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    lease =
                        repository.acquire(snapshot) { incoming -> handleEvent(token, incoming) }
                }
                currentCoroutineContext().ensureActive()
                if (lease == null) {
                    change(token) { it.copy(issue = BookSourceDebugIssue.Busy) }
                    return@launch
                }
                owner = lease
                lease.run(value)
                currentCoroutineContext().ensureActive()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                change(token) { it.copy(error = error.localizedMessage ?: "调试失败") }
            } finally {
                lease?.close()
                withContext(NonCancellable) { lease?.awaitStopped() }
                if (owner === lease) owner = null
                change(token) { it.copy(running = false) }
            }
        }
    }

    private fun handleEvent(token: Long, event: BookSourceDebugEvent) =
        synchronized(guard) {
            if (stopped || generation.get() != token) return@synchronized
            when (event.state) {
                10 -> {
                    record = record?.copy(searchHtml = event.text)
                    mutable.value = state.value.copy(hasSearchHtml = true)
                }
                20 -> {
                    record = record?.copy(bookHtml = event.text)
                    mutable.value = state.value.copy(hasBookHtml = true)
                }
                30 -> {
                    record = record?.copy(tocHtml = event.text)
                    mutable.value = state.value.copy(hasTocHtml = true)
                }
                40 -> {
                    record = record?.copy(contentHtml = event.text)
                    mutable.value = state.value.copy(hasContentHtml = true)
                }
                else ->
                    mutable.value =
                        state.value.copy(
                            output =
                                (if (state.value.output.isEmpty()) event.text
                                    else state.value.output + "\n" + event.text)
                                    .takeLast(20_000)
                        )
            }
            checkpoint()
        }

    private fun change(token: Long, transform: (BookSourceDebugState) -> BookSourceDebugState) =
        synchronized(guard) {
            if (!stopped && generation.get() == token) {
                mutable.value = transform(state.value)
                checkpoint()
            }
        }

    fun html(stage: BookSourceDebugStage): String? =
        synchronized(guard) {
            when (stage) {
                BookSourceDebugStage.Search -> record?.searchHtml
                BookSourceDebugStage.Book -> record?.bookHtml
                BookSourceDebugStage.Toc -> record?.tocHtml
                BookSourceDebugStage.Content -> record?.contentHtml
            }
        }

    fun prefix(prefix: String) {
        require(prefix == "++" || prefix == "--")
        val value = state.value.query
        if (value.isBlank() || value.length <= 2) query(prefix)
        else run(if (value.startsWith(prefix)) value else prefix + value)
    }

    fun detail() {
        if (state.value.query.isNotBlank()) run()
    }

    fun refreshExplore() {
        val snapshot = source ?: return
        if (stopped || state.value.closed) return
        sortsJob?.cancel()
        val token = ++sortsGeneration
        synchronized(guard) {
            mutable.value = state.value.copy(output = "", help = true)
            checkpoint()
        }
        sortsJob = viewModelScope.launch {
            try {
                val sorts = repository.sorts(snapshot, refresh = true)
                currentCoroutineContext().ensureActive()
                if (stopped || token != sortsGeneration) return@launch
                synchronized(guard) {
                    val first = sorts.firstOrNull()
                    val error = first?.takeIf { it.name.startsWith("ERROR:") }
                    saved[PREFIX + "sort"] = 0
                    mutable.value =
                        state.value.copy(
                            sorts = sorts,
                            selectedSort = 0,
                            output =
                                if (error != null) "获取发现出错\n${error.url}".takeLast(20_000)
                                else state.value.output,
                            help = error == null,
                        )
                    checkpoint()
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == sortsGeneration) failed(error)
            }
        }
    }

    fun close() {
        if (state.value.closed) return
        synchronized(guard) {
            saved[PREFIX + "closed"] = true
            mutable.value = state.value.copy(closed = true, running = false)
        }
        stop()
        releaseSession()
    }

    private fun releaseSession() {
        cleanup.launch { runCatching { repository.release(session) } }
    }

    suspend fun flush() {
        val snapshot = synchronized(guard) { record }
        snapshot?.let { repository.write(session, it) }
    }

    private fun failed(error: Exception) {
        if (error is CancellationException) throw error
        synchronized(guard) {
            if (!stopped)
                mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
        }
    }

    internal fun stop() {
        synchronized(guard) {
            stopped = true
            generation.incrementAndGet()
        }
        loadJob?.cancel()
        sortsJob?.cancel()
        runJob?.cancel()
        owner?.close()
        owner = null
        writer.cancel()
        records.close()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
