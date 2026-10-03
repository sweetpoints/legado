package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookshelfManagementDraftRepository
import io.legado.app.data.repository.BookshelfManagementRepository
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class BookshelfManagementState(val loading: Boolean = true, val failed: Boolean = false,
    val snapshot: ManagedShelfSnapshot? = null, val draft: BookshelfManagementDraft? = null, val error: String? = null,
    val writeFailed: Boolean = false, val selecting: Boolean = false) {
    val visibleSelection: List<String> get() = snapshot?.books.orEmpty().map { it.id }.filter { it in draft?.selected.orEmpty() }
}
/** The saved Bundle contains one opaque session ID; selection URLs and queries stay on disk. */
internal class BookshelfManagementViewModel(private val repository: BookshelfManagementRepository,
    private val drafts: BookshelfManagementDraftRepository, private val saved: SavedStateHandle,
    private val initialGroup: Long = -1L) : ViewModel() {
    val session = saved.get<String>("shelfManageSession") ?: UUID.randomUUID().toString().also { saved["shelfManageSession"] = it }
    private val mutable = MutableStateFlow(BookshelfManagementState()); val state = mutable.asStateFlow()
    private var current = BookshelfManagementDraft(); private var initialized = false; private var stopped = false
    private var revision = 0L; private var generation = 0; private var loading: Job? = null; private var observing: Job? = null
    private var gesture: Set<String>? = null; private var gestureIds: List<String> = emptyList()
    private val gate = Mutex(); private val updates = MutableStateFlow<BookshelfManagementDraft?>(null)
    private val writer = viewModelScope.launch { updates.filterNotNull().collect { value ->
        try { persist(value); currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = false) }
        catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = true, error = error.localizedMessage.orEmpty()) }
    } }
    init { initialize() }
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun usable() = initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.writeFailed && current.operation == null
    private suspend fun persist(value: BookshelfManagementDraft) = gate.withLock { drafts.write(session, value) }
    private fun update(value: BookshelfManagementDraft, persist: Boolean = true) {
        current = value.copy(revision = nextRevision()); mutable.value = state.value.copy(draft = current)
        if (persist) updates.value = current
    }
    private fun initialize() {
        loading?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        loading = viewModelScope.launch {
            try {
                if (!initialized) {
                    val restored = drafts.open(session); currentCoroutineContext().ensureActive()
                    if (stopped || generation != token) return@launch
                    revision = maxOf(revision, restored.revision)
                    current = if (restored.revision == 0L) restored.copy(groupId = initialGroup, revision = nextRevision()) else restored
                    initialized = true
                }
                persist(current); currentCoroutineContext().ensureActive()
                if (!stopped && generation == token) { mutable.value = state.value.copy(draft = current); observe() }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && generation == token) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    private fun observe() {
        observing?.cancel(); val token = ++generation
        val group = current.groupId; val query = current.query
        observing = viewModelScope.launch {
            try { repository.observe(group, query).collect { value ->
                currentCoroutineContext().ensureActive()
                if (!stopped && generation == token) {
                    if (gesture != null && gestureIds != value.books.map { it.id }) endSelectionGesture(cancel = true)
                    mutable.value = state.value.copy(loading = false, failed = false, snapshot = value, draft = current)
                }
            } } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && generation == token) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun query(value: String) { if (usable() && gesture == null) { update(current.copy(query = value)); observe() } }
    fun group(id: Long) { if (usable() && gesture == null) { update(current.copy(groupId = id)); observe() } }
    fun toggle(id: String) { if (usable() && gesture == null && state.value.snapshot?.books?.any { it.id == id } == true) {
        update(current.copy(selected = current.selected.toMutableSet().apply { if (!remove(id)) add(id) }.toList()))
    } }
    fun selectAll(selected: Boolean) { if (usable() && gesture == null) update(current.copy(selected = if (selected)
        (current.selected + state.value.snapshot?.books.orEmpty().map { it.id }).distinct() else emptyList())) }
    fun inverse() { if (usable() && gesture == null) {
        val selected = current.selected.toMutableSet(); state.value.snapshot?.books.orEmpty().forEach { if (!selected.remove(it.id)) selected.add(it.id) }
        update(current.copy(selected = selected.toList()))
    } }
    fun selectInterval() { if (usable() && gesture == null) {
        val ids = state.value.snapshot?.books.orEmpty().map { it.id }; val selected = ids.indices.filter { ids[it] in current.selected }
        if (selected.isNotEmpty()) update(current.copy(selected = (current.selected + ids.subList(selected.first(), selected.last() + 1)).distinct()))
    } }
    fun beginSelectionGesture(): Boolean {
        if (!usable() || gesture != null) return false
        gesture = current.selected.toSet(); gestureIds = state.value.snapshot?.books.orEmpty().map { it.id }
        mutable.value = state.value.copy(selecting = true); return true
    }
    /** Toggle-and-reverse always evaluates against the gesture's original set, not prior frames. */
    fun selectionRange(start: Int, end: Int) {
        val original = gesture ?: return
        if (start !in gestureIds.indices || end !in gestureIds.indices) return
        val selected = original.toMutableSet()
        for (index in minOf(start, end)..maxOf(start, end)) { val id = gestureIds[index]; if (!selected.remove(id)) selected.add(id) }
        current = current.copy(selected = selected.toList()); mutable.value = state.value.copy(draft = current)
    }
    fun endSelectionGesture(cancel: Boolean = false) {
        val original = gesture ?: return
        gesture = null; gestureIds = emptyList()
        val next = if (cancel) current.copy(selected = original.toList()) else current
        update(next); mutable.value = state.value.copy(selecting = false)
    }
    fun retry() { if (stopped) return
        if (state.value.failed) initialize()
        else if (state.value.writeFailed) update(current)
    }
    suspend fun flush() { if (initialized) persist(current.copy(selected = gesture?.toList() ?: current.selected)) }
    fun stop() { if (!stopped) { stopped = true; generation++; loading?.cancel(); observing?.cancel(); writer.cancel() } }
    suspend fun release() { drafts.release(session) }
    override fun onCleared() { stop(); super.onCleared() }
}
