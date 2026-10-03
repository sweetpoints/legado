package io.legado.app.ui.book.audio.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.AudioSkipCreditsDraft
import io.legado.app.data.repository.AudioSkipCreditsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AudioSkipCreditsState(val loading: Boolean = true, val draft: AudioSkipCreditsDraft? = null,
    val saving: Boolean = false, val scopeLoading: Boolean = false, val closeFailed: Boolean = false, val error: String? = null, val loadFailed: Boolean = false, val finished: Boolean = false)
internal class AudioSkipCreditsViewModel(private val repository: AudioSkipCreditsRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(AudioSkipCreditsState())
    val state = mutable.asStateFlow()
    private data class Write(val draft: AudioSkipCreditsDraft, val revision: Long, val globals: Boolean)
    private val writes = Channel<Write>(Channel.CONFLATED)
    private var globalDirty = saved.get<Boolean>("globalDirty") == true
    private var revision = 0L; private var stopped = false; private var load: Job? = null; private var close: Job? = null; private var writer: Job? = null; private var scopeJob: Job? = null; private var scopeGeneration = 0L
    init {
        writer = viewModelScope.launch {
            for (value in writes) {
                try { repository.write(value.draft, value.revision, value.globals, false); currentCoroutineContext().ensureActive()
                    if (!stopped && revision == value.revision) { globalDirty = false; saved["globalDirty"] = false; mutable.value = state.value.copy(error = null) }
                } catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && revision == value.revision) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            }
        }
        initialize()
    }
    private fun initialize() {
        if (stopped) return
        load?.cancel(); mutable.value = state.value.copy(loading = true, loadFailed = false, error = null)
        load = viewModelScope.launch {
            try {
                val loaded = repository.load(); currentCoroutineContext().ensureActive()
                val draft = if (saved.get<Boolean>("draft") == true) AudioSkipCreditsDraft(saved["scope"] ?: loaded.useGlobal,
                    saved["bookOpen"] ?: loaded.bookOpen, saved["bookClose"] ?: loaded.bookClose,
                    if (globalDirty) saved["globalOpen"] ?: loaded.globalOpen else loaded.globalOpen,
                    if (globalDirty) saved["globalClose"] ?: loaded.globalClose else loaded.globalClose) else loaded
                if (stopped) return@launch
                mutable.value = state.value.copy(loading = false, draft = draft, finished = saved.get<Boolean>("finished") == true)
                if (globalDirty && !state.value.finished) queue(draft)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    private fun nextRevision(): Long { revision = maxOf(revision + 1, System.nanoTime()); return revision }
    private fun remember(draft: AudioSkipCreditsDraft) {
        saved["draft"] = true; saved["scope"] = draft.useGlobal; saved["bookOpen"] = draft.bookOpen; saved["bookClose"] = draft.bookClose
        saved["globalOpen"] = draft.globalOpen; saved["globalClose"] = draft.globalClose; saved["globalDirty"] = globalDirty
    }
    private fun queue(draft: AudioSkipCreditsDraft) { writes.trySend(Write(draft, nextRevision(), globalDirty)) }
    private fun update(globalsFromUser: Boolean = true, transform: (AudioSkipCreditsDraft) -> AudioSkipCreditsDraft) {
        val previous = state.value.draft ?: return
        if (stopped || state.value.loading || state.value.saving || state.value.finished) return
        val draft = transform(previous); if (draft == previous) return
        globalDirty = globalDirty || globalsFromUser && (previous.globalOpen != draft.globalOpen || previous.globalClose != draft.globalClose)
        remember(draft); mutable.value = state.value.copy(draft = draft, error = null); queue(draft)
    }
    fun scope(global: Boolean) {
        val snapshot = state.value.draft ?: return
        if (stopped || state.value.loading || state.value.saving || state.value.finished) return
        val token = ++scopeGeneration; scopeJob?.cancel()
        mutable.value = state.value.copy(scopeLoading = true, error = null)
        scopeJob = viewModelScope.launch {
            try {
                if (globalDirty) {
                    repository.write(snapshot, nextRevision(), true, false); currentCoroutineContext().ensureActive()
                    val current = state.value.draft
                    if (current != null && current.globalOpen == snapshot.globalOpen && current.globalClose == snapshot.globalClose) { globalDirty = false; saved["globalDirty"] = false }
                }
                val loaded = repository.load(); currentCoroutineContext().ensureActive()
                if (stopped || token != scopeGeneration) return@launch
                update(globalsFromUser = false) { latest ->
                    val refreshed = if (latest.globalOpen != snapshot.globalOpen || latest.globalClose != snapshot.globalClose) latest
                        else latest.copy(globalOpen = loaded.globalOpen, globalClose = loaded.globalClose)
                    refreshed.scope(global)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == scopeGeneration) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            finally { if (!stopped && token == scopeGeneration && currentCoroutineContext().isActive) mutable.value = state.value.copy(scopeLoading = false) }
        }
    }
    fun opening(seconds: Int) = update { it.opening(seconds) }
    fun closing(seconds: Int) = update { it.closing(seconds) }
    fun retry() {
        if (state.value.loadFailed) initialize() else if (state.value.closeFailed) requestClose() else state.value.draft?.let { queue(it) }
    }
    /** Enter the bounded durable write immediately, also when native cancel removes the Fragment owner. */
    fun requestClose() {
        if (stopped || state.value.saving || state.value.finished) return
        val draft = state.value.draft
        load?.cancel(); scopeGeneration++; scopeJob?.cancel()
        if (draft == null) { mutable.value = state.value.copy(loading = false, finished = true); saved["finished"] = true; return }
        val token = nextRevision(); val globals = globalDirty
        mutable.value = state.value.copy(saving = true, error = null, closeFailed = false, scopeLoading = false)
        close = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { repository.write(draft, token, globals, true) }
                currentCoroutineContext().ensureActive()
                if (!stopped) { globalDirty = false; saved["globalDirty"] = false; saved["finished"] = true
                    mutable.value = state.value.copy(saving = false, finished = true) }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(saving = false, closeFailed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun stop() { stopped = true; scopeGeneration++; scopeJob?.cancel(); load?.cancel(); close?.cancel(); writer?.cancel(); writes.close() }
    override fun onCleared() { stop(); super.onCleared() }
}
