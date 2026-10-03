package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.data.entities.Book
import io.legado.app.R
import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class RemoteLibraryState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val draft: RemoteLibraryDraft? = null, val connection: RemoteLibraryConnection? = null, val error: String? = null,
    val writeFailed: Boolean = false, val interrupted: Boolean = false, val pendingCommit: Boolean = false, val progress: Int = 0, val total: Int = 0) {
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
    private var operation: Job? = null; private var pendingAccepted: RemoteLibraryDraft? = null
    private var earlyStorage: Pair<String, String?>? = null
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
    private fun usable() = initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.writeFailed && !state.value.busy && !state.value.pendingCommit && current.task == null
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
                earlyStorage?.let { earlyStorage = null; if (it.first == current.storageTicket) { mutable.value = state.value.copy(loading = false); storagePicked(it.first, it.second); return@launch } }
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
        if ((!usable() && !(initialized && !stopped && !state.value.loading && !state.value.failed && !state.value.busy && !state.value.writeFailed && !state.value.pendingCommit && current.effects.firstOrNull()?.effect == RemoteLibraryEffect.PickStorage)) || current.effects.firstOrNull()?.id != id) return false
        saved["remoteLibraryConsumed"] = id; update(current.copy(effects = current.effects.drop(1))); return true
    }
    fun retry() {
        if (stopped) return
        val accepted = pendingAccepted
        if (accepted != null && !state.value.busy) {
            mutable.value = state.value.copy(busy = true, error = null)
            operation = viewModelScope.launch {
                try { commit(accepted); if (!stopped) mutable.value = state.value.copy(interrupted = current.task != null) }
                catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
                finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
            }
        } else if (state.value.writeFailed) update(current) else initialize()
    }

    private fun receipt(effect: RemoteLibraryEffect, resource: Int = 0, text: String? = null, bookId: String? = null) = RemoteLibraryReceipt(UUID.randomUUID().toString(), effect, text, bookId, resource)
    private suspend fun commit(value: RemoteLibraryDraft) {
        pendingAccepted = value; mutable.value = state.value.copy(pendingCommit = true)
        withContext(NonCancellable) { persist(value); pendingAccepted = null }
        currentCoroutineContext().ensureActive()
        if (!stopped) { current = value; mutable.value = state.value.copy(draft = current, pendingCommit = false, writeFailed = false) }
    }
    private fun readOperation(block: suspend () -> RemoteLibraryDraft) {
        if (!usable()) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { val value = block(); currentCoroutineContext().ensureActive(); commit(value.copy(revision = nextRevision())) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun menu(effect: RemoteLibraryEffect) { if (effect in listOf(RemoteLibraryEffect.Log, RemoteLibraryEffect.Help, RemoteLibraryEffect.Servers))
        readOperation { current.copy(effects = current.effects + receipt(effect)) } }
    fun dismissConfirmation() { if (usable()) update(current.copy(confirmation = null)) }
    fun reimport(id: String) { if (usable()) current.rows.firstOrNull { it.id == id && !it.directory && it.onShelf }?.let { update(current.copy(confirmation = RemoteLibraryConfirmation(RemoteLibraryPrompt.Reimport, id))) } }
    private fun readingDraft(target: RemoteLibraryReadTarget): RemoteLibraryDraft = when (target) {
        RemoteLibraryReadTarget.None -> current.copy(confirmation = null)
        RemoteLibraryReadTarget.UnsupportedArchive -> current.copy(confirmation = null, effects = current.effects + receipt(RemoteLibraryEffect.Toast, R.string.unsupport_archivefile_entry))
        is RemoteLibraryReadTarget.Open -> current.copy(confirmation = null, effects = current.effects + receipt(RemoteLibraryEffect.OpenBook, bookId = target.bookId))
        is RemoteLibraryReadTarget.DownloadArchive -> current.copy(confirmation = RemoteLibraryConfirmation(RemoteLibraryPrompt.DownloadArchive, target.entry.id))
        is RemoteLibraryReadTarget.ImportArchive -> current.copy(confirmation = RemoteLibraryConfirmation(RemoteLibraryPrompt.ImportArchive, uri = target.uri, name = target.name))
        is RemoteLibraryReadTarget.ChooseArchive -> current.copy(confirmation = RemoteLibraryConfirmation(RemoteLibraryPrompt.ChooseArchive, uri = target.uri, names = target.names))
    }
    fun read(id: String) { val row = state.value.visible.firstOrNull { it.id == id && it.onShelf && !it.directory } ?: return
        readOperation { readingDraft(reading.prepare(row)) } }
    fun archiveChoice(name: String) { val prompt = current.confirmation?.takeIf { it.kind == RemoteLibraryPrompt.ChooseArchive && name in it.names } ?: return
        readOperation { readingDraft(reading.chooseArchive(requireNotNull(prompt.uri), name)) } }
    suspend fun prepareBook(id: String): Book? = reading.readBook(id)
    fun preparationFailed(error: Exception) { if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
    fun confirm() {
        if (!usable()) return
        val prompt = current.confirmation ?: return
        when (prompt.kind) {
            RemoteLibraryPrompt.StorageHelp -> pickStorage(initial = true)
            RemoteLibraryPrompt.Reimport -> startTask(RemoteLibraryTask(UUID.randomUUID().toString(), RemoteLibraryTaskKind.ImportBooks, listOf(requireNotNull(prompt.entryId))))
            RemoteLibraryPrompt.DownloadArchive -> startTask(RemoteLibraryTask(UUID.randomUUID().toString(), RemoteLibraryTaskKind.ImportBooks, listOf(requireNotNull(prompt.entryId)), readAfter = prompt.entryId))
            RemoteLibraryPrompt.ImportArchive -> startTask(RemoteLibraryTask(UUID.randomUUID().toString(), RemoteLibraryTaskKind.ImportArchive, uri = prompt.uri, name = prompt.name))
            RemoteLibraryPrompt.ChooseArchive -> Unit
        }
    }
    fun importSelected() { if (usable() && state.value.visibleSelection.isNotEmpty()) startTask(RemoteLibraryTask(UUID.randomUUID().toString(), RemoteLibraryTaskKind.ImportBooks, state.value.visibleSelection)) }
    private fun startTask(task: RemoteLibraryTask) {
        if (stopped || state.value.busy || state.value.pendingCommit) return
        mutable.value = state.value.copy(busy = true, error = null, interrupted = false, progress = task.completed.size, total = task.ids.size)
        operation = viewModelScope.launch {
            try {
                val connection = requireNotNull(state.value.connection)
                commit(current.copy(revision = nextRevision(), confirmation = null, task = task)); currentCoroutineContext().ensureActive()
                when (task.kind) {
                    RemoteLibraryTaskKind.ImportBooks -> task.ids.filterNot { it in task.completed }.forEach { id ->
                        currentCoroutineContext().ensureActive()
                        val row = current.rows.firstOrNull { it.id == id && !it.directory } ?: return@forEach
                        repository.importWithReceipt(connection, row) {
                            withContext(Dispatchers.Main.immediate + NonCancellable) {
                                val accepted = current.copy(revision = nextRevision(), task = requireNotNull(current.task).copy(completed = (requireNotNull(current.task).completed + id).distinct()), rows = current.rows.map { if (it.id == id) it.copy(onShelf = true) else it })
                                commit(accepted)
                                if (!stopped) mutable.value = state.value.copy(progress = accepted.task!!.completed.size)
                            }
                        }
                    }
                    RemoteLibraryTaskKind.ImportArchive -> {
                        if (task.completed.isEmpty()) reading.importArchiveWithReceipt(requireNotNull(task.uri), requireNotNull(task.name)) { bookId ->
                            withContext(Dispatchers.Main.immediate + NonCancellable) {
                                commit(current.copy(revision = nextRevision(), task = requireNotNull(current.task).copy(completed = listOf(bookId ?: "completed-without-book")),
                                    effects = if (bookId != null) current.effects + receipt(RemoteLibraryEffect.OpenBook, bookId = bookId) else current.effects))
                            }
                        }
                    }
                }
                val completed = current.copy(revision = nextRevision(), task = null, selected = emptyList())
                commit(completed); currentCoroutineContext().ensureActive()
                if (task.readAfter != null) current.rows.firstOrNull { it.id == task.readAfter }?.let { row -> commit(readingDraft(reading.prepare(row)).copy(revision = nextRevision())) }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) {
                    if (pendingAccepted == null) update(current.copy(selected = emptyList())) else pendingAccepted = pendingAccepted!!.copy(revision = nextRevision(), selected = emptyList())
                    mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), interrupted = current.task != null)
                    if (error is SecurityException && !state.value.pendingCommit) pickStorage(initial = false, allowInterrupted = true)
                }
            } finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun retryTaskConfirmed() { if (!stopped && !state.value.busy && !state.value.pendingCommit) current.task?.let(::startTask) }
    fun discardTask() { if (!stopped && !state.value.busy && !state.value.pendingCommit) { update(current.copy(task = null, selected = emptyList())); mutable.value = state.value.copy(interrupted = false, error = null) } }
    private fun pickStorage(initial: Boolean, allowInterrupted: Boolean = false) {
        if (!allowInterrupted && !usable()) return
        val ticket = receipt(RemoteLibraryEffect.PickStorage); saved["remoteStorageInitial"] = initial
        update(current.copy(confirmation = null, storageTicket = ticket.id, effects = current.effects + ticket))
    }
    fun cancelStorageHelp() { if (usable() && current.confirmation?.kind == RemoteLibraryPrompt.StorageHelp)
        readOperation { current.copy(confirmation = null, effects = current.effects + receipt(RemoteLibraryEffect.Close)) } }
    fun storageTicket(): String? = current.storageTicket
    fun storagePicked(id: String, uri: String?) {
        if (!initialized || state.value.loading || state.value.failed) { if (!stopped && earlyStorage == null) earlyStorage = id to uri; return }
        if (stopped || current.storageTicket != id || state.value.busy || state.value.pendingCommit) return
        val initial = saved.get<Boolean>("remoteStorageInitial") == true
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (uri != null) reading.storageUri(uri)
                currentCoroutineContext().ensureActive()
                val effects = if (uri == null && initial) current.effects + receipt(RemoteLibraryEffect.Close) else current.effects
                commit(current.copy(revision = nextRevision(), storageTicket = null, effects = effects)); currentCoroutineContext().ensureActive()
                if ((uri != null || !initial) && !stopped) { mutable.value = state.value.copy(busy = false); initialize() }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    suspend fun flush() { if (initialized) persist(current) }
    fun stop() { if (!stopped) { stopped = true; generation++; loading?.cancel(); operation?.cancel(); writer.cancel() } }
    suspend fun release() { repository.close(); drafts.release(session) }
    override fun onCleared() { stop(); super.onCleared() }
}
