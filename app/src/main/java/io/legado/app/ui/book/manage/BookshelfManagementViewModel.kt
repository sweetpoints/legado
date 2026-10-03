package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.io.File

internal data class BookshelfManagementState(val loading: Boolean = true, val failed: Boolean = false,
    val snapshot: ManagedShelfSnapshot? = null, val draft: BookshelfManagementDraft? = null, val error: String? = null,
    val writeFailed: Boolean = false, val selecting: Boolean = false, val busy: Boolean = false,
    val progress: String? = null, val pendingCommit: Boolean = false, val interrupted: Boolean = false, val invalidCron: Boolean = false) {
    val visibleSelection: List<String> get() {
        val selected = draft?.selected.orEmpty().toHashSet()
        return snapshot?.books.orEmpty().map { it.id }.filter { it in selected }
    }
}
/** The saved Bundle contains one opaque session ID; selection URLs and queries stay on disk. */
internal class BookshelfManagementViewModel(private val repository: BookshelfManagementRepository,
    private val drafts: BookshelfManagementDraftRepository, private val saved: SavedStateHandle,
    private val initialGroup: Long = -1L, private val maintenance: BookshelfMaintenanceRepository? = null,
    private val covers: BookshelfCoverRepository? = null, private val sources: BookshelfSourceRepository? = null) : ViewModel() {
    val session = saved.get<String>("shelfManageSession") ?: UUID.randomUUID().toString().also { saved["shelfManageSession"] = it }
    private val mutable = MutableStateFlow(BookshelfManagementState()); val state = mutable.asStateFlow()
    private var current = BookshelfManagementDraft(); private var initialized = false; private var stopped = false
    private var revision = 0L; private var generation = 0; private var loading: Job? = null; private var observing: Job? = null
    private var operation: Job? = null; private var pendingAccepted: BookshelfManagementDraft? = null; private var uncommittedExport: File? = null
    private var earlyGroup: Pair<String, Long>? = null; private var earlySource: Pair<String, String>? = null
    private var gesture: Set<String>? = null; private var gestureIds: List<String> = emptyList()
    private val gate = Mutex(); private val updates = MutableStateFlow<BookshelfManagementDraft?>(null)
    private val writer = viewModelScope.launch { updates.filterNotNull().collect { value ->
        try { persist(value); currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = false) }
        catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current.revision == value.revision) mutable.value = state.value.copy(writeFailed = true, error = error.localizedMessage.orEmpty()) }
    } }
    init { initialize() }
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun usable() = initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.writeFailed && !state.value.busy && !state.value.pendingCommit && current.operation == null
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
                val consumed = saved.get<String>("shelfManageConsumed")
                val consumedIndex = current.effects.indexOfFirst { it.id == consumed }
                if (consumedIndex >= 0) { current = current.copy(revision = nextRevision(), effects = current.effects.drop(consumedIndex + 1)); persist(current); currentCoroutineContext().ensureActive() }
                if (!stopped && generation == token) { mutable.value = state.value.copy(draft = current, interrupted = current.operation != null); observe() }
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
                    if (usable()) earlyGroup?.let { earlyGroup = null; groupPicked(it.first, it.second) }
                    if (usable()) earlySource?.let { earlySource = null; sourcePicked(it.first, it.second) }
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
    fun confirmAction(action: ShelfManagementAction, ids: List<String> = state.value.visibleSelection) {
        if (!usable() || gesture != null) return
        update(current.copy(confirmation = ShelfManagementConfirmation(action, ids.toList())))
    }
    fun confirmationText(value: String, start: Int = value.length, end: Int = start) {
        if (!usable()) return
        current.confirmation?.let { update(current.copy(confirmation = it.copy(cron = value, selectionStart = start.coerceIn(0, value.length), selectionEnd = end.coerceIn(0, value.length)))); mutable.value = state.value.copy(invalidCron = false) }
    }
    fun deleteOriginal(value: Boolean) { if (usable()) current.confirmation?.let { update(current.copy(confirmation = it.copy(deleteOriginal = value))) } }
    fun dismissConfirmation() { if (usable()) update(current.copy(confirmation = null)) }
    fun executeConfirmed() {
        val value = current.confirmation ?: return
        if (!usable()) return
        if (value.action == ShelfManagementAction.CreateTasks && io.legado.app.utils.CronSchedule.parse(value.cron.trim()) == null) { mutable.value = state.value.copy(invalidCron = true); return }
        execute(value.action, value.ids, value.deleteOriginal, value.cron)
    }
    fun execute(action: ShelfManagementAction, ids: List<String> = state.value.visibleSelection, original: Boolean = false,
        cron: String = "", sourceId: String = "", group: Long = 0L, enabled: Boolean = false) {
        if (!usable() || gesture != null) return
        startOperation(ShelfManagementOperation(UUID.randomUUID().toString(), action, ids.toList(), original, cron, sourceId, group, enabled))
    }
    private fun toast(resource: Int, vararg args: Int) = ShelfManagementReceipt(UUID.randomUUID().toString(), ShelfManagementEffect.Toast, resource, args.toList())
    private fun startOperation(value: ShelfManagementOperation) {
        mutable.value = state.value.copy(busy = true, error = null, progress = null)
        operation = viewModelScope.launch {
            try {
                current = current.copy(revision = nextRevision(), confirmation = null, operation = value); mutable.value = state.value.copy(draft = current, interrupted = false)
                persist(current); currentCoroutineContext().ensureActive()
                val receipts = when (value.action) {
                    ShelfManagementAction.Delete -> { requireNotNull(maintenance).delete(value.ids, value.deleteOriginal); emptyList() }
                    ShelfManagementAction.EnableUpdate, ShelfManagementAction.DisableUpdate -> { repository.canUpdate(value.ids, value.action == ShelfManagementAction.EnableUpdate); emptyList() }
                    ShelfManagementAction.GroupReplace, ShelfManagementAction.GroupAdd, ShelfManagementAction.GroupRemove -> {
                        repository.group(value.ids, value.group, when (value.action) { ShelfManagementAction.GroupAdd -> ShelfGroupMutation.Add; ShelfManagementAction.GroupRemove -> ShelfGroupMutation.Remove; else -> ShelfGroupMutation.Replace }); emptyList()
                    }
                    ShelfManagementAction.ToggleTitle -> { repository.openTitle(value.enabled); emptyList() }
                    ShelfManagementAction.ClearCache -> { requireNotNull(maintenance).clearCache(value.ids); listOf(toast(R.string.clear_cache_success)) }
                    ShelfManagementAction.CreateTasks -> {
                        val count = requireNotNull(maintenance).createTasks(value.ids, value.cron)
                        listOf(toast(if (count == 0) R.string.no_book_can_update else R.string.success))
                    }
                    ShelfManagementAction.UpdateToc -> {
                        val books = requireNotNull(maintenance).updateCandidates(value.ids)
                        if (books.isEmpty()) listOf(toast(R.string.no_book_can_update)) else listOf(ShelfManagementReceipt(UUID.randomUUID().toString(), ShelfManagementEffect.UpdateToc, ids = books.map { it.bookUrl }))
                    }
                    ShelfManagementAction.ExportSources -> {
                        val file = requireNotNull(maintenance).exportSources().also { uncommittedExport = it }
                        listOf(ShelfManagementReceipt(UUID.randomUUID().toString(), ShelfManagementEffect.ExportSources, file = file.absolutePath))
                    }
                    ShelfManagementAction.PersistCovers, ShelfManagementAction.RestoreNetworkCovers, ShelfManagementAction.RestoreSourceCovers -> {
                        var result: ShelfCoverSummary? = null
                        requireNotNull(covers).run(value.ids, when (value.action) { ShelfManagementAction.RestoreNetworkCovers -> ShelfCoverAction.RestoreNetwork; ShelfManagementAction.RestoreSourceCovers -> ShelfCoverAction.RestoreSource; else -> ShelfCoverAction.PersistNetwork }).collect { event ->
                            currentCoroutineContext().ensureActive()
                            when (event) { is ShelfCoverEvent.Progress -> if (!stopped) mutable.value = state.value.copy(progress = "${event.position} / ${event.total}"); is ShelfCoverEvent.Completed -> result = event.summary }
                        }
                        val summary = requireNotNull(result)
                        listOf(when (value.action) { ShelfManagementAction.RestoreNetworkCovers -> toast(R.string.restore_network_cover_result, summary.saved); ShelfManagementAction.RestoreSourceCovers -> toast(R.string.restore_source_cover_result, summary.saved); else -> toast(R.string.persist_cover_result, summary.saved, summary.skipped, summary.failed) })
                    }
                    ShelfManagementAction.ChangeSource -> {
                        requireNotNull(sources).change(value.ids, value.sourceId).collect { event -> currentCoroutineContext().ensureActive(); if (!stopped && event is ShelfSourceEvent.Progress) mutable.value = state.value.copy(progress = "${event.position} / ${event.total}") }
                        emptyList()
                    }
                }
                currentCoroutineContext().ensureActive()
                val completed = current.copy(revision = nextRevision(), operation = null, effects = current.effects + receipts, exports = (current.exports + receipts.mapNotNull { it.file }).distinct())
                pendingAccepted = completed; mutable.value = state.value.copy(pendingCommit = true)
                completeAccepted(completed)
                if (!stopped) observe()
            } catch (canceled: CancellationException) {
                val abandoned = uncommittedExport
                if (abandoned != null) withContext(Dispatchers.IO + NonCancellable) { abandoned.delete() }
                uncommittedExport = null
                throw canceled
            }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), interrupted = current.operation != null, pendingCommit = pendingAccepted != null) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false, progress = null) }
        }
    }
    private suspend fun completeAccepted(value: BookshelfManagementDraft) {
        withContext(NonCancellable) {
            persist(value)
            uncommittedExport = null // Disk owns the export before cancellation can resume the caller.
        }
        currentCoroutineContext().ensureActive()
        if (!stopped) { current = value; pendingAccepted = null; mutable.value = state.value.copy(draft = current, interrupted = false, pendingCommit = false, writeFailed = false) }
    }
    fun retryOperationConfirmed() { if (!stopped && !state.value.busy && !state.value.pendingCommit) current.operation?.let(::startOperation) }
    fun cancelOperation() {
        if (stopped || pendingAccepted != null) return
        val action = current.operation?.action ?: return
        if (action !in listOf(ShelfManagementAction.ChangeSource, ShelfManagementAction.PersistCovers)) return
        operation?.cancel(); update(current.copy(operation = null)); mutable.value = state.value.copy(busy = false, progress = null, interrupted = false)
    }
    private fun queue(receipt: ShelfManagementReceipt, transform: (BookshelfManagementDraft) -> BookshelfManagementDraft = { it }) {
        if (!usable() || gesture != null) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val queued = transform(current).copy(revision = nextRevision(), effects = current.effects + receipt)
                persist(queued); currentCoroutineContext().ensureActive()
                if (!stopped) { current = queued; mutable.value = state.value.copy(draft = current) }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun openBook(id: String) {
        if (state.value.snapshot?.books?.any { it.id == id } != true) return
        queue(ShelfManagementReceipt(UUID.randomUUID().toString(), ShelfManagementEffect.OpenBook, ids = listOf(id)))
    }
    fun manageGroups() = queue(ShelfManagementReceipt(UUID.randomUUID().toString(), ShelfManagementEffect.ManageGroups))
    fun pickGroup(mode: ShelfGroupMutation, ids: List<String> = state.value.visibleSelection, group: Long = 0L) {
        if (current.groupRequest != null) return
        val id = UUID.randomUUID().toString()
        queue(ShelfManagementReceipt(id, ShelfManagementEffect.PickGroup)) { it.copy(groupRequest = ShelfGroupRequest(id, ids.toList(), group, mode)) }
    }
    fun cancelGroup(id: String) { if (usable() && current.groupRequest?.id == id) update(current.copy(groupRequest = null)) }
    fun groupPicked(id: String, group: Long) {
        if (!initialized || state.value.loading || state.value.failed) { if (!stopped && earlyGroup == null) earlyGroup = id to group; return }
        if (!usable()) return
        val request = current.groupRequest?.takeIf { it.id == id } ?: return
        update(current.copy(groupRequest = null))
        execute(when (request.mode) { ShelfGroupMutation.Add -> ShelfManagementAction.GroupAdd; ShelfGroupMutation.Remove -> ShelfManagementAction.GroupRemove; ShelfGroupMutation.Replace -> ShelfManagementAction.GroupReplace }, request.ids, group = group)
    }
    fun pickSource() {
        if (current.sourceTicket != null) return
        val id = UUID.randomUUID().toString(); val ids = state.value.visibleSelection
        queue(ShelfManagementReceipt(id, ShelfManagementEffect.PickSource)) { it.copy(sourceTicket = id, sourceIds = ids) }
    }
    fun cancelSource(id: String) { if (usable() && current.sourceTicket == id) update(current.copy(sourceTicket = null, sourceIds = emptyList())) }
    fun sourcePicked(id: String, source: String) {
        if (!initialized || state.value.loading || state.value.failed) { if (!stopped && earlySource == null) earlySource = id to source; return }
        if (!usable() || current.sourceTicket != id) return
        val ids = current.sourceIds; update(current.copy(sourceTicket = null, sourceIds = emptyList()))
        execute(ShelfManagementAction.ChangeSource, ids, sourceId = source)
    }
    fun consumeEffect(id: String): Boolean {
        if (!usable() || current.effects.firstOrNull()?.id != id) return false
        saved["shelfManageConsumed"] = id; update(current.copy(effects = current.effects.drop(1))); return true
    }
    suspend fun prepareBooks(receipt: ShelfManagementReceipt): List<Book> {
        return if (receipt.effect == ShelfManagementEffect.UpdateToc) requireNotNull(maintenance).updateCandidates(receipt.ids)
        else requireNotNull(maintenance).books(receipt.ids)
    }
    fun retry() { if (stopped) return
        if (pendingAccepted != null && !state.value.busy) {
            mutable.value = state.value.copy(busy = true, error = null)
            operation = viewModelScope.launch {
                try { completeAccepted(pendingAccepted!!); if (!stopped) observe() }
                catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
                finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
            }
        }
        else if (state.value.failed) initialize()
        else if (state.value.writeFailed) update(current)
    }
    suspend fun flush() { if (initialized) persist(current.copy(selected = gesture?.toList() ?: current.selected)) }
    fun stop() { if (!stopped) { stopped = true; generation++; loading?.cancel(); observing?.cancel(); operation?.cancel(); writer.cancel() } }
    suspend fun release() { uncommittedExport?.let { withContext(Dispatchers.IO + NonCancellable) { it.delete() } }; uncommittedExport = null; drafts.release(session) }
    override fun onCleared() { stop(); super.onCleared() }
}
