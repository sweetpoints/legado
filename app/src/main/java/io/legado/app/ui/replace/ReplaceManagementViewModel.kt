package io.legado.app.ui.replace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class ReplaceManagementDialog { Delete, AddGroup, RemoveGroup }
data class ReplaceManagementLabels(val enabled: String, val disabled: String, val noGroup: String)
data class ReplaceManagementState(val loaded: Boolean = false, val busy: Boolean = false,
    val rows: List<ReplaceManagementRow> = emptyList(), val groups: List<String> = emptyList(),
    val query: String = "", val queryStart: Int = 0, val queryEnd: Int = 0, val selected: Set<Long> = emptySet(),
    val dialog: ReplaceManagementDialog? = null, val targets: List<Long> = emptyList(),
    val draft: String = "", val draftStart: Int = 0, val draftEnd: Int = 0,
    val manual: Boolean = false, val changed: Boolean = false, val dragging: Long? = null,
    val error: String? = null, val scrollIndex: Int = 0, val scrollOffset: Int = 0) {
    val visibleSelection get() = rows.filter { it.id in selected }.map { it.id }
}

/** Gesture previews are transient. Only completed selection and modal edits become disk drafts. */
class ReplaceManagementViewModel(private val repository: ReplaceManagementRepository,
    private val sessions: ReplaceManagementSessionRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val id = saved.get<String>("replaceManagement.session") ?: UUID.randomUUID().toString().also { saved["replaceManagement.session"] = it }
    private val mutable = MutableStateFlow(ReplaceManagementState(changed = saved.get<Boolean>("replaceManagement.changed") == true,
        scrollIndex = saved.get<Int>("replaceManagement.scrollIndex") ?: 0, scrollOffset = saved.get<Int>("replaceManagement.scrollOffset") ?: 0))
    val state = mutable.asStateFlow()
    private var checkpoint = ReplaceManagementCheckpoint()
    private var revision = saved.get<Long>("replaceManagement.revision") ?: 0L
    private var labels: ReplaceManagementLabels? = null
    private var epoch = 0L
    private var loading: Job? = null
    private var rowsJob: Job? = null
    private var groupsJob: Job? = null
    private var operation: Job? = null
    private var selecting: Set<Long>? = null
    private var dragRows: List<ReplaceManagementRow>? = null
    private var buffered: List<ReplaceManagementRow>? = null
    private var target: Pair<Long, Boolean>? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun bind(value: ReplaceManagementLabels) {
        if (state.value.loaded) { if (labels != value) { labels = value; observe() }; return }
        labels = value; if (loading?.isActive == true) return
        val generation = epoch
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(id); currentCoroutineContext().ensureActive(); if (generation != epoch) return@launch
                revision = maxOf(revision, disk?.revision ?: 0); checkpoint = disk ?: checkpoint
                val manual = repository.manual(); currentCoroutineContext().ensureActive(); if (generation != epoch) return@launch
                project(); mutable.value = state.value.copy(manual = manual); observe(); observeGroups()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch) failed(error) }
        }
    }
    private fun project() {
        mutable.value = state.value.copy(loaded = true, query = checkpoint.query, queryStart = checkpoint.queryStart,
            queryEnd = checkpoint.queryEnd, selected = checkpoint.selected.toSet(), targets = checkpoint.targets,
            dialog = checkpoint.dialog?.let { runCatching { ReplaceManagementDialog.valueOf(it) }.getOrNull() },
            draft = checkpoint.draft, draftStart = checkpoint.draftStart, draftEnd = checkpoint.draftEnd)
    }
    private fun observe() {
        rowsJob?.cancel(); val generation = epoch; val values = checkNotNull(labels)
        val filter = replaceManagementFilter(checkpoint.query, values.enabled, values.disabled, values.noGroup)
        rowsJob = viewModelScope.launch {
            var first = true
            try { repository.rows(filter).collect { rows ->
                currentCoroutineContext().ensureActive(); if (generation != epoch) return@collect
                if (!first) changed(); first = false
                if (selecting != null || dragRows != null) buffered = rows else mutable.value = state.value.copy(rows = rows)
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch) failed(error) }
        }
    }
    private fun observeGroups() {
        groupsJob?.cancel(); val generation = epoch
        groupsJob = viewModelScope.launch {
            try { repository.groups().collect { values -> currentCoroutineContext().ensureActive(); if (generation == epoch) mutable.value = state.value.copy(groups = values) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch) failed(error) }
        }
    }
    private fun editable() = state.value.loaded && !state.value.busy
    private fun persist() {
        revision++; saved["replaceManagement.revision"] = revision; checkpoint = checkpoint.copy(revision = revision)
        val value = checkpoint; val generation = epoch
        viewModelScope.launch {
            try { sessions.write(id, value); currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch && revision == value.revision) failed(error) }
        }
    }
    private fun failed(error: Exception) { mutable.value = state.value.copy(error = error.localizedMessage ?: error.javaClass.simpleName) }
    fun changed() { saved["replaceManagement.changed"] = true; mutable.value = state.value.copy(changed = true) }
    fun query(text: String, start: Int = text.length, end: Int = start) {
        if (!editable()) return
        cancelGesture(); val changed = text != checkpoint.query
        checkpoint = checkpoint.copy(query = text, queryStart = start.coerceIn(0, text.length), queryEnd = end.coerceIn(0, text.length))
        project(); persist(); if (changed) observe()
    }
    fun selected(id: Long, checked: Boolean) {
        if (!editable() || state.value.rows.none { it.id == id }) return
        val values = checkpoint.selected.toMutableSet(); if (checked) values.add(id) else values.remove(id)
        selection(values)
    }
    private fun selection(values: Set<Long>) { if (!editable()) return; cancelGesture(); checkpoint = checkpoint.copy(selected = values.toList()); project(); persist() }
    fun selectAll() = selection(checkpoint.selected.toSet() + state.value.rows.map { it.id })
    fun invertSelection() {
        val values = checkpoint.selected.toMutableSet(); state.value.rows.forEach { if (!values.add(it.id)) values.remove(it.id) }; selection(values)
    }
    fun selectInterval() {
        val indices = state.value.rows.indices.filter { state.value.rows[it].id in checkpoint.selected }
        if (indices.isNotEmpty()) selection(checkpoint.selected.toSet() + state.value.rows.subList(indices.first(), indices.last() + 1).map { it.id })
    }
    fun beginSelection(): Boolean {
        if (!editable() || selecting != null || dragRows != null) return false
        selecting = checkpoint.selected.toSet(); return true
    }
    fun previewSelection(ids: Set<Long>) {
        val baseline = selecting ?: return
        val values = ids.intersect(state.value.rows.map { it.id }.toSet())
        mutable.value = state.value.copy(selected = (baseline - values) + (values - baseline))
    }
    fun finishSelection() {
        if (selecting == null) return
        checkpoint = checkpoint.copy(selected = state.value.selected.toList()); selecting = null
        buffered?.let { mutable.value = state.value.copy(rows = it) }; buffered = null; project(); persist()
    }
    fun beginDrag(id: Long): Boolean {
        if (!editable() || selecting != null || dragRows != null || state.value.rows.none { it.id == id }) return false
        dragRows = state.value.rows; target = null; mutable.value = state.value.copy(dragging = id); return true
    }
    fun dragTo(id: Long, after: Boolean) {
        val rows = dragRows ?: return; val moving = state.value.dragging ?: return
        if (moving == id || rows.none { it.id == id }) return
        val values = rows.filter { it.id != moving }.toMutableList(); val index = values.indexOfFirst { it.id == id }
        values.add(index + if (after) 1 else 0, rows.first { it.id == moving }); target = id to after
        mutable.value = state.value.copy(rows = values)
    }
    fun finishDrag() {
        val moving = state.value.dragging ?: return; val destination = target
        val changed = state.value.rows.map { it.id } != dragRows?.map { it.id }
        cancelGesture(); if (changed && destination != null) mutate { repository.move(moving, destination.first, destination.second) }
    }
    fun cancelGesture() {
        if (selecting != null) mutable.value = state.value.copy(selected = checkpoint.selected.toSet())
        val rows = buffered ?: dragRows; if (rows != null) mutable.value = state.value.copy(rows = rows)
        mutable.value = state.value.copy(dragging = null); selecting = null; dragRows = null; buffered = null; target = null
    }
    fun enabled(ids: List<Long>, value: Boolean) = mutate { repository.enabled(ids, value) }
    fun edge(ids: List<Long>, top: Boolean) = mutate { repository.edge(ids, top) }
    fun toggleManual() = mutate {
        val manual = !repository.manual(); repository.manual(manual); currentCoroutineContext().ensureActive()
        mutable.value = state.value.copy(manual = manual)
    }
    private fun mutate(block: suspend () -> Unit) {
        if (!editable()) return
        cancelGesture(); val generation = epoch; mutable.value = state.value.copy(busy = true, error = null); changed()
        operation = viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive(); if (generation == epoch) mutable.value = state.value.copy(busy = false) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch) { mutable.value = state.value.copy(busy = false); failed(error) } }
        }
    }
    fun dialog(kind: ReplaceManagementDialog, ids: List<Long> = state.value.visibleSelection) {
        if (!editable()) return
        cancelGesture(); checkpoint = checkpoint.copy(dialog = kind.name, targets = ids.distinct(), draft = "", draftStart = 0, draftEnd = 0)
        project(); persist()
    }
    fun draft(text: String, start: Int = text.length, end: Int = start) {
        if (!editable() || state.value.dialog == null) return
        checkpoint = checkpoint.copy(draft = text, draftStart = start.coerceIn(0, text.length), draftEnd = end.coerceIn(0, text.length)); project(); persist()
    }
    fun cancelDialog() { if (!editable()) return; checkpoint = checkpoint.copy(dialog = null, targets = emptyList(), draft = "", draftStart = 0, draftEnd = 0); project(); persist() }
    fun confirmDialog() {
        val kind = state.value.dialog ?: return; val ids = checkpoint.targets; val text = checkpoint.draft
        if (kind != ReplaceManagementDialog.Delete && text.isEmpty()) { cancelDialog(); return }
        mutate {
            when (kind) {
                ReplaceManagementDialog.Delete -> repository.delete(ids)
                ReplaceManagementDialog.AddGroup -> repository.group(ids, text, true)
                ReplaceManagementDialog.RemoveGroup -> repository.group(ids, text, false)
            }
            currentCoroutineContext().ensureActive()
            checkpoint = checkpoint.copy(dialog = null, targets = emptyList(), draft = "", draftStart = 0, draftEnd = 0,
                selected = if (kind == ReplaceManagementDialog.Delete) checkpoint.selected - ids.toSet() else checkpoint.selected)
            project(); persist()
        }
    }
    fun scroll(index: Int, offset: Int) {
        val position = index.coerceAtLeast(0); val pixels = offset.coerceAtLeast(0)
        saved["replaceManagement.scrollIndex"] = position; saved["replaceManagement.scrollOffset"] = pixels
        mutable.value = state.value.copy(scrollIndex = position, scrollOffset = pixels)
    }
    fun retry() { if (state.value.busy) return; mutable.value = state.value.copy(error = null); if (!state.value.loaded) labels?.let(::bind) else { persist(); observe(); observeGroups() } }
    suspend fun flush() { sessions.write(id, checkpoint) }
    fun stop() { epoch++; cancelGesture(); viewModelScope.cancel() }
    override fun onCleared() { stop(); cleanup.launch { runCatching { sessions.release(id) } }; super.onCleared() }
}
