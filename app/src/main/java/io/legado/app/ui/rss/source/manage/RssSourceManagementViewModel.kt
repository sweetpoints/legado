package io.legado.app.ui.rss.source.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class RssSourceManagementAction { Add, Edit, ImportLocal, ImportQr, ImportUrl, Export, Share, Help, Groups }
enum class RssSourceManagementDialog { Delete, AddGroup, RemoveGroup, ImportUrl }
data class RssSourceManagementEffect(val action: RssSourceManagementAction, val nonce: String)
data class RssSourceManagementLabels(val enabled: String, val disabled: String, val login: String, val noGroup: String)
data class RssSourceManagementState(val loaded: Boolean = false, val busy: Boolean = false,
    val rows: List<RssSourceManagementRow> = emptyList(), val groups: List<String> = emptyList(),
    val query: String = "", val queryStart: Int = 0, val queryEnd: Int = 0, val selected: Set<String> = emptySet(),
    val dialog: RssSourceManagementDialog? = null, val draft: String = "", val draftStart: Int = 0, val draftEnd: Int = 0,
    val history: List<String> = emptyList(), val pending: RssSourceManagementEffect? = null,
    val dragging: String? = null, val error: String? = null, val scrollIndex: Int = 0, val scrollOffset: Int = 0) {
    val visibleSelection get() = rows.filter { it.id in selected }.map { it.id }
}
data class RssSourceManagementNative(val effect: RssSourceManagementEffect, val sourceId: String? = null,
    val input: String? = null, val export: RssSourceManagementExport? = null)

