package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FileManagementState(
    val root: String? = null,
    val directory: String? = null,
    val crumbs: List<ManagedFileCrumb> = emptyList(),
    val rows: List<ManagedFile> = emptyList(),
    val query: String = "",
    val queryStart: Int = 0,
    val queryEnd: Int = 0,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val navigation: ManagedFileOpen? = null,
    val error: String? = null,
    val closed: Boolean = false,
) {
    val canAct
        get() = loaded && !busy && !closed && navigation == null
}

class FileManagementViewModel(
    private val files: FileManagementRepository,
    private val drafts: FileManagementDraftRepository,
    private val saved: SavedStateHandle,
    cleanupScope: CoroutineScope? = null,
) : ViewModel() {
    private val ticket =
        saved.get<String>("file.management.ticket")
            ?: UUID.randomUUID().toString().also { saved["file.management.ticket"] = it }
    private val mutable =
        MutableStateFlow(
            FileManagementState(
                closed = saved["file.management.closed"] ?: false,
                loading = saved.get<Boolean>("file.management.closed") != true,
            )
        )
    val state = mutable.asStateFlow()
    private val cleanup =
        cleanupScope
            ?: CoroutineScope(SupervisorJob() + viewModelScope.coroutineContext.minusKey(Job))
    private var record = FileManagementDraft()
    private var stopped = false
    private var entries = emptyList<ManagedFile>()
    private var generation = 0L
    private var scan: Job? = null
    private val navigationGate = Mutex()
    private var initialized = false
    private var loadFailed = false
    private val writes = Channel<FileManagementDraft>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (draft in writes) try {
            drafts.write(ticket, draft)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure(error)
        }
    }

    init {
        if (state.value.closed) {
            stop()
            release()
        } else initialize()
    }

    private fun initialize() {
        mutable.value = state.value.copy(loading = true, error = null)
        scan = viewModelScope.launch {
            try {
                val restored = drafts.read(ticket) ?: FileManagementDraft()
                currentCoroutineContext().ensureActive()
                record = restored
                initialized = true
                mutable.value =
                    state.value.copy(
                        query = record.query,
                        queryStart =
                            (saved.get<Int>("file.management.queryStart") ?: record.query.length)
                                .coerceIn(0, record.query.length),
                        queryEnd =
                            (saved.get<Int>("file.management.queryEnd") ?: record.query.length)
                                .coerceIn(0, record.query.length),
                        navigation = record.navigation,
                    )
                scan = null
                load(record.directory, clearQuery = false)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
                mutable.value = state.value.copy(loading = false)
            }
        }
    }

    private fun update(next: FileManagementDraft) {
        record = next.copy(revision = record.revision + 1)
        writes.trySend(record)
    }

    private fun load(path: String?, clearQuery: Boolean = true) {
        val token = ++generation
        scan?.cancel()
        loadFailed = false
        entries = emptyList()
        if (clearQuery) {
            saved["file.management.queryStart"] = 0
            saved["file.management.queryEnd"] = 0
        }
        update(record.copy(directory = path, query = if (clearQuery) "" else record.query))
        mutable.value =
            state.value.copy(
                directory = path,
                loading = true,
                error = null,
                rows = emptyList(),
                query = record.query,
                queryStart = if (clearQuery) 0 else state.value.queryStart,
                queryEnd = if (clearQuery) 0 else state.value.queryEnd,
            )
        scan = viewModelScope.launch {
            try {
                val snapshot = files.list(path)
                currentCoroutineContext().ensureActive()
                if (token != generation || stopped) return@launch
                entries = snapshot.entries
                update(record.copy(directory = snapshot.directory))
                mutable.value =
                    state.value.copy(
                        root = snapshot.root,
                        directory = snapshot.directory,
                        crumbs = snapshot.crumbs,
                        loaded = true,
                        loading = false,
                    )
                filter()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == generation) {
                    loadFailed = true
                    failure(error)
                    mutable.value = state.value.copy(loading = false)
                }
            }
        }
    }

    fun navigate(path: String?) {
        if (state.value.canAct) load(path)
    }

    fun root() {
        navigate(state.value.root)
    }

    fun back() {
        if (!state.value.canAct) return
        val crumbs = state.value.crumbs
        if (crumbs.size > 1) navigate(crumbs[crumbs.lastIndex - 1].path) else close()
    }

    fun query(value: String, start: Int = value.length, end: Int = start) {
        if (!state.value.canAct || state.value.loading) return
        saved["file.management.queryStart"] = start.coerceIn(0, value.length)
        saved["file.management.queryEnd"] = end.coerceIn(0, value.length)
        update(record.copy(query = value))
        mutable.value =
            state.value.copy(
                query = value,
                queryStart = saved.get<Int>("file.management.queryStart")!!,
                queryEnd = saved.get<Int>("file.management.queryEnd")!!,
                error = null,
            )
        filter()
    }

    private fun filter() {
        mutable.value =
            state.value.copy(
                rows =
                    entries.filter {
                        it.kind == ManagedFileKind.Parent || it.name.contains(state.value.query)
                    }
            )
    }

    fun click(path: String) {
        if (!state.value.canAct || state.value.loading) return
        val item = state.value.rows.find { it.path == path } ?: return
        if (item.kind != ManagedFileKind.File) {
            navigate(item.path)
            return
        }
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val uri = files.open(path)
                currentCoroutineContext().ensureActive()
                val navigation = ManagedFileOpen(UUID.randomUUID().toString(), uri)
                update(record.copy(navigation = navigation))
                mutable.value = state.value.copy(navigation = navigation)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun delete(path: String) {
        if (!state.value.canAct || state.value.loading) return
        val item =
            state.value.rows.find { it.path == path && it.kind != ManagedFileKind.Parent } ?: return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                files.delete(item.path)
                currentCoroutineContext().ensureActive()
                load(state.value.directory)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun retry() {
        if (stopped || state.value.loading || state.value.busy || state.value.closed) return
        if (!initialized) {
            initialize()
            return
        }
        if (!state.value.loaded || loadFailed) {
            load(record.directory, clearQuery = false)
            return
        }
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                flush()
                currentCoroutineContext().ensureActive()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun openFailure(error: Exception) {
        failure(error)
    }

    suspend fun consumeOpen(token: String, canDeliver: () -> Boolean): ManagedFileOpen? =
        navigationGate.withLock {
            currentCoroutineContext().ensureActive()
            val navigation = record.navigation?.takeIf { it.token == token } ?: return@withLock null
            if (stopped || !canDeliver()) return@withLock null
            flush()
            currentCoroutineContext().ensureActive()
            if (stopped || !canDeliver() || record.navigation?.token != token) return@withLock null
            val before = record
            val claimed = before.copy(navigation = null, revision = before.revision + 1)
            var committed = false
            try {
                drafts.write(ticket, claimed)
                committed = true
                currentCoroutineContext().ensureActive()
                if (stopped || !canDeliver()) {
                    val rollback = before.copy(revision = claimed.revision + 1)
                    withContext(NonCancellable) { drafts.write(ticket, rollback) }
                    if (!stopped) {
                        record = rollback
                        mutable.value = state.value.copy(navigation = navigation)
                    }
                    return@withLock null
                }
                record = claimed
                mutable.value = state.value.copy(navigation = null)
                navigation
            } catch (error: Exception) {
                if (committed || error is CancellationException) {
                    val rollback = before.copy(revision = claimed.revision + 1)
                    withContext(NonCancellable) { runCatching { drafts.write(ticket, rollback) } }
                    if (!stopped) {
                        record = rollback
                        mutable.value = state.value.copy(navigation = navigation)
                    }
                }
                throw error
            }
        }

    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
    }

    suspend fun flush() {
        if (initialized) drafts.write(ticket, record)
    }

    fun close() {
        if (state.value.closed) return
        saved["file.management.closed"] = true
        mutable.value = state.value.copy(closed = true)
        stop()
        release()
    }

    private fun release() {
        cleanup.launch { runCatching { drafts.release(ticket) } }
    }

    internal fun stop() {
        stopped = true
        generation++
        writer.cancel()
        writes.close()
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
