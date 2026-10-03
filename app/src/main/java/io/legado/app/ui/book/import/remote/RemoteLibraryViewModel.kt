package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class RemoteLibraryState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val draft: RemoteLibraryDraft? = null, val connection: RemoteLibraryConnection? = null, val error: String? = null,
    val writeFailed: Boolean = false, val interrupted: Boolean = false) {
    val visible: List<RemoteLibraryEntry> get() = draft?.let { projectRemoteLibrary(it.rows, it.query, it.sort, it.ascending) }.orEmpty()
    val visibleSelection: List<String> get() { val selected = draft?.selected.orEmpty().toHashSet(); return visible.filter { it.checkable && it.id in selected }.map { it.id } }
    val checkableCount: Int get() = visible.count { it.checkable }
    val path: String get() = (if (connection?.defaultServer == true) "books/" else "/") + draft?.directories.orEmpty().joinToString("") { it.name + "/" }
}
/** Only an opaque UUID and consumed effect ownership enter SavedState. */
internal class RemoteLibraryViewModel(private val repository: RemoteLibraryRepository,
    private val reading: RemoteLibraryReadingRepository, private val drafts: RemoteLibraryDraftRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("remoteLibrarySession") ?: UUID.randomUUID().toString().also { saved["remoteLibrarySession"] = it }
    private val mutable = MutableStateFlow(RemoteLibraryState()); val state = mutable.asStateFlow()
    private var current = RemoteLibraryDraft(); private var initialized = false; private var stopped = false
    private var revision = 0L; private var generation = 0; private var loading: Job? = null
    private val gate = Mutex(); private val changes = MutableStateFlow<RemoteLibraryDraft?>(null)
    private val writer = viewModelScope.launch { changes.filterNotNull().collect { value ->
        try { persist(value); currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = false) }
        catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = true, error = error.localizedMessage.orEmpty()) }
    } }
    init { initialize() }
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private suspend fun persist(value: RemoteLibraryDraft) = gate.withLock { drafts.write(session, value) }
    private fun update(value: RemoteLibraryDraft) { current = value.copy(revision = nextRevision()); mutable.value = state.value.copy(draft = current); changes.value = current }
    private fun usable() = initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.writeFailed && !state.value.busy && current.task == null
    private fun initialize() {
        loading?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        loading = viewModelScope.launch {
            try {
                if (!initialized) {
                    val restored = drafts.open(session); currentCoroutineContext().ensureActive()
                    if (stopped || token != generation) return@launch
                    revision = maxOf(revision, restored.revision); current = restored; initialized = true
                }
                val consumed = saved.get<String>("remoteLibraryConsumed"); val index = current.effects.indexOfFirst { it.id == consumed }
                if (index >= 0) current = current.copy(revision = nextRevision(), effects = current.effects.drop(index + 1))
                persist(current); currentCoroutineContext().ensureActive()
                if (stopped || token != generation) return@launch
                mutable.value = state.value.copy(draft = current, interrupted = current.task != null)
                if (!reading.storageConfigured()) {
                    val help = reading.storageHelp(); currentCoroutineContext().ensureActive()
                    if (stopped || token != generation) return@launch
                    if (current.storageTicket == null) update(current.copy(confirmation = RemoteLibraryConfirmation(RemoteLibraryPrompt.StorageHelp, help = help)))
                    mutable.value = state.value.copy(loading = false)
                } else {
                    if (current.confirmation?.kind == RemoteLibraryPrompt.StorageHelp) update(current.copy(confirmation = null))
                    loadInside(token, reconnect = state.value.connection == null)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    private suspend fun loadInside(token: Int, reconnect: Boolean) {
        val connection = if (reconnect) repository.connect() else requireNotNull(state.value.connection)
        currentCoroutineContext().ensureActive(); if (stopped || token != generation) return
        val rows = repository.list(connection, current.directories.lastOrNull()?.path)
        currentCoroutineContext().ensureActive(); if (stopped || token != generation) return
        update(current.copy(rows = rows.toList())); mutable.value = state.value.copy(loading = false, failed = false, connection = connection)
    }
    fun refresh(reconnect: Boolean = false) {
        if (!usable()) return
        loading?.cancel(); val token = ++generation
        update(current.copy(rows = emptyList(), selected = emptyList())); mutable.value = state.value.copy(loading = true, error = null)
        loading = viewModelScope.launch {
            try { loadInside(token, reconnect || state.value.connection == null) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun query(value: String) { if (usable()) update(current.copy(query = value)) }
    fun sort(value: RemoteLibrarySort) { if (usable()) { update(current.copy(sort = value, ascending = if (current.sort == value) !current.ascending else true)); refresh() } }
    fun openDirectory(id: String) {
        if (!usable()) return
        current.rows.firstOrNull { it.id == id && it.directory }?.let { update(current.copy(directories = current.directories + it)); refresh() }
    }
    fun goBackDirectory(): Boolean {
        if (!usable()) return true
        if (current.directories.isEmpty()) return false
        update(current.copy(directories = current.directories.dropLast(1))); refresh(); return true
    }
    fun toggle(id: String) { if (usable() && state.value.visible.any { it.id == id && it.checkable })
        update(current.copy(selected = current.selected.toMutableSet().apply { if (!remove(id)) add(id) }.toList())) }
    fun selectAll(value: Boolean) { if (usable()) update(current.copy(selected = if (value) (current.selected + state.value.visible.filter { it.checkable }.map { it.id }).distinct() else emptyList())) }
    fun inverse() { if (usable()) { val selected = current.selected.toMutableSet(); state.value.visible.filter { it.checkable }.forEach { if (!selected.remove(it.id)) selected.add(it.id) }; update(current.copy(selected = selected.toList())) } }
    fun consumeEffect(id: String): Boolean {
        if (!usable() || current.effects.firstOrNull()?.id != id) return false
        saved["remoteLibraryConsumed"] = id; update(current.copy(effects = current.effects.drop(1))); return true
    }
    fun retry() { if (stopped) return; if (state.value.writeFailed) update(current) else initialize() }
    suspend fun flush() { if (initialized) persist(current) }
    fun stop() { if (!stopped) { stopped = true; generation++; loading?.cancel(); writer.cancel() } }
    suspend fun release() { repository.close(); drafts.release(session) }
    override fun onCleared() { stop(); super.onCleared() }
}
