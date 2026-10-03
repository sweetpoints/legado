package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchEngineRepository
import io.legado.app.data.repository.BookSearchMetadataRepository
import io.legado.app.model.webBook.BookSearchDraft
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The filesystem session owns editable text and result payloads; SavedState retains only its ID.
 */
internal class BookSearchViewModel(
    private val drafts: BookSearchDraftRepository,
    private val preferences: BookSearchPreferencesRepository,
    private val metadata: BookSearchMetadataRepository,
    savedState: SavedStateHandle,
    engineFactory: ((CoroutineScope) -> BookSearchEngineRepository)? = null,
) : ViewModel() {
    val session: String =
        savedState.get<String>("searchSession")
            ?: UUID.randomUUID().toString().also {
                savedState["searchSession"] = it
            }
    private val mutableState = MutableStateFlow(BookSearchUiState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private val observers = mutableListOf<Job>()
    private val writes = Channel<BookSearchDraft>(Channel.CONFLATED)
    private val writeGate = Mutex()
    private var revision = 0L
    private var stopped = false
    private var loadJob: Job? = null
    private var historyJob: Job? = null
    private var suggestionsJob: Job? = null
    private val engine = engineFactory?.invoke(viewModelScope)
    private var commandJob: Job? = null
    private var commandGeneration = 0L
    private var acceptsEngine = false
    private var minimumEngineGeneration = Long.MAX_VALUE
    private var lastEngineError: String? = null

    init {
        jobs += viewModelScope.launch {
            for (draft in writes) {
                try {
                    persist(draft)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!stopped)
                        mutableState.value =
                            state.value.copy(persistError = error.message ?: error.toString())
                }
            }
        }
        engine?.let { searchEngine ->
            jobs +=
                observe(searchEngine.state) { progress ->
                    if (
                        !state.value.ready ||
                            !acceptsEngine ||
                            progress.generation < minimumEngineGeneration
                    )
                        return@observe
                    if (progress.key != state.value.draft.submittedKey) return@observe
                    mutableState.value = state.value.copy(searching = progress.searching)
                    updateDraft { draft ->
                        draft.copy(
                            results = progress.results,
                            scope = progress.scope,
                            searched = progress.searched,
                            total = progress.total,
                            hasMore = progress.hasMore,
                            interrupted = progress.searching,
                            emptyScopeConfirmation =
                                progress.finishedEmpty && progress.scope.isNotEmpty(),
                        )
                    }
                    if (progress.error != lastEngineError) {
                        lastEngineError = progress.error
                        mutableState.value = state.value.copy(commandError = progress.error)
                    }
                }
        }
        initialize()
    }

    private fun initialize() {
        if (stopped || loadJob?.isActive == true) return
        mutableState.value =
            state.value.copy(loading = true, initializationFailed = false, persistError = null)
        loadJob = viewModelScope.launch {
            try {
                val restored = drafts.open(session)
                val settings = preferences.load()
                currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                // A process restart may restore a disk revision newer than its last saved Bundle.
                revision = maxOf(revision, restored.revision)
                val draft =
                    if (restored.revision == 0L) restored.copy(scope = settings.scope) else restored
                mutableState.value =
                    state.value.copy(
                        loading = false,
                        draft = draft,
                        preferences = settings,
                        durableRevision = restored.revision,
                    )
                observeMetadata()
                observeQuery(draft.query)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(
                            loading = false,
                            initializationFailed = true,
                            persistError = error.message ?: error.toString(),
                        )
            }
        }
    }

    private fun observeMetadata() {
        observers.forEach(Job::cancel)
        observers.clear()
        observers +=
            observe(preferences.observe()) { settings ->
                mutableState.value = state.value.copy(preferences = settings)
            }
        observers +=
            observe(metadata.membership()) { membership ->
                mutableState.value = state.value.copy(membership = membership)
            }
        observers +=
            observe(metadata.groups()) { groups ->
                mutableState.value = state.value.copy(groups = groups)
            }
    }

    private fun <T> observe(flow: Flow<T>, accept: (T) -> Unit): Job = viewModelScope.launch {
        try {
            flow.collect { value ->
                currentCoroutineContext().ensureActive()
                if (!stopped) accept(value)
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!stopped)
                mutableState.value =
                    state.value.copy(metadataError = error.message ?: error.toString())
        }
    }

    private fun observeQuery(query: String) {
        historyJob?.cancel()
        suggestionsJob?.cancel()
        historyJob =
            observe(metadata.history(query.trim())) { history ->
                mutableState.value = state.value.copy(history = history)
            }
        suggestionsJob =
            observe(metadata.suggestions(query.trim())) { suggestions ->
                mutableState.value = state.value.copy(suggestions = suggestions)
            }
    }

    fun editQuery(
        query: String,
        selectionStart: Int = query.length,
        selectionEnd: Int = selectionStart,
    ) {
        if (!usable()) return
        val textChanged = query != state.value.draft.query
        if (textChanged) invalidateSearch()
        updateDraft { draft ->
            draft.copy(
                query = query,
                selectionStart = selectionStart.coerceIn(0, query.length),
                selectionEnd = selectionEnd.coerceIn(0, query.length),
                inputHelp = if (textChanged) true else draft.inputHelp,
                manualStop = if (textChanged) true else draft.manualStop,
            )
        }
        if (textChanged) observeQuery(query)
    }

    fun focusInput(focused: Boolean) {
        if (!usable()) return
        val visible =
            !state.value.searching &&
                (focused ||
                    state.value.draft.results.isEmpty() ||
                    state.value.draft.query.isBlank())
        updateDraft { it.copy(inputHelp = visible) }
    }

    fun openFilter() {
        if (!usable()) return
        updateDraft {
            it.copy(filterDraft = state.value.preferences.resultFilter, filterSelection = 0)
        }
    }

    fun editFilter(text: String, selection: Int = text.length) {
        if (!usable() || state.value.draft.filterDraft == null) return
        updateDraft {
            it.copy(filterDraft = text, filterSelection = selection.coerceIn(0, text.length))
        }
    }

    fun dismissFilter() {
        if (!usable()) return
        updateDraft { it.copy(filterDraft = null, filterSelection = 0) }
    }

    fun confirmClearHistory(visible: Boolean) {
        if (!usable()) return
        updateDraft { it.copy(clearHistoryConfirmation = visible) }
    }

    fun submit() {
        if (!usable()) return
        startQuery(state.value.draft.query.trim(), saveHistory = true)
    }

    private fun startQuery(query: String, saveHistory: Boolean) {
        invalidateSearch()
        updateDraft { draft ->
            draft.copy(
                submittedKey = query,
                results = emptyList(),
                inputHelp = false,
                manualStop = false,
                interrupted = query.isNotEmpty(),
                hasMore = true,
                searched = 0,
                total = 0,
                emptyScopeConfirmation = false,
            )
        }
        val generation = commandGeneration
        commandJob = viewModelScope.launch {
            try {
                checkpoint()
                if (saveHistory) metadata.saveHistory(query)
                currentCoroutineContext().ensureActive()
                if (stopped || generation != commandGeneration) return@launch
                val searchEngine = engine ?: return@launch
                minimumEngineGeneration = searchEngine.state.value.generation + 1
                acceptsEngine = true
                lastEngineError = null
                mutableState.value = state.value.copy(commandError = null)
                searchEngine.search(query, state.value.draft.scope)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped && generation == commandGeneration)
                    mutableState.value =
                        state.value.copy(
                            searching = false,
                            commandError = error.message ?: error.toString(),
                        )
            }
        }
    }

    private fun invalidateSearch() {
        acceptsEngine = false
        commandGeneration++
        commandJob?.cancel()
        mutableState.value = state.value.copy(searching = false)
        engine?.let { searchEngine -> jobs += viewModelScope.launch { searchEngine.stop() } }
    }

    fun stopSearch() {
        if (!usable()) return
        invalidateSearch()
        updateDraft { it.copy(manualStop = true, interrupted = false) }
    }

    fun continueSearch(manual: Boolean = true) {
        if (
            !usable() ||
                state.value.searching ||
                commandJob?.isActive == true ||
                !state.value.draft.hasMore
        )
            return
        val draft = state.value.draft
        if (draft.submittedKey.isEmpty() || (!manual && draft.manualStop)) return
        updateDraft { it.copy(manualStop = false, interrupted = true) }
        commandJob = viewModelScope.launch {
            try {
                checkpoint()
                val searchEngine = engine ?: return@launch
                acceptsEngine = true
                if (searchEngine.state.value.key != draft.submittedKey) {
                    minimumEngineGeneration = searchEngine.state.value.generation + 1
                    searchEngine.search(draft.submittedKey, draft.scope)
                } else {
                    minimumEngineGeneration = searchEngine.state.value.generation
                    searchEngine.nextPage()
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(commandError = error.message ?: error.toString())
            }
        }
    }

    fun historyClicked(word: String) {
        if (!usable()) return
        val sameQuery = word == state.value.draft.query
        editQuery(word)
        val generation = commandGeneration
        commandJob = viewModelScope.launch {
            try {
                val shouldSearch = sameQuery || !metadata.hasNamedBook(word)
                currentCoroutineContext().ensureActive()
                if (generation == commandGeneration && !stopped && shouldSearch) submit()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped && generation == commandGeneration)
                    mutableState.value =
                        state.value.copy(commandError = error.message ?: error.toString())
            }
        }
    }

    fun deleteHistory(word: String) {
        if (!usable()) return
        jobs += viewModelScope.launch {
            try {
                metadata.deleteHistory(word)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(commandError = error.message ?: error.toString())
            }
        }
    }

    fun clearHistory() {
        if (!usable()) return
        jobs += viewModelScope.launch {
            try {
                metadata.clearHistory()
                currentCoroutineContext().ensureActive()
                if (!stopped) updateDraft { it.copy(clearHistoryConfirmation = false) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(commandError = error.message ?: error.toString())
            }
        }
    }

    fun pause() {
        engine?.pause()
    }

    fun resume() {
        engine?.resume()
    }

    private fun usable(): Boolean = !stopped && state.value.ready

    private fun updateDraft(transform: (BookSearchDraft) -> BookSearchDraft) {
        val transformed = transform(state.value.draft)
        if (transformed == state.value.draft) return
        revision++
        val draft = transformed.copy(revision = revision)
        mutableState.value = state.value.copy(draft = draft)
        writes.trySend(draft)
    }

    private suspend fun persist(draft: BookSearchDraft) = writeGate.withLock {
        drafts.write(session, draft)
        currentCoroutineContext().ensureActive()
        if (!stopped)
            mutableState.value =
                state.value.copy(
                    durableRevision = maxOf(state.value.durableRevision, draft.revision),
                    persistError = null,
                )
    }

    fun retry() {
        if (stopped) return
        if (state.value.initializationFailed) initialize()
        else if (state.value.ready) {
            if (state.value.metadataError != null) {
                mutableState.value = state.value.copy(metadataError = null)
                observeMetadata()
                observeQuery(state.value.draft.query)
            }
            writes.trySend(state.value.draft)
        }
    }

    /** Used before native delivery; a queued writer cannot race the accepted payload. */
    suspend fun checkpoint() {
        check(usable()) { "Search session not initialized" }
        persist(state.value.draft)
    }

    /**
     * A real finish releases private payloads; Activity recreation retains the ViewModel/session.
     */
    suspend fun release() {
        stop()
        withContext(NonCancellable) {
            engine?.close()
            writeGate.withLock { drafts.release(session) }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        acceptsEngine = false
        commandGeneration++
        commandJob?.cancel()
        engine?.let { searchEngine -> cleanupScope.launch { searchEngine.close() } }
        loadJob?.cancel()
        historyJob?.cancel()
        suggestionsJob?.cancel()
        jobs.forEach(Job::cancel)
        observers.forEach(Job::cancel)
        writes.close()
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        // Engine cleanup owns no Activity and must survive ViewModel scope cancellation.
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
