package io.legado.app.ui.rss.source.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class RssSourceDebugIssue { Busy, Interrupted }
data class RssSourceDebugState(val name: String = "", val query: String = "", val queryStart: Int = 0, val queryEnd: Int = 0,
    val output: String = "", val help: Boolean = true, val loading: Boolean = true, val loaded: Boolean = false,
    val running: Boolean = false, val missing: Boolean = false, val closed: Boolean = false,
    val sorts: List<RssSourceDebugSort> = emptyList(), val selectedSort: Int = 0,
    val hasListHtml: Boolean = false, val hasContentHtml: Boolean = false,
    val error: String? = null, val issue: RssSourceDebugIssue? = null)
private const val PREFIX = "rss.debug."
class RssSourceDebugViewModel(private val repository: RssSourceDebugRepository, private val saved: SavedStateHandle,
    initialKey: String? = null) : ViewModel() {
    private val key = saved.get<String>(PREFIX + "key") ?: initialKey.also { saved[PREFIX + "key"] = it }
    private val session = saved.get<String>(PREFIX + "session") ?: UUID.randomUUID().toString().also { saved[PREFIX + "session"] = it }
    private val mutable = MutableStateFlow(RssSourceDebugState(closed = saved[PREFIX + "closed"] ?: false))
    val state = mutable.asStateFlow()
    private val guard = Any(); private val generation = AtomicLong()
    @Volatile private var stopped = false
    @Volatile private var owner: RssSourceDebugLease? = null
    private var source: RssSourceDebugSnapshot? = null
    private var record: RssSourceDebugRecord? = null
    private var loadJob: Job? = null; private var runJob: Job? = null
    private val records = Channel<RssSourceDebugRecord>(Channel.CONFLATED)
    private val writer = viewModelScope.launch { for (record in records) try { repository.write(session, record) } catch (error: Exception) { failed(error) } }
    init { load() }
    private fun load() {
        loadJob = viewModelScope.launch {
            try {
                val snapshot = key?.let { repository.load(it) }
                currentCoroutineContext().ensureActive()
                if (snapshot == null) { mutable.value = state.value.copy(loading = false, missing = true); return@launch }
                val restored = repository.read(session)
                currentCoroutineContext().ensureActive(); check(restored == null || restored.sourceKey == snapshot.key) { "Invalid debug record" }
                val sorts = repository.sorts(snapshot)
                currentCoroutineContext().ensureActive()
                synchronized(guard) {
                    source = snapshot; record = restored ?: RssSourceDebugRecord(snapshot.key)
                    val query = record!!.query
                    mutable.value = state.value.copy(name = snapshot.name, query = query,
                        queryStart = (saved.get<Int>(PREFIX + "queryStart") ?: query.length).coerceIn(0, query.length),
                        queryEnd = (saved.get<Int>(PREFIX + "queryEnd") ?: query.length).coerceIn(0, query.length),
                        output = record!!.output.takeLast(20_000), help = record!!.help, loading = false, loaded = true,
                        sorts = sorts, selectedSort = (saved.get<Int>(PREFIX + "sort") ?: 0).coerceIn(0, (sorts.size - 1).coerceAtLeast(0)),
                        hasListHtml = record!!.listHtml != null, hasContentHtml = record!!.contentHtml != null,
                        issue = if (record!!.running) RssSourceDebugIssue.Interrupted else null)
                    if (restored?.running == true) checkpoint()
                }
            } catch (error: Exception) { currentCoroutineContext().ensureActive(); failed(error); mutable.value = state.value.copy(loading = false) }
        }
    }
    fun retry() { if (!stopped && !state.value.loading && !state.value.loaded) { mutable.value = state.value.copy(loading = true, error = null); load() } }
    private fun checkpoint() {
        val previous = record ?: return
        record = previous.copy(query = state.value.query, help = state.value.help, output = state.value.output,
            running = state.value.running, revision = previous.revision + 1)
        records.trySend(record!!)
    }
    fun query(text: String, start: Int = text.length, end: Int = start) {
        if (stopped || state.value.closed || !state.value.loaded) return
        synchronized(guard) {
            saved[PREFIX + "queryStart"] = start.coerceIn(0, text.length); saved[PREFIX + "queryEnd"] = end.coerceIn(0, text.length)
            mutable.value = state.value.copy(query = text, queryStart = start.coerceIn(0, text.length), queryEnd = end.coerceIn(0, text.length)); checkpoint()
        }
    }
    fun help(value: Boolean) {
        if (stopped || state.value.closed || !state.value.loaded) return
        synchronized(guard) { mutable.value = state.value.copy(help = value); checkpoint() }
    }
    fun sort(index: Int) {
        val sort = state.value.sorts.getOrNull(index) ?: return
        if (sort.name.startsWith("ERROR:")) return
        saved[PREFIX + "sort"] = index; mutable.value = state.value.copy(selectedSort = index)
        query(sort.query); run(sort.query)
    }
    fun run(query: String? = state.value.query) {
        val snapshot = source ?: return
        if (stopped || state.value.closed || !state.value.loaded) return
        val value = query ?: "我的"; val token = generation.incrementAndGet(); val previous = runJob
        previous?.cancel(); owner?.close(); owner = null
        synchronized(guard) {
            record = record?.copy(listHtml = null, contentHtml = null)
            mutable.value = state.value.copy(query = value, queryStart = value.length, queryEnd = value.length, output = "", help = false,
                running = true, error = null, issue = null, hasListHtml = false, hasContentHtml = false)
            saved[PREFIX + "queryStart"] = value.length; saved[PREFIX + "queryEnd"] = value.length; checkpoint()
        }
        runJob = viewModelScope.launch {
            var lease: RssSourceDebugLease? = null
            try {
                previous?.join(); currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { lease = repository.acquire(snapshot) { incoming -> handleEvent(token, incoming) } }
                currentCoroutineContext().ensureActive()
                if (lease == null) { change(token) { it.copy(issue = RssSourceDebugIssue.Busy) }; return@launch }
                owner = lease; lease!!.run(value); currentCoroutineContext().ensureActive()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { change(token) { it.copy(error = error.localizedMessage ?: "调试失败") } }
            finally {
                lease?.close(); withContext(NonCancellable) { lease?.awaitStopped() }
                if (owner === lease) owner = null
                change(token) { it.copy(running = false) }
            }
        }
    }
    private fun handleEvent(token: Long, event: RssSourceDebugEvent) = synchronized(guard) {
        if (stopped || generation.get() != token) return@synchronized
        when (event.state) {
            10 -> { record = record?.copy(listHtml = event.text); mutable.value = state.value.copy(hasListHtml = true) }
            20 -> { record = record?.copy(contentHtml = event.text); mutable.value = state.value.copy(hasContentHtml = true) }
            else -> mutable.value = state.value.copy(output = (if (state.value.output.isEmpty()) event.text else state.value.output + "\n" + event.text).takeLast(20_000))
        }
        checkpoint()
    }
    private fun change(token: Long, transform: (RssSourceDebugState) -> RssSourceDebugState) = synchronized(guard) {
        if (!stopped && generation.get() == token) { mutable.value = transform(state.value); checkpoint() }
    }
    fun html(content: Boolean): String? = synchronized(guard) { if (content) record?.contentHtml else record?.listHtml }
    fun close() {
        if (state.value.closed) return
        synchronized(guard) { saved[PREFIX + "closed"] = true; mutable.value = state.value.copy(closed = true, running = false); checkpoint() }
        stop()
    }
    suspend fun flush() { val snapshot = synchronized(guard) { record }; snapshot?.let { repository.write(session, it) } }
    private fun failed(error: Exception) { if (error is CancellationException) throw error; synchronized(guard) { if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") } }
    internal fun stop() { synchronized(guard) { stopped = true; generation.incrementAndGet() }; loadJob?.cancel(); runJob?.cancel(); owner?.close(); owner = null; writer.cancel(); records.close() }
    override fun onCleared() { stop(); super.onCleared() }
}
