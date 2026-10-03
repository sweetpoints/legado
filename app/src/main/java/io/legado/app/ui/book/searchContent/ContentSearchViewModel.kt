package io.legado.app.ui.book.searchContent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.ContentSearchOptions
import io.legado.app.data.preferences.ContentSearchOptionsRepository
import io.legado.app.data.repository.*
import io.legado.app.model.book.ContentSearchMatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class ContentSearchState(val loading: Boolean = true, val loadFailed: Boolean = false,
    val running: Boolean = false, val selecting: Boolean = false, val completed: Boolean = false,
    val query: String = "", val results: List<ContentSearchMatch> = emptyList(), val currentChapter: Int = 0,
    val position: Int = 0, val focusInput: Boolean = true, val options: ContentSearchOptions = ContentSearchOptions(),
    val searchedChapters: Int = 0, val totalChapters: Int = 0, val pendingResult: String? = null,
    val finished: Boolean = false, val error: String? = null, val persistError: Boolean = false)
internal data class ContentSearchDelivery(val selected: ContentSearchMatch, val results: List<ContentSearchMatch>, val index: Int)

internal class ContentSearchViewModel(private val repository: ContentSearchRepository,
    private val options: ContentSearchOptionsRepository, private val saved: SavedStateHandle,
    private val seed: suspend () -> ContentSearchSession) : ViewModel() {
    val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(ContentSearchState()); val state = mutable.asStateFlow()
    private var current: ContentSearchSession? = null
    private var book: ContentSearchBook? = null
    @Volatile private var cacheNames: Set<String> = emptySet()
    private var revision = 0L; private var generation = 0L; private var stopped = false
    private var load: Job? = null; private var search: Job? = null; private var selection: Job? = null
    private val writes = MutableStateFlow<ContentSearchSession?>(null)
    private val writer = viewModelScope.launch {
        writes.filterNotNull().collect { value ->
            try { repository.write(session, value); currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision) mutable.value = state.value.copy(persistError = false)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision) mutable.value = state.value.copy(persistError = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    init { initialize() }
    private fun nextRevision(): Long { revision = maxOf(revision + 1, System.nanoTime(), (current?.revision ?: 0) + 1); return revision }
    private fun initialize() {
        if (stopped) return
        load?.cancel(); mutable.value = state.value.copy(loading = true, loadFailed = false, error = null)
        load = viewModelScope.launch {
            try {
                var snapshot = repository.read(session) ?: repository.create(session, seed())
                // The durable revision can exceed both SavedState and a rebooted monotonic clock.
                revision = maxOf(revision, snapshot.revision)
                val completed = saved.get<Boolean>("completed") == true &&
                    (saved.get<Long>("completedRevision") ?: Long.MAX_VALUE) <= snapshot.revision
                saved["completed"] = completed
                val loaded = repository.load(snapshot.bookUrl); currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                if (saved.get<String>("process") != null && saved.get<String>("process") != process) options.restore(snapshot.options)
                saved["process"] = process
                snapshot = snapshot.copy(options = options.current())
                if (saved.get<String>("consumedResult") == snapshot.pendingResult && snapshot.pendingResult != null) snapshot = snapshot.copy(pendingResult = null, finished = true)
                val autoSubmit = snapshot.initialSubmit && !snapshot.finished
                snapshot = snapshot.copy(initialSubmit = false, revision = nextRevision())
                repository.write(session, snapshot); currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                current = snapshot; book = loaded.book; cacheNames = loaded.cacheNames.toSet()
                mutable.value = ContentSearchState(loading = false, query = snapshot.query, results = snapshot.results,
                    options = snapshot.options, currentChapter = loaded.book.currentChapter,
                    position = saved.get<Int>("position") ?: snapshot.position, focusInput = snapshot.searchOpen,
                    completed = completed, pendingResult = snapshot.pendingResult, finished = snapshot.finished)
                if (autoSubmit) submit()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: ContentSearchSessionClosedException) { currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(loading = false, finished = true) }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    private fun change(value: ContentSearchSession) {
        if (stopped) return
        current = value.copy(revision = nextRevision()); writes.value = current
    }
    fun query(value: String) {
        val snapshot = current ?: return
        if (stopped || snapshot.finished || state.value.selecting) return
        change(snapshot.copy(query = value)); mutable.value = state.value.copy(query = value)
    }
    fun replace(value: Boolean) { if (!stopped && current != null && !state.value.finished && !state.value.selecting) settings(options.replace(value)) }
    fun regex(value: Boolean) { if (!stopped && current != null && !state.value.finished && !state.value.selecting) settings(options.regex(value)) }
    fun refreshOptions() = settings(options.current())
    private fun settings(value: ContentSearchOptions) {
        val snapshot = current ?: return
        if (stopped || snapshot.finished || state.value.selecting || snapshot.options == value) return
        change(snapshot.copy(options = value)); mutable.value = state.value.copy(options = value)
    }
    fun position(value: Int) { if (!stopped) saved["position"] = value.coerceAtLeast(0) }
    fun cached(url: String, fileName: String) { if (!stopped && url == book?.url) cacheNames = cacheNames + fileName }
    fun submit() {
        val snapshot = current ?: return; val loaded = book ?: return
        val query = snapshot.query.trim()
        if (stopped || snapshot.finished || state.value.selecting || query.isBlank()) return
        search?.cancel(); val token = ++generation; saved["completed"] = false; saved["position"] = 0
        change(snapshot.copy(query = query, results = emptyList(), searchOpen = false, position = 0))
        mutable.value = state.value.copy(query = query, results = emptyList(), running = true, completed = false,
            focusInput = false, position = 0, error = null, searchedChapters = 0, totalChapters = 0)
        search = viewModelScope.launch {
            try {
                repository.search(loaded, query) { cacheNames }.collect { update ->
                    currentCoroutineContext().ensureActive(); if (stopped || token != generation) return@collect
                    val latest = current ?: return@collect
                    change(latest.copy(results = update.results.toList()))
                    saved["completed"] = !update.running
                    if (!update.running) saved["completedRevision"] = current?.revision
                    mutable.value = state.value.copy(results = update.results.toList(), running = update.running,
                        completed = !update.running, searchedChapters = update.searchedChapters, totalChapters = update.totalChapters)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped && token == generation) mutable.value = state.value.copy(running = false, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun stopSearch() { ++generation; search?.cancel(); if (!stopped) mutable.value = state.value.copy(running = false) }
    fun choose(id: String) {
        val snapshot = current ?: return
        if (stopped || snapshot.finished || state.value.selecting) return
        val result = snapshot.results.find { it.id == id && it.query.isNotBlank() } ?: return
        stopSearch(); mutable.value = state.value.copy(selecting = true, error = null)
        selection = viewModelScope.launch {
            try {
                val latest = current ?: return@launch
                val pending = latest.copy(pendingResult = result.id, finished = true, revision = nextRevision())
                repository.write(session, pending); currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                current = pending; mutable.value = state.value.copy(selecting = false, pendingResult = result.id, finished = true)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(selecting = false, persistError = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun prepareResult(id: String): ContentSearchDelivery {
        val snapshot = requireNotNull(current)
        check(snapshot.pendingResult == id)
        val index = snapshot.results.indexOfFirst { it.id == id }; check(index >= 0)
        return ContentSearchDelivery(snapshot.results[index], snapshot.results.toList(), index)
    }
    fun consumeResult(id: String): Boolean {
        val snapshot = current ?: return false
        if (stopped || snapshot.pendingResult != id || saved.get<String>("consumedResult") == id) return false
        saved["consumedResult"] = id
        change(snapshot.copy(pendingResult = null, finished = true))
        mutable.value = state.value.copy(pendingResult = null, finished = true)
        return true
    }
    fun retry() {
        if (state.value.loadFailed) initialize()
        else if (state.value.persistError) { current?.let { writes.value = it.copy(revision = nextRevision()).also { value -> current = value } } }
        else submit()
    }
    suspend fun flush() { if (!stopped) current?.let { repository.write(session, it) } }
    /** Called only for a real host finish, never for configuration recreation. */
    suspend fun releaseOwnedSession() { stop(); repository.release(session) }
    fun stop() { if (stopped) return; stopped = true; ++generation; load?.cancel(); search?.cancel(); selection?.cancel(); writer.cancel() }
    override fun onCleared() { stop(); super.onCleared() }
    private companion object { val process = UUID.randomUUID().toString() }
}