/** Queries, large selections and drafts are disk backed; transient gestures never write a checkpoint. */
class RssSourceManagementViewModel(private val repository: RssSourceManagementRepository,
    private val sessions: RssSourceManagementSessionRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val id = saved.get<String>("rssManagement.session") ?: UUID.randomUUID().toString().also { saved["rssManagement.session"] = it }
    private val mutable = MutableStateFlow(RssSourceManagementState(scrollIndex = saved.get<Int>("rssManagement.scrollIndex") ?: 0,
        scrollOffset = saved.get<Int>("rssManagement.scrollOffset") ?: 0))
    val state: StateFlow<RssSourceManagementState> = mutable
    private var checkpoint = RssSourceManagementCheckpoint()
    private var revision = saved.get<Long>("rssManagement.revision") ?: 0L
    private var labels: RssSourceManagementLabels? = null
    private var generation = 0L
    private var loading: Job? = null
    private var rowsJob: Job? = null
    private var groupJob: Job? = null
    private var operation: Job? = null
    private var selecting: Set<String>? = null
    private var dragRows: List<RssSourceManagementRow>? = null
    private var bufferedRows: List<RssSourceManagementRow>? = null
    private var moveTarget: Pair<String, Boolean>? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun bind(value: RssSourceManagementLabels) {
        if (state.value.loaded) { if (labels != value) { labels = value; observe() }; return }
        labels = value
        if (loading?.isActive == true) return
        val epoch = generation
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(id); currentCoroutineContext().ensureActive(); if (epoch != generation) return@launch
                revision = maxOf(revision, disk?.revision ?: 0); checkpoint = disk ?: checkpoint
                val pending = checkpoint.pending?.takeUnless { saved.get<String>("rssManagement.delivered") == it.nonce }
                checkpoint = checkpoint.copy(pending = pending)
                project(); observe(); observeGroups(); if (state.value.dialog == RssSourceManagementDialog.ImportUrl) loadHistory()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
        }
    }
    private fun project() {
        mutable.value = state.value.copy(loaded = true, busy = false, query = checkpoint.query,
            queryStart = checkpoint.queryStart, queryEnd = checkpoint.queryEnd, selected = checkpoint.selected.toSet(),
            dialog = checkpoint.dialog?.let { runCatching { RssSourceManagementDialog.valueOf(it) }.getOrNull() },
            draft = checkpoint.draft, draftStart = checkpoint.draftStart, draftEnd = checkpoint.draftEnd,
            pending = checkpoint.pending?.let { value -> runCatching { RssSourceManagementEffect(RssSourceManagementAction.valueOf(value.action), value.nonce) }.getOrNull() }, error = null)
    }
    private fun filter(): RssSourceManagementFilter {
        val key = checkpoint.query; val labels = checkNotNull(labels)
        return when {
            key.isBlank() -> RssSourceManagementFilter.All
            key == labels.enabled -> RssSourceManagementFilter.Enabled
            key == labels.disabled -> RssSourceManagementFilter.Disabled
            key == labels.login -> RssSourceManagementFilter.Login
            key == labels.noGroup -> RssSourceManagementFilter.NoGroup
            key.startsWith("group:") -> RssSourceManagementFilter.Group(key.substringAfter("group:"))
            else -> RssSourceManagementFilter.Search(key)
        }
    }
    private fun observe() {
        rowsJob?.cancel(); val epoch = generation
        rowsJob = viewModelScope.launch {
            try { repository.rows(filter()).collect { rows ->
                currentCoroutineContext().ensureActive()
                if (epoch == generation) {
                    if (dragRows != null || selecting != null) bufferedRows = rows
                    else mutable.value = state.value.copy(rows = rows)
                }
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
        }
    }
    private fun observeGroups() {
        groupJob?.cancel(); val epoch = generation
        groupJob = viewModelScope.launch {
            try { repository.groups().collect { groups -> if (epoch == generation) mutable.value = state.value.copy(groups = groups) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
        }
    }
    private fun editable() = state.value.loaded && !state.value.busy && state.value.pending == null
    private fun persist() {
        revision++; saved["rssManagement.revision"] = revision; checkpoint = checkpoint.copy(revision = revision)
        val value = checkpoint; val epoch = generation
        viewModelScope.launch { try { sessions.write(id, value); currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation && revision == value.revision) failed(error) } }
    }
    fun query(text: String, start: Int = text.length, end: Int = start) {
        if (!editable()) return
        cancelGesture(); val changed = text != checkpoint.query
        checkpoint = checkpoint.copy(query = text, queryStart = start.coerceIn(0, text.length), queryEnd = end.coerceIn(0, text.length))
        project(); persist(); if (changed) observe()
    }
    fun selected(id: String, checked: Boolean) {
        if (!editable() || state.value.rows.none { it.id == id }) return
        val selected = checkpoint.selected.toMutableSet(); if (checked) selected.add(id) else selected.remove(id)
        checkpoint = checkpoint.copy(selected = selected.toList()); project(); persist()
    }
    fun selectAll() { selection(state.value.rows.map { it.id }.toSet() + checkpoint.selected) }
    fun invertSelection() {
        val selected = checkpoint.selected.toMutableSet(); state.value.rows.forEach { if (!selected.add(it.id)) selected.remove(it.id) }; selection(selected)
    }
    fun selectInterval() {
        val indices = state.value.rows.indices.filter { state.value.rows[it].id in checkpoint.selected }
        if (indices.isEmpty()) return
        selection(checkpoint.selected.toSet() + state.value.rows.subList(indices.first(), indices.last() + 1).map { it.id })
    }
    private fun selection(ids: Set<String>) { if (!editable()) return; cancelGesture(); checkpoint = checkpoint.copy(selected = ids.toList()); project(); persist() }
    fun beginSelection(): Boolean {
        if (!editable() || dragRows != null || selecting != null) return false
        selecting = checkpoint.selected.toSet(); return true
    }
    fun selectionRange(first: String, last: String) {
        val baseline = selecting ?: return; val rows = state.value.rows
        val start = rows.indexOfFirst { it.id == first }; val end = rows.indexOfFirst { it.id == last }; if (start < 0 || end < 0) return
        val changed = rows.subList(minOf(start, end), maxOf(start, end) + 1).map { it.id }.toSet()
        mutable.value = state.value.copy(selected = (baseline - changed) + (changed - baseline))
    }
    fun finishSelection() {
        if (selecting == null) return
        checkpoint = checkpoint.copy(selected = state.value.selected.toList()); selecting = null
        bufferedRows?.let { mutable.value = state.value.copy(rows = it) }; bufferedRows = null; project(); persist()
    }
    fun beginDrag(id: String): Boolean {
        if (!editable() || selecting != null || dragRows != null || state.value.rows.none { it.id == id }) return false
        dragRows = state.value.rows; moveTarget = null; mutable.value = state.value.copy(dragging = id); return true
    }
    fun dragTo(target: String, after: Boolean) {
        val original = dragRows ?: return; val moving = state.value.dragging ?: return
        if (target == moving || original.none { it.id == target }) return
        val rows = original.filter { it.id != moving }.toMutableList(); val index = rows.indexOfFirst { it.id == target }
        rows.add(index + if (after) 1 else 0, original.first { it.id == moving }); moveTarget = target to after
        mutable.value = state.value.copy(rows = rows)
    }
    fun finishDrag() {
        val moving = state.value.dragging ?: return; val target = moveTarget
        cancelGesture()
        if (target != null) mutate { repository.move(moving, target.first, target.second) }
    }
    fun cancelGesture() {
        val original = dragRows
        if (selecting != null) mutable.value = state.value.copy(selected = checkpoint.selected.toSet())
        if (original != null) mutable.value = state.value.copy(rows = bufferedRows ?: original, dragging = null)
        else bufferedRows?.let { mutable.value = state.value.copy(rows = it) }
        selecting = null; dragRows = null; bufferedRows = null; moveTarget = null
    }
    fun enabled(ids: List<String>, enabled: Boolean) = mutate { repository.enabled(ids, enabled) }
    fun edge(ids: List<String>, top: Boolean) = mutate { repository.edge(ids, top) }
    private fun mutate(block: suspend () -> Unit) {
        if (!editable()) return
        cancelGesture(); val epoch = generation; mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive(); if (epoch == generation) mutable.value = state.value.copy(busy = false) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
        }
    }
    fun dialog(dialog: RssSourceManagementDialog, ids: List<String> = state.value.visibleSelection) {
        if (!editable()) return
        cancelGesture(); checkpoint = checkpoint.copy(dialog = dialog.name, targets = ids.distinct(), draft = "", draftStart = 0, draftEnd = 0)
        project(); persist()
        if (dialog == RssSourceManagementDialog.ImportUrl) loadHistory()
    }
    private fun loadHistory() {
        val epoch = generation
        viewModelScope.launch { try {
            val history = repository.importHistory(); currentCoroutineContext().ensureActive()
            if (epoch == generation && state.value.dialog == RssSourceManagementDialog.ImportUrl) mutable.value = state.value.copy(history = history)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation && state.value.dialog == RssSourceManagementDialog.ImportUrl) failed(error) } }
    }
    fun draft(text: String, start: Int = text.length, end: Int = start) {
        if (!editable() || state.value.dialog == null) return
        checkpoint = checkpoint.copy(draft = text, draftStart = start.coerceIn(0, text.length), draftEnd = end.coerceIn(0, text.length)); project(); persist()
    }
    fun cancelDialog() { if (!editable()) return; checkpoint = checkpoint.copy(dialog = null, draft = "", draftStart = 0, draftEnd = 0, targets = emptyList()); project(); persist() }
    fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        val targets = checkpoint.targets; val draft = checkpoint.draft
        if (dialog == RssSourceManagementDialog.ImportUrl) { effect(RssSourceManagementAction.ImportUrl, input = draft); return }
        if (dialog != RssSourceManagementDialog.Delete && draft.isEmpty()) { cancelDialog(); return }
        mutate {
            when (dialog) {
                RssSourceManagementDialog.Delete -> repository.delete(targets)
                RssSourceManagementDialog.AddGroup -> repository.group(targets, draft, true)
                RssSourceManagementDialog.RemoveGroup -> repository.group(targets, draft, false)
                else -> Unit
            }
            currentCoroutineContext().ensureActive()
            checkpoint = checkpoint.copy(dialog = null, draft = "", draftStart = 0, draftEnd = 0, targets = emptyList()); project(); persist()
        }
    }
    fun defaults() = mutate { repository.importDefault() }
    fun forgetImport(value: String) = mutate {
        repository.forgetImport(value); val history = repository.importHistory(); currentCoroutineContext().ensureActive()
        mutable.value = state.value.copy(history = history)
    }
    fun effect(action: RssSourceManagementAction, sourceId: String? = null, input: String? = null) {
        if (!editable()) return
        val ids = state.value.visibleSelection
        if (action in listOf(RssSourceManagementAction.Export, RssSourceManagementAction.Share) && ids.isEmpty()) return
        if (action == RssSourceManagementAction.Edit && sourceId == null) return
        val epoch = generation; mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            var ownedExport: RssSourceManagementExport? = null
            try {
                val exported = if (action in listOf(RssSourceManagementAction.Export, RssSourceManagementAction.Share)) repository.export(ids) else null
                ownedExport = exported
                if (action == RssSourceManagementAction.ImportUrl) repository.rememberImport(input.orEmpty())
                currentCoroutineContext().ensureActive(); if (epoch != generation) return@launch
                val prepared = RssSourceManagementPrepared(action.name, UUID.randomUUID().toString(), sourceId, input, exported)
                revision++; saved["rssManagement.revision"] = revision
                checkpoint = checkpoint.copy(revision = revision, pending = prepared, dialog = null, draft = "", draftStart = 0, draftEnd = 0, targets = emptyList(), exportFile = exported ?: checkpoint.exportFile)
                ownedExport = null // The private pending checkpoint now owns the file, including write-failure retry.
                sessions.write(id, checkpoint); currentCoroutineContext().ensureActive(); if (epoch == generation) project()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
            finally { ownedExport?.let { export -> withContext(Dispatchers.IO + NonCancellable) { repository.releaseExport(export.path) } } }
        }
    }
    fun native(nonce: String): RssSourceManagementNative? = checkpoint.pending?.takeIf { it.nonce == nonce && state.value.pending?.nonce == nonce }?.let {
        RssSourceManagementNative(checkNotNull(state.value.pending), it.sourceId, it.input, it.export)
    }
    fun delivered(nonce: String): Boolean {
        if (state.value.pending?.nonce != nonce) return false
        saved["rssManagement.delivered"] = nonce; checkpoint = checkpoint.copy(pending = null); project(); persist(); return true
    }
    suspend fun source(id: String) = repository.source(id)
    fun scroll(index: Int, offset: Int) {
        saved["rssManagement.scrollIndex"] = index.coerceAtLeast(0); saved["rssManagement.scrollOffset"] = offset.coerceAtLeast(0)
        mutable.value = state.value.copy(scrollIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0))
    }
    fun retry() {
        if (state.value.busy) return
        if (!state.value.loaded) { labels?.let(::bind); return }
        val epoch = generation; mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                sessions.write(id, checkpoint.copy(revision = revision)); currentCoroutineContext().ensureActive()
                if (epoch == generation) { project(); observe() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (epoch == generation) failed(error) }
        }
    }
    private fun failed(error: Exception) { mutable.value = state.value.copy(busy = false, error = error.localizedMessage ?: error.javaClass.simpleName) }
    suspend fun flush() { val current = checkpoint.copy(revision = revision); sessions.write(id, current) }
    fun stop() { generation++; cancelGesture(); viewModelScope.cancel() }
    override fun onCleared() {
        stop()
        val undelivered = checkpoint.pending?.export
        cleanup.launch {
            runCatching { sessions.release(id) }
            // Prepared exports have not been handed to a native consumer; delivered shares remain readable.
            undelivered?.let { runCatching { repository.releaseExport(it.path) } }
        }
        super.onCleared()
    }
}
