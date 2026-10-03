package io.legado.app.ui.book.source.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.utils.moveRelativeTo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface SourceManagerPreferences {
    var showStatus: Boolean
    var blockNavigation: Boolean
}

internal class AppSourceManagerPreferences : SourceManagerPreferences {
    override var showStatus: Boolean
        get() = AppConfig.showSourceCheckStatus
        set(value) {
            AppConfig.showSourceCheckStatus = value
        }

    override var blockNavigation: Boolean
        get() = AppConfig.blockSourceNavigation
        set(value) {
            AppConfig.blockSourceNavigation = value
        }
}

internal class BookSourceManagerViewModel(
    private val repository: BookSourceManagerRepository,
    private val store: SourceManagerSessionStorage,
    private val preferences: SourceManagerPreferences = AppSourceManagerPreferences(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            SourceManagerState(
                showStatus = preferences.showStatus,
                blockNavigation = preferences.blockNavigation,
            )
        )
    val state: StateFlow<SourceManagerState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var session = SourceManagerSession()
    private var sourceJob: Job? = null
    private var restoringSession = true
    @Volatile private var terminated = false
    private var filteredRows = emptyList<SourceManagerRow>()
    private var dragStartRows = emptyList<SourceManagerRow>()
    private var dragRows = emptyList<SourceManagerRow>()

    init {
        viewModelScope.launch {
            operationMutex.withLock { restoreSession() }
            watchQuery()
            launch {
                runCatching { repository.importHistory() }
                    .onSuccess { history ->
                        mutableState.update { it.copy(importHistory = history.toList()) }
                    }
                    .onFailure(::showFailure)
            }
            launch {
                repository
                    .allSources()
                    .catch { showFailure(it) }
                    .collect { rows ->
                        val existingKeys = rows.map { it.url }.toSet()
                        if (state.value.selected.any { it !in existingKeys }) {
                            edit { it.copy(selected = it.selected.intersect(existingKeys)) }
                        }
                    }
            }
            launch {
                repository
                    .groups()
                    .catch { showFailure(it) }
                    .collect { groups ->
                        mutableState.update { it.copy(groups = groups.toList()) }
                        if (isMissingBookSourceGroupFilter(state.value.query, groups.toSet()))
                            query("")
                    }
            }
            launch {
                repository
                    .counts()
                    .catch { showFailure(it) }
                    .collect { counts ->
                        mutableState.update { it.copy(counts = counts.toMap()) }
                    }
            }
        }
    }

    private suspend fun restoreSession() {
        try {
            session = withContext(ioDispatcher) { store.read() }
            val export =
                session.exportPath?.let { path ->
                    SourceExport(
                        File(path),
                        session.exportName.orEmpty(),
                        session.exportMime.orEmpty(),
                    )
                }
            val pendingEffect =
                session.effectId
                    ?.takeUnless { it in session.receipts }
                    ?.let { id ->
                        SourceManagerEffect(
                            id,
                            session.effectAction.orEmpty(),
                            session.effectKey,
                            export,
                            session.effectKeys,
                        )
                    }
            mutableState.update {
                it.copy(
                    query = session.query,
                    selected = session.selected.toSet(),
                    sort = session.sort,
                    ascending = session.ascending,
                    byDomain = session.domain,
                    status = if (it.showStatus) session.status else "",
                    dialog = session.dialog,
                    draft = session.draft,
                    effect = pendingEffect,
                    feedback = session.feedback,
                    error = if (session.pendingOperation) "上次操作被中断，请检查结果后重试" else null,
                )
            }
            // An interrupted operation is never automatically replayed. This prevents a second
            // ordering/deletion effect when the process died after its database transaction.
            session = session.copy(pendingOperation = false)
        } catch (failure: Exception) {
            session = SourceManagerSession()
            showFailure(failure)
        } finally {
            restoringSession = false
        }
    }

    private fun showFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        mutableState.update { it.copy(loading = false, error = failure.localizedMessage ?: "操作失败") }
    }

    private fun publishRows() {
        mutableState.update { current ->
            val rows = filteredRows.filter {
                !current.showStatus || current.status.isEmpty() || it.checkStatus == current.status
            }
            val latestByKey = filteredRows.associateBy { it.url }
            val displayedRows =
                if (current.draggingKey != null) {
                    dragRows.map { latestByKey[it.url] ?: it }
                } else {
                    sortSourceManagerRows(rows, current.sort, current.ascending, current.byDomain)
                }
            current.copy(
                rows = displayedRows,
                loading = false,
            )
        }
    }

    private fun watchQuery() {
        sourceJob?.cancel()
        val query = state.value.query
        sourceJob = viewModelScope.launch {
            repository
                .sources(query)
                .catch { showFailure(it) }
                .collect {
                    filteredRows = it
                    publishRows()
                }
        }
    }

    private suspend fun persist() {
        val current = state.value
        val effect = current.effect
        // Build the immutable disk snapshot on Main. The mutex serializes its IO write, so
        // older completions cannot replace a newer draft or an accepted operation receipt.
        session =
            session.copy(
                query = current.query,
                selected = current.selected.toSet(),
                sort = current.sort,
                ascending = current.ascending,
                domain = current.byDomain,
                status = current.status,
                dialog = current.dialog,
                draft = current.draft,
                effectId = effect?.id,
                effectAction = effect?.action,
                effectKey = effect?.key.orEmpty(),
                effectKeys = effect?.keys.orEmpty(),
                exportPath = effect?.export?.file?.path,
                exportName = effect?.export?.name,
                exportMime = effect?.export?.mime,
                feedback = current.feedback,
            )
        val snapshot = session
        withContext(ioDispatcher + NonCancellable) { store.write(snapshot) }
    }

    private fun edit(transform: (SourceManagerState) -> SourceManagerState) {
        if (terminated || state.value.busy || restoringSession) return
        val previous = state.value
        mutableState.update(transform)
        val updated = state.value
        if (previous.query != updated.query) {
            watchQuery()
        } else if (
            previous.sort != updated.sort ||
                previous.ascending != updated.ascending ||
                previous.byDomain != updated.byDomain ||
                previous.status != updated.status ||
                previous.showStatus != updated.showStatus
        ) {
            publishRows()
        }
        // Text input and checkboxes respond immediately. Disk work is serialized separately;
        // operation() saves the current snapshot before entering its accepted mutation segment.
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    persist()
                } catch (failure: Exception) {
                    showFailure(failure)
                }
            }
        }
    }

    fun query(value: String) = edit { it.copy(query = value, loading = true) }

    fun sort(value: BookSourceSort) = edit { it.copy(sort = value) }

    fun ascending() = edit { it.copy(ascending = !it.ascending) }

    fun domain() = edit { it.copy(byDomain = !it.byDomain) }

    fun status(value: String) = edit { it.copy(status = value) }

    fun showStatus() = edit {
        preferences.showStatus = !it.showStatus
        it.copy(showStatus = !it.showStatus, status = "")
    }

    fun blockNavigation() = edit {
        preferences.blockNavigation = !it.blockNavigation
        it.copy(blockNavigation = !it.blockNavigation)
    }

    fun toggle(key: String) = edit {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAll() = edit { it.copy(selected = it.selected + it.rows.map { row -> row.url }) }

    fun invert() = edit {
        val visibleKeys = it.rows.map { row -> row.url }.toSet()
        it.copy(selected = (it.selected - visibleKeys) + (visibleKeys - it.selected))
    }

    fun interval() = edit { current ->
        val positions = current.rows.indices.filter { current.rows[it].url in current.selected }
        if (positions.isEmpty()) current
        else
            current.copy(
                selected =
                    current.selected +
                        current.rows.subList(positions.first(), positions.last() + 1).map { it.url }
            )
    }

    fun slide(keys: Set<String>, selectedAtStart: Set<String>) = edit {
        it.copy(selected = (selectedAtStart - keys) + (keys - selectedAtStart))
    }

    fun retry() {
        mutableState.update { it.copy(error = null, loading = true) }
        watchQuery()
    }

    private fun selectedKeys(): List<String> {
        val current = state.value
        // Hidden checked IDs survive filtering, while batch targets and counts stay visible-only.
        return current.rows.filter { it.url in current.selected }.map { it.url }
    }

    fun open(dialog: SourceManagerDialog, key: String? = null) = edit {
        session = session.copy(dialogKeys = key?.let(::listOf) ?: selectedKeys())
        it.copy(
            dialog = dialog,
            draft =
                if (dialog == SourceManagerDialog.CHECK) io.legado.app.model.CheckSource.keyword
                else "",
        )
    }

    fun draft(value: String) = edit { it.copy(draft = value) }

    fun forgetImport(value: String) {
        viewModelScope.launch {
            operationMutex.withLock {
                if (state.value.busy) return@withLock
                try {
                    repository.forgetImport(value)
                    val history = repository.importHistory()
                    mutableState.update { it.copy(importHistory = history.toList()) }
                } catch (failure: Exception) {
                    showFailure(failure)
                }
            }
        }
    }

    fun dismiss() = edit { it.copy(dialog = null, draft = "", feedback = null) }

    fun confirm() {
        val current = state.value
        val keys = session.dialogKeys.toList()
        when (current.dialog) {
            SourceManagerDialog.DELETE -> mutate(SourceMutation.DELETE, keys)
            SourceManagerDialog.ADD_GROUP ->
                if (current.draft.isNotBlank())
                    mutate(SourceMutation.ADD_GROUP, keys, current.draft)
            SourceManagerDialog.REMOVE_GROUP ->
                if (current.draft.isNotBlank())
                    mutate(SourceMutation.REMOVE_GROUP, keys, current.draft)
            SourceManagerDialog.IMPORT ->
                operation {
                    repository.rememberImport(current.draft)
                    mutableState.update {
                        it.copy(
                            effect =
                                SourceManagerEffect(
                                    UUID.randomUUID().toString(),
                                    "import",
                                    current.draft,
                                )
                        )
                    }
                }
            SourceManagerDialog.CHECK -> if (keys.isNotEmpty()) effect("check", current.draft, keys)
            SourceManagerDialog.EXPORT_SUCCESS ->
                state.value.feedback?.let { effect("copy", it.url) }
            SourceManagerDialog.PASSPHRASE ->
                state.value.feedback?.passphrase?.let { effect("copy", it) }
            null -> Unit
        }
    }

    private fun operation(block: suspend () -> Unit) {
        // Reject rapid second submissions before launching. A mutex alone would queue and repeat
        // the same destructive action once the first request had completed.
        if (terminated || state.value.busy || state.value.loading) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    withContext(NonCancellable) {
                        session = session.copy(pendingOperation = true)
                        persist()
                        block()
                        session = session.copy(pendingOperation = false)
                        mutableState.update { it.copy(dialog = null, draft = "") }
                        persist()
                    }
                } catch (failure: Exception) {
                    session = session.copy(pendingOperation = false)
                    showFailure(failure)
                } finally {
                    mutableState.update { it.copy(busy = false) }
                    runCatching { persist() }.onFailure { showFailure(it) }
                }
            }
        }
    }

    fun mutate(action: SourceMutation, keys: List<String> = selectedKeys(), value: String = "") {
        if (keys.isEmpty()) return
        operation { repository.mutate(keys.toList(), action, value) }
    }

    fun beginDrag(key: String) {
        if (!state.value.canMove || state.value.draggingKey != null) return
        dragStartRows = state.value.rows.toList()
        dragRows = dragStartRows
        mutableState.update { it.copy(draggingKey = key) }
    }

    fun previewDrag(targetIndex: Int) {
        val key = state.value.draggingKey ?: return
        val currentIndex = dragRows.indexOfFirst { it.url == key }
        val target = dragRows.getOrNull(targetIndex) ?: return
        if (currentIndex == targetIndex) return
        dragRows = moveRelativeTo(dragRows, key, target.url, targetIndex > currentIndex) { it.url }
        publishRows()
    }

    fun finishDrag(cancelled: Boolean = false) {
        val key = state.value.draggingKey ?: return
        val startIndex = dragStartRows.indexOfFirst { it.url == key }
        val endIndex = dragRows.indexOfFirst { it.url == key }
        val after = endIndex > startIndex
        val target = dragRows.getOrNull(if (after) endIndex - 1 else endIndex + 1)
        mutableState.update { it.copy(draggingKey = null) }
        if (
            !cancelled &&
                startIndex >= 0 &&
                endIndex >= 0 &&
                endIndex != startIndex &&
                target != null
        ) {
            move(key, target.url, after)
        }
        publishRows()
    }

    fun move(key: String, target: String, after: Boolean) {
        if (!state.value.canMove) return
        val ascending = state.value.ascending
        operation { repository.move(key, target, if (ascending) after else !after) }
    }

    fun step(key: String, delta: Int) {
        val rows = state.value.rows
        val index = rows.indexOfFirst { it.url == key }
        val target = rows.getOrNull(index + delta) ?: return
        move(key, target.url, delta > 0)
    }

    fun export(share: Boolean) {
        val keys = selectedKeys()
        if (keys.isEmpty()) return
        operation {
            val output = repository.export(keys)
            mutableState.update {
                it.copy(
                    effect =
                        SourceManagerEffect(
                            UUID.randomUUID().toString(),
                            if (share) "share" else "export",
                            export = output,
                        )
                )
            }
        }
    }

    fun effect(action: String, key: String = "", keys: List<String> = emptyList()) = edit {
        if (it.effect != null) it
        else
            it.copy(
                dialog = if (action == "check-config") it.dialog else null,
                draft = if (action == "check-config") it.draft else "",
                effect =
                    SourceManagerEffect(
                        UUID.randomUUID().toString(),
                        action,
                        key,
                        keys = keys.toList(),
                    ),
            )
    }

    suspend fun prepareEffect(effect: SourceManagerEffect): PreparedSourceManagerEffect {
        currentCoroutineContext().ensureActive()
        return when (effect.action) {
            "search" -> {
                val source = repository.resolve(listOf(effect.key)).singleOrNull() ?: error("书源不存在")
                currentCoroutineContext().ensureActive()
                PreparedSourceManagerEffect(effect, searchSource = source)
            }
            "check" -> {
                val sources = repository.resolve(effect.keys)
                currentCoroutineContext().ensureActive()
                if (sources.isEmpty()) error("没有可检验的书源")
                val sessionId = Debug.tryStartCheckSession() ?: error("书源调试通道占用中，请稍后重试")
                var check: CheckSource.PreparedCheck? = null
                try {
                    check = CheckSource.prepare(sources, sessionId)
                    currentCoroutineContext().ensureActive()
                    PreparedSourceManagerEffect(effect, check = check)
                } catch (failure: Exception) {
                    check?.let(CheckSource::release)
                    throw failure
                }
            }
            else -> PreparedSourceManagerEffect(effect)
        }
    }

    fun releasePreparedEffect(prepared: PreparedSourceManagerEffect) {
        prepared.check?.let(CheckSource::release)
    }

    suspend fun deliverEffect(id: String, ready: () -> Boolean, deliver: () -> Unit): Boolean =
        operationMutex.withLock {
            if (terminated || id in session.receipts || state.value.effect?.id != id)
                return@withLock false
            withContext(NonCancellable) {
                val previousSession = session
                val previousEffect = state.value.effect
                var deliveryStarted = false
                mutableState.update { it.copy(busy = true) }
                try {
                    session = session.copy(receipts = session.receipts + id)
                    mutableState.update { it.copy(effect = null) }
                    persist()
                    // Disk acceptance may suspend. Recheck the current owner after it completes,
                    // then launch synchronously on Main with no suspension in the gate/launch gap.
                    if (terminated || !ready()) {
                        session = previousSession
                        mutableState.update { it.copy(effect = previousEffect) }
                        persist()
                        return@withContext false
                    }
                    deliveryStarted = true
                    deliver()
                    true
                } catch (failure: Exception) {
                    if (!deliveryStarted) {
                        session = previousSession
                        mutableState.update { it.copy(effect = previousEffect) }
                        persist()
                    }
                    throw failure
                } finally {
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }

    suspend fun acceptEffect(id: String): Boolean = deliverEffect(id, { true }, {})

    fun exportReturned(url: String) = preparePrompt {
        val feedback = repository.feedback(url)
        mutableState.update {
            it.copy(feedback = feedback, dialog = SourceManagerDialog.EXPORT_SUCCESS, draft = url)
        }
    }

    fun showPassphrase() {
        val feedback = state.value.feedback ?: return
        if (!feedback.canSharePassphrase) return
        preparePrompt {
            val text = repository.passphrase(feedback.url)
            mutableState.update {
                it.copy(
                    feedback = feedback.copy(passphrase = text),
                    dialog = SourceManagerDialog.PASSPHRASE,
                    draft = text,
                )
            }
        }
    }

    private fun preparePrompt(prepare: suspend () -> Unit) {
        if (terminated || state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            operationMutex.withLock {
                try {
                    withContext(NonCancellable) {
                        prepare()
                        persist()
                    }
                } catch (failure: Exception) {
                    showFailure(failure)
                } finally {
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun checkProgress(message: String?) {
        mutableState.update { it.copy(checkMessage = message) }
    }

    fun debugMessages(messages: Map<String, String>) {
        mutableState.update { it.copy(debugMessages = messages.toMap()) }
    }

    fun hostError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    override fun onCleared() {
        terminated = true
        val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        cleanupScope.launch {
            try {
                // Accepted operations own this same mutex through their final disk receipt.
                // Cleanup runs after those writes, so no late completion can recreate the UUID.
                operationMutex.withLock { store.delete() }
            } finally {
                cleanupScope.cancel()
            }
        }
        super.onCleared()
    }

    companion object {
        fun token(savedState: SavedStateHandle): String {
            val existing = savedState.get<String>("manager-session")
            if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess)
                return existing
            return UUID.randomUUID().toString().also { savedState["manager-session"] = it }
        }
    }
}
