package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchEngineRepository
import io.legado.app.data.repository.BookSearchMetadataRepository
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchEffect
import io.legado.app.model.webBook.BookSearchReceipt
import io.legado.app.model.webBook.BookSearchScopeSelection
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
    private val savedState: SavedStateHandle,
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
    private var settingsJob: Job? = null
    private var pendingSettings: (suspend () -> Unit)? = null
    private var earlyScopeResult: Pair<String, String>? = null

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
                val consumedSequence = savedState.get<Long>("searchConsumedSequence") ?: -1L
                revision = maxOf(revision, restored.revision, consumedSequence)
                val retainedReceipts = restored.effects.filter { it.sequence > consumedSequence }
                val draft =
                    restored.copy(
                        scope = if (restored.revision == 0L) settings.scope else restored.scope,
                        effects = retainedReceipts,
                    )
                mutableState.value =
                    state.value.copy(
                        loading = false,
                        draft = draft,
                        preferences = settings,
                        durableRevision = restored.revision,
                    )
                observeMetadata()
                observeQuery(draft.query)
                if (draft.pendingScopeRequest != null) applyScopeResult(restored = true)
                else acceptEarlyScopeResult()
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
                if (saveHistory) {
                    try {
                        metadata.saveHistory(query)
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!stopped && generation == commandGeneration)
                            mutableState.value =
                                state.value.copy(metadataError = error.message ?: error.toString())
                    }
                }
                // The legacy history write was independent of the actual search request.
                checkpoint()
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    fun togglePrecision() {
        val target = !state.value.preferences.precision
        runSettings {
            val settings = preferences.precision(target)
            currentCoroutineContext().ensureActive()
            if (stopped) return@runSettings
            mutableState.value = state.value.copy(preferences = settings)
            editQuery(state.value.draft.query.trim())
            submit()
        }
    }

    fun toggleReadRecords() {
        val target = !state.value.preferences.showReadRecord
        runSettings {
            val settings = preferences.showReadRecord(target)
            currentCoroutineContext().ensureActive()
            if (!stopped) mutableState.value = state.value.copy(preferences = settings)
        }
    }

    fun confirmFilter() {
        val original = state.value.draft.filterDraft ?: return
        runSettings {
            val settings = preferences.resultFilter(original.trim())
            currentCoroutineContext().ensureActive()
            if (stopped) return@runSettings
            mutableState.value = state.value.copy(preferences = settings)
            if (state.value.draft.filterDraft == original) dismissFilter()
            checkpoint()
        }
    }

    fun selectScope(value: String, save: Boolean = true) {
        if (!usable() || state.value.settingsBusy) return
        val shouldSearch = !state.value.draft.inputHelp
        updateDraft { it.copy(scope = value) }
        runSettings {
            if (save) {
                val settings = preferences.scope(value)
                currentCoroutineContext().ensureActive()
                if (stopped) return@runSettings
                mutableState.value = state.value.copy(preferences = settings)
            }
            checkpoint()
            if (shouldSearch && !stopped) {
                editQuery(state.value.draft.query.trim())
                submit()
            }
        }
    }

    fun selectGroup(name: String) {
        val selection = BookSearchScopeSelection(state.value.draft.scope)
        if (name in selection.names) selectScope(selection.remove(name).value, save = false)
        else selectScope(name)
    }

    fun validateScopeMenu() {
        if (!usable()) return
        val selection = BookSearchScopeSelection(state.value.draft.scope)
        if (!selection.hasCheckedChoice(state.value.groups)) selectScope("")
    }

    fun dismissEmptyScope() {
        if (!usable()) return
        updateDraft { it.copy(emptyScopeConfirmation = false) }
    }

    fun confirmEmptyScope() {
        if (!usable() || !state.value.draft.emptyScopeConfirmation) return
        if (state.value.preferences.precision) {
            runSettings {
                val settings = preferences.precision(false)
                currentCoroutineContext().ensureActive()
                if (stopped) return@runSettings
                mutableState.value = state.value.copy(preferences = settings)
                startQuery(state.value.draft.query, saveHistory = false)
            }
        } else selectScope("")
    }

    private fun runSettings(operation: suspend () -> Unit) {
        if (!usable() || state.value.settingsBusy) return
        pendingSettings = operation
        launchSettings(operation)
    }

    private fun launchSettings(operation: suspend () -> Unit) {
        mutableState.value = state.value.copy(settingsBusy = true, settingsError = null)
        settingsJob = viewModelScope.launch {
            try {
                operation()
                currentCoroutineContext().ensureActive()
                if (!stopped) pendingSettings = null
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(settingsError = error.message ?: error.toString())
            } finally {
                if (!stopped) {
                    mutableState.value = state.value.copy(settingsBusy = false)
                    if (state.value.settingsError == null) acceptEarlyScopeResult()
                }
            }
        }
    }

    fun openBook(resultId: String) {
        val result = state.value.draft.results.find { it.id == resultId } ?: return
        enqueueReceipt(
            BookSearchReceipt(
                id = UUID.randomUUID().toString(),
                effect = BookSearchEffect.BookInfo,
                resultId = result.id,
                bookId = result.bookUrl,
                name = result.name,
                author = result.author,
            )
        )
    }

    fun openSuggestion(bookId: String) {
        val suggestion = state.value.suggestions.find { it.bookId == bookId } ?: return
        enqueueReceipt(
            BookSearchReceipt(
                id = UUID.randomUUID().toString(),
                effect = BookSearchEffect.BookInfo,
                bookId = suggestion.bookId,
                name = suggestion.name,
                author = suggestion.author,
            )
        )
    }

    fun openScope() {
        if (savedState.get<String>("searchScopeRequest") != null) return
        enqueueReceipt(
            BookSearchReceipt(
                id = UUID.randomUUID().toString(),
                effect = BookSearchEffect.Scope,
                text = state.value.draft.scope,
            )
        )
    }

    fun openSources() {
        enqueueReceipt(BookSearchReceipt(UUID.randomUUID().toString(), BookSearchEffect.Sources))
    }

    fun openLog() {
        enqueueReceipt(BookSearchReceipt(UUID.randomUUID().toString(), BookSearchEffect.Log))
    }

    private fun enqueueReceipt(receipt: BookSearchReceipt) {
        if (!usable() || state.value.draft.effects.isNotEmpty()) return
        updateDraft { it.copy(effects = listOf(receipt.copy(sequence = revision + 1))) }
    }

    /** Host invokes this only after RESUMED and any cancellable IO preparation has completed. */
    fun consumeReceipt(id: String): BookSearchReceipt? {
        if (!usable() || state.value.durableRevision < state.value.draft.revision) return null
        val receipt = state.value.draft.effects.firstOrNull()?.takeIf { it.id == id } ?: return null
        // Persist a small consumption fence before starting a platform action.
        savedState["searchConsumedSequence"] = receipt.sequence
        if (receipt.effect == BookSearchEffect.Scope) savedState["searchScopeRequest"] = receipt.id
        updateDraft { it.copy(effects = emptyList()) }
        return receipt
    }

    fun scopeRequest(): String? = savedState["searchScopeRequest"]

    fun scopeDismissed(request: String) {
        if (earlyScopeResult?.first == request || state.value.draft.pendingScopeRequest == request)
            return
        if (scopeRequest() == request) savedState.remove<String>("searchScopeRequest")
    }

    fun scopeSelected(request: String, scope: String) {
        if (scopeRequest() != request) return
        earlyScopeResult = request to scope
        acceptEarlyScopeResult()
    }

    private fun acceptEarlyScopeResult() {
        if (!usable() || state.value.settingsBusy) return
        val result = earlyScopeResult ?: return
        if (scopeRequest() != result.first) {
            earlyScopeResult = null
            return
        }
        earlyScopeResult = null
        updateDraft {
            it.copy(pendingScopeRequest = result.first, pendingScopeValue = result.second)
        }
        applyScopeResult(restored = false)
    }

    private fun applyScopeResult(restored: Boolean) {
        val request = state.value.draft.pendingScopeRequest ?: return
        val value = state.value.draft.pendingScopeValue ?: return
        val shouldSearch = !restored && !state.value.draft.inputHelp
        runSettings {
            // Accept the full result privately before clearing its small platform request ticket.
            checkpoint()
            val settings = preferences.scope(value)
            currentCoroutineContext().ensureActive()
            if (stopped) return@runSettings
            mutableState.value = state.value.copy(preferences = settings)
            updateDraft {
                it.copy(scope = value, pendingScopeRequest = null, pendingScopeValue = null)
            }
            checkpoint()
            scopeDismissed(request)
            if (shouldSearch) {
                editQuery(state.value.draft.query.trim())
                submit()
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
            pendingSettings?.let { operation ->
                if (!state.value.settingsBusy) launchSettings(operation)
            }
            if (pendingSettings == null && state.value.draft.pendingScopeRequest != null)
                applyScopeResult(restored = true)
            acceptEarlyScopeResult()
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
        settingsJob?.cancel()
        pendingSettings = null
        engine?.let { searchEngine -> cleanupScope.launch { searchEngine.close() } }
        loadJob?.cancel()
        historyJob?.cancel()
        suggestionsJob?.cancel()
        viewModelScope.coroutineContext.cancelChildren()
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
