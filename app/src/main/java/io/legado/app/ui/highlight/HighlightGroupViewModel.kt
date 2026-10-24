package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.HighlightGroupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal enum class HighlightGroupStage {
    None,
    Rename,
    Delete,
    Move,
}

internal data class HighlightGroupState(
    val groups: List<String> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val stage: HighlightGroupStage = HighlightGroupStage.None,
    val source: String? = null,
    val name: String = "",
    val nameStart: Int = name.length,
    val nameEnd: Int = name.length,
    val refresh: Boolean = false,
)

internal class HighlightGroupViewModel(
    private val repository: HighlightGroupRepository,
    private val saved: SavedStateHandle,
    private val drafts: HighlightGroupDraftStore = MemoryHighlightGroupDraftStore,
) : ViewModel() {
    val ticket: String = saved.get<String>("highlight.groups.ticket") ?: UUID.randomUUID().toString().also { saved["highlight.groups.ticket"] = it }
    private val mutable = MutableStateFlow(HighlightGroupState())
    private val writes = Mutex()
    private var ready = false
    private var draftRevision = 0L
    private var writtenRevision = -1L
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var loadJob: Job? = null
    private var wantsObservation = true
    private var mutation: Job? = null
    private var stopped = false

    init { load() }
    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val restored = drafts.read(ticket) ?: HighlightGroupDraft()
                if (stopped) return@launch
                mutable.value = state.value.copy(stage = restored.stage, source = restored.source, name = restored.name,
                    nameStart = restored.start.coerceIn(0, restored.name.length), nameEnd = restored.end.coerceIn(0, restored.name.length), refresh = restored.refresh)
                draftRevision = restored.revision
                writtenRevision = restored.revision
                ready = true
                if (wantsObservation) observe()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(loading = false, error = failure.localizedMessage.orEmpty())
            }
        }
    }

    private fun persist() {
        draftRevision++
        viewModelScope.launch { try { flush() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(error = failure.localizedMessage.orEmpty())
        } }
    }
    suspend fun flush() = writes.withLock {
        if (!ready || stopped || writtenRevision == draftRevision) return@withLock
        val s = state.value; val version = draftRevision
        drafts.write(ticket, HighlightGroupDraft(s.stage, s.source, s.name, s.nameStart, s.nameEnd, s.refresh, version))
        writtenRevision = version
    }
    fun close() {
        if (state.value.busy || state.value.refresh) return
        stop()
        viewModelScope.launch(NonCancellable) { runCatching { writes.withLock { drafts.release(ticket) } } }
    }

    fun observe() {
        wantsObservation = true
        if (stopped || !ready || observation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try {
                repository.groups().collect { groups ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped)
                        mutable.value =
                            state.value.copy(groups = groups.distinct().toList(), loading = false)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value =
                        state.value.copy(
                            loading = false,
                            error = failure.localizedMessage.orEmpty(),
                        )
            }
        }
    }

    fun pauseObservation() {
        wantsObservation = false
        observation?.cancel()
        observation = null
    }

    fun retry() {
        if (!ready) { load(); return }
        pauseObservation()
        observe()
    }

    fun rename(source: String) = open(source, HighlightGroupStage.Rename)

    fun delete(source: String) = open(source, HighlightGroupStage.Delete)

    private fun open(source: String, stage: HighlightGroupStage) {
        if (stopped || state.value.loading || state.value.busy || source !in state.value.groups)
            return
        mutable.value =
            state.value.copy(source = source, stage = stage, name = source, nameStart = source.length, nameEnd = source.length, error = null)
        persist()
    }

    fun name(value: String) = rawName(value, value.length, value.length)

    fun rawName(value: String, start: Int, end: Int) {
        require(start in 0..value.length && end in 0..value.length)
        if (stopped || state.value.busy || state.value.stage != HighlightGroupStage.Rename) return
        mutable.value = state.value.copy(name = value, nameStart = start, nameEnd = end)
        persist()
    }

    fun cancel() {
        if (stopped || state.value.busy) return
        clearDialog()
    }

    private fun clearDialog() {
        mutable.value = state.value.copy(stage = HighlightGroupStage.None, source = null, name = "", nameStart = 0, nameEnd = 0)
        persist()
    }

    fun confirmRename() {
        val current = state.value
        val source = current.source ?: return
        if (current.stage != HighlightGroupStage.Rename) return
        val name = current.name.trim()
        if (name.isEmpty()) {
            cancel()
            return
        }
        mutate(false) { repository.rename(source, name) }
    }

    fun confirmDelete() {
        val current = state.value
        val source = current.source ?: return
        if (current.stage == HighlightGroupStage.Delete) mutate(true) { repository.delete(source) }
    }

    fun chooseMove() {
        if (stopped || state.value.busy || state.value.stage != HighlightGroupStage.Delete) return
        mutable.value = state.value.copy(stage = HighlightGroupStage.Move)
        persist()
    }

    fun move(target: String?) {
        val current = state.value
        val source = current.source ?: return
        if (
            current.stage != HighlightGroupStage.Move ||
                target == source ||
                (target != null && target !in current.groups)
        )
            return
        mutate(true) { repository.move(source, target) }
    }

    private fun mutate(refresh: Boolean, action: suspend () -> Unit) {
        if (stopped || state.value.busy || state.value.loading) return
        mutable.value = state.value.copy(busy = true, error = null)
        mutation = viewModelScope.launch {
            try {
                action()
                currentCoroutineContext().ensureActive()
                if (!stopped) {
                    clearDialog()
                    if (refresh) {
                        mutable.value = state.value.copy(refresh = true)
                        persist()
                    }
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value = state.value.copy(error = failure.localizedMessage.orEmpty())
            } finally {
                if (!stopped && currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(busy = false)
            }
        }
    }

    /** Persist one reader-refresh claim and roll it back if PAUSE wins during IO. */
    suspend fun claimRefresh(canDeliver: () -> Boolean): Boolean {
        flush()
        if (stopped || !state.value.refresh || state.value.busy) return false
        mutable.value = state.value.copy(busy = true)
        try {
            return writes.withLock {
                currentCoroutineContext().ensureActive()
                if (stopped || !state.value.refresh || !canDeliver()) return@withLock false
                val before = state.value
                val claimedRevision = draftRevision + 1
                val next = HighlightGroupDraft(before.stage, before.source, before.name, before.nameStart, before.nameEnd, false, claimedRevision)
                withContext(NonCancellable) { drafts.write(ticket, next) }
                if (!currentCoroutineContext().isActive || !canDeliver() || stopped) {
                    val rollbackRevision = claimedRevision + 1
                    withContext(NonCancellable) { drafts.write(ticket, next.copy(refresh = true, revision = rollbackRevision)) }
                    draftRevision = rollbackRevision; writtenRevision = rollbackRevision
                    currentCoroutineContext().ensureActive()
                    return@withLock false
                }
                draftRevision = claimedRevision; writtenRevision = claimedRevision
                mutable.value = state.value.copy(refresh = false)
                true
            }
        } finally { if (!stopped) mutable.value = state.value.copy(busy = false) }
    }

    fun consumeRefresh() {
        if (stopped || !state.value.refresh) return
        mutable.value = state.value.copy(refresh = false)
        persist()
    }

    fun stop() {
        stopped = true
        loadJob?.cancel()
        pauseObservation()
        mutation?.cancel()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
