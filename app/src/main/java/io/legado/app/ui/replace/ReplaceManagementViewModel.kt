package io.legado.app.ui.replace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.AppReplaceManagementSharingRepository
import io.legado.app.data.repository.ReplaceManagementCheckpoint
import io.legado.app.data.repository.ReplaceManagementExport
import io.legado.app.data.repository.ReplaceManagementPrepared
import io.legado.app.data.repository.ReplaceManagementRepository
import io.legado.app.data.repository.ReplaceManagementRow
import io.legado.app.data.repository.ReplaceManagementSessionRepository
import io.legado.app.data.repository.ReplaceManagementShareFeedback
import io.legado.app.data.repository.ReplaceManagementSharingRepository
import io.legado.app.data.repository.replaceManagementFilter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReplaceManagementAction {
    Add,
    Edit,
    ImportLocal,
    ImportQr,
    ImportUrl,
    ImportInput,
    Export,
    Share,
    Help,
    Groups,
    Copy,
}

enum class ReplaceManagementDialog {
    Delete,
    AddGroup,
    RemoveGroup,
    ImportUrl,
    ExportResult,
    Passphrase,
}

data class ReplaceManagementEffect(val action: ReplaceManagementAction, val nonce: String)

data class ReplaceManagementNative(
    val effect: ReplaceManagementEffect,
    val ruleId: Long? = null,
    val input: String? = null,
    val export: ReplaceManagementExport? = null,
)

data class ReplaceManagementLabels(val enabled: String, val disabled: String, val noGroup: String)

data class ReplaceManagementState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val rows: List<ReplaceManagementRow> = emptyList(),
    val groups: List<String> = emptyList(),
    val query: String = "",
    val queryStart: Int = 0,
    val queryEnd: Int = 0,
    val selected: Set<Long> = emptySet(),
    val dialog: ReplaceManagementDialog? = null,
    val targets: List<Long> = emptyList(),
    val draft: String = "",
    val draftStart: Int = 0,
    val draftEnd: Int = 0,
    val manual: Boolean = false,
    val changed: Boolean = false,
    val dragging: Long? = null,
    val pending: ReplaceManagementEffect? = null,
    val history: List<String> = emptyList(),
    val feedback: ReplaceManagementShareFeedback? = null,
    val waitingNative: Boolean = false,
    val error: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
) {
    val visibleSelection
        get() = rows.filter { it.id in selected }.map { it.id }
}

/** Gesture previews are transient. Only completed selection and modal edits become disk drafts. */
class ReplaceManagementViewModel(
    private val repository: ReplaceManagementRepository,
    private val sessions: ReplaceManagementSessionRepository,
    private val saved: SavedStateHandle,
    private val sharing: ReplaceManagementSharingRepository =
        AppReplaceManagementSharingRepository(),
) : ViewModel() {
    private val id =
        saved.get<String>("replaceManagement.session")
            ?: UUID.randomUUID().toString().also { saved["replaceManagement.session"] = it }
    private val mutable =
        MutableStateFlow(
            ReplaceManagementState(
                changed = saved.get<Boolean>("replaceManagement.changed") == true,
                scrollIndex = saved.get<Int>("replaceManagement.scrollIndex") ?: 0,
                scrollOffset = saved.get<Int>("replaceManagement.scrollOffset") ?: 0,
            )
        )
    val state = mutable.asStateFlow()
    private var checkpoint = ReplaceManagementCheckpoint()
    private var revision = saved.get<Long>("replaceManagement.revision") ?: 0L
    private var labels: ReplaceManagementLabels? = null
    private var epoch = 0L
    private var loading: Job? = null
    private var rowsJob: Job? = null
    private var groupsJob: Job? = null
    private var operation: Job? = null
    private var earlyResult: Pair<String, String?>? = null
    private var selecting: Set<Long>? = null
    private var dragRows: List<ReplaceManagementRow>? = null
    private var buffered: List<ReplaceManagementRow>? = null
    private var target: Pair<Long, Boolean>? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bind(value: ReplaceManagementLabels) {
        if (state.value.loaded) {
            if (labels != value) {
                labels = value
                observe()
            }
            return
        }
        labels = value
        if (loading?.isActive == true) return
        val generation = epoch
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(id)
                currentCoroutineContext().ensureActive()
                if (generation != epoch) return@launch
                revision = maxOf(revision, disk?.revision ?: 0)
                checkpoint = disk ?: checkpoint
                checkpoint =
                    checkpoint.copy(
                        ownedExports =
                            (checkpoint.ownedExports +
                                    listOfNotNull(
                                        checkpoint.exportFile?.path,
                                        checkpoint.pending?.export?.path,
                                    ))
                                .distinct()
                    )
                val manual = repository.manual()
                currentCoroutineContext().ensureActive()
                if (generation != epoch) return@launch
                checkpoint =
                    checkpoint.copy(
                        pending =
                            checkpoint.pending?.takeUnless {
                                saved.get<String>("replaceManagement.delivered") == it.nonce
                            }
                    )
                clearPersistedReturn()
                project()
                mutable.value = state.value.copy(manual = manual)
                observe()
                observeGroups()
                if (state.value.dialog == ReplaceManagementDialog.ImportUrl) loadHistory()
                drainResult()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch) failed(error)
            }
        }
    }

    private fun project() {
        mutable.value =
            state.value.copy(
                loaded = true,
                query = checkpoint.query,
                queryStart = checkpoint.queryStart,
                queryEnd = checkpoint.queryEnd,
                selected = checkpoint.selected.toSet(),
                targets = checkpoint.targets,
                dialog =
                    checkpoint.dialog?.let {
                        runCatching { ReplaceManagementDialog.valueOf(it) }.getOrNull()
                    },
                draft = checkpoint.draft,
                draftStart = checkpoint.draftStart,
                draftEnd = checkpoint.draftEnd,
                pending =
                    checkpoint.pending?.let { value ->
                        runCatching {
                            ReplaceManagementEffect(
                                ReplaceManagementAction.valueOf(value.action),
                                value.nonce,
                            )
                        }
                            .getOrNull()
                    },
                feedback = checkpoint.feedback,
                waitingNative = saved.get<String>("replaceManagement.waitingNonce") != null,
            )
    }

    private fun observe() {
        rowsJob?.cancel()
        val generation = epoch
        val values = checkNotNull(labels)
        val filter =
            replaceManagementFilter(
                checkpoint.query,
                values.enabled,
                values.disabled,
                values.noGroup,
            )
        rowsJob = viewModelScope.launch {
            var first = true
            try {
                repository.rows(filter).collect { rows ->
                    currentCoroutineContext().ensureActive()
                    if (generation != epoch) return@collect
                    if (!first) changed()
                    first = false
                    if (selecting != null || dragRows != null) buffered = rows
                    else mutable.value = state.value.copy(rows = rows)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch) failed(error)
            }
        }
    }

    private fun observeGroups() {
        groupsJob?.cancel()
        val generation = epoch
        groupsJob = viewModelScope.launch {
            try {
                repository.groups().collect { values ->
                    currentCoroutineContext().ensureActive()
                    if (generation == epoch) mutable.value = state.value.copy(groups = values)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch) failed(error)
            }
        }
    }

    private fun editable() = state.value.loaded && !state.value.busy && checkpoint.pending == null

    private fun persist() {
        revision++
        saved["replaceManagement.revision"] = revision
        checkpoint = checkpoint.copy(revision = revision)
        val value = checkpoint
        val generation = epoch
        viewModelScope.launch {
            try {
                sessions.write(id, value)
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch && revision == value.revision) failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        mutable.value =
            state.value.copy(error = error.localizedMessage ?: error.javaClass.simpleName)
    }

    fun changed() {
        saved["replaceManagement.changed"] = true
        mutable.value = state.value.copy(changed = true)
    }

    fun query(text: String, start: Int = text.length, end: Int = start) {
        if (!editable()) return
        cancelGesture()
        val changed = text != checkpoint.query
        checkpoint =
            checkpoint.copy(
                query = text,
                queryStart = start.coerceIn(0, text.length),
                queryEnd = end.coerceIn(0, text.length),
            )
        project()
        persist()
        if (changed) observe()
    }

    fun selected(id: Long, checked: Boolean) {
        if (!editable() || state.value.rows.none { it.id == id }) return
        val values = checkpoint.selected.toMutableSet()
        if (checked) values.add(id) else values.remove(id)
        selection(values)
    }

    private fun selection(values: Set<Long>) {
        if (!editable()) return
        cancelGesture()
        checkpoint = checkpoint.copy(selected = values.toList())
        project()
        persist()
    }

    fun selectAll() = selection(checkpoint.selected.toSet() + state.value.rows.map { it.id })

    fun invertSelection() {
        val values = checkpoint.selected.toMutableSet()
        state.value.rows.forEach { if (!values.add(it.id)) values.remove(it.id) }
        selection(values)
    }

    fun selectInterval() {
        val indices =
            state.value.rows.indices.filter { state.value.rows[it].id in checkpoint.selected }
        if (indices.isNotEmpty())
            selection(
                checkpoint.selected.toSet() +
                    state.value.rows.subList(indices.first(), indices.last() + 1).map { it.id }
            )
    }

    fun beginSelection(): Boolean {
        if (!editable() || selecting != null || dragRows != null) return false
        selecting = checkpoint.selected.toSet()
        return true
    }

    fun previewSelection(ids: Set<Long>) {
        val baseline = selecting ?: return
        val values = ids.intersect(state.value.rows.map { it.id }.toSet())
        mutable.value = state.value.copy(selected = (baseline - values) + (values - baseline))
    }

    fun selectionRange(first: Long, last: Long) {
        val rows = state.value.rows
        val start = rows.indexOfFirst { it.id == first }
        val end = rows.indexOfFirst { it.id == last }
        if (start >= 0 && end >= 0)
            previewSelection(
                rows.subList(minOf(start, end), maxOf(start, end) + 1).map { it.id }.toSet()
            )
    }

    fun finishSelection() {
        if (selecting == null) return
        checkpoint = checkpoint.copy(selected = state.value.selected.toList())
        selecting = null
        buffered?.let { mutable.value = state.value.copy(rows = it) }
        buffered = null
        project()
        persist()
    }

    fun beginDrag(id: Long): Boolean {
        if (
            !editable() ||
                selecting != null ||
                dragRows != null ||
                state.value.rows.none { it.id == id }
        )
            return false
        dragRows = state.value.rows
        target = null
        mutable.value = state.value.copy(dragging = id)
        return true
    }

    fun dragTo(id: Long, after: Boolean) {
        val rows = dragRows ?: return
        val moving = state.value.dragging ?: return
        if (moving == id || rows.none { it.id == id }) return
        val values = rows.filter { it.id != moving }.toMutableList()
        val index = values.indexOfFirst { it.id == id }
        values.add(index + if (after) 1 else 0, rows.first { it.id == moving })
        target = id to after
        mutable.value = state.value.copy(rows = values)
    }

    fun finishDrag() {
        val moving = state.value.dragging ?: return
        val destination = target
        val changed = state.value.rows.map { it.id } != dragRows?.map { it.id }
        cancelGesture()
        if (changed && destination != null)
            mutate { repository.move(moving, destination.first, destination.second) }
    }

    fun cancelGesture() {
        if (selecting != null)
            mutable.value = state.value.copy(selected = checkpoint.selected.toSet())
        val rows = buffered ?: dragRows
        if (rows != null) mutable.value = state.value.copy(rows = rows)
        mutable.value = state.value.copy(dragging = null)
        selecting = null
        dragRows = null
        buffered = null
        target = null
    }

    fun enabled(ids: List<Long>, value: Boolean) = mutate { repository.enabled(ids, value) }

    fun edge(ids: List<Long>, top: Boolean) = mutate { repository.edge(ids, top) }

    fun toggleManual() = mutate {
        val manual = !repository.manual()
        repository.manual(manual)
        currentCoroutineContext().ensureActive()
        mutable.value = state.value.copy(manual = manual)
    }

    private fun mutate(block: suspend () -> Unit) {
        if (!editable()) return
        cancelGesture()
        val generation = epoch
        mutable.value = state.value.copy(busy = true, error = null)
        changed()
        operation = viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
                if (generation == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    drainResult()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            }
        }
    }

    fun dialog(kind: ReplaceManagementDialog, ids: List<Long> = state.value.visibleSelection) {
        if (!editable()) return
        cancelGesture()
        checkpoint =
            checkpoint.copy(
                dialog = kind.name,
                targets = ids.distinct(),
                draft = "",
                draftStart = 0,
                draftEnd = 0,
            )
        project()
        persist()
        if (kind == ReplaceManagementDialog.ImportUrl) loadHistory()
    }

    fun draft(text: String, start: Int = text.length, end: Int = start) {
        if (!editable() || state.value.dialog == null) return
        checkpoint =
            checkpoint.copy(
                draft = text,
                draftStart = start.coerceIn(0, text.length),
                draftEnd = end.coerceIn(0, text.length),
            )
        project()
        persist()
    }

    fun cancelDialog() {
        if (!editable()) return
        checkpoint =
            checkpoint.copy(
                dialog = null,
                targets = emptyList(),
                draft = "",
                draftStart = 0,
                draftEnd = 0,
            )
        project()
        persist()
    }

    fun confirmDialog() {
        val kind = state.value.dialog ?: return
        val ids = checkpoint.targets
        val text = checkpoint.draft
        if (kind == ReplaceManagementDialog.ImportUrl) {
            effect(ReplaceManagementAction.ImportUrl, input = text)
            return
        }
        if (kind != ReplaceManagementDialog.Delete && text.isEmpty()) {
            cancelDialog()
            return
        }
        mutate {
            when (kind) {
                ReplaceManagementDialog.Delete -> repository.delete(ids)
                ReplaceManagementDialog.AddGroup -> repository.group(ids, text, true)
                ReplaceManagementDialog.RemoveGroup -> repository.group(ids, text, false)
                else -> Unit
            }
            currentCoroutineContext().ensureActive()
            checkpoint =
                checkpoint.copy(
                    dialog = null,
                    targets = emptyList(),
                    draft = "",
                    draftStart = 0,
                    draftEnd = 0,
                    selected =
                        if (kind == ReplaceManagementDialog.Delete)
                            checkpoint.selected - ids.toSet()
                        else checkpoint.selected,
                )
            project()
            persist()
        }
    }

    private fun loadHistory() {
        val captured = epoch
        viewModelScope.launch {
            try {
                val history = repository.importHistory()
                currentCoroutineContext().ensureActive()
                if (captured == epoch && state.value.dialog == ReplaceManagementDialog.ImportUrl)
                    mutable.value = state.value.copy(history = history)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (captured == epoch && state.value.dialog == ReplaceManagementDialog.ImportUrl)
                    failed(error)
            }
        }
    }

    fun forgetImport(value: String) = nativeMutate {
        repository.forgetImport(value)
        val history = repository.importHistory()
        currentCoroutineContext().ensureActive()
        mutable.value = state.value.copy(history = history)
    }

    fun effect(
        action: ReplaceManagementAction,
        ruleId: Long? = null,
        input: String? = null,
        returningNonce: String? = null,
    ) {
        if (
            !editable() ||
                (saved.get<String>("replaceManagement.waitingNonce") != null &&
                    returningNonce != saved.get<String>("replaceManagement.waitingNonce"))
        )
            return
        val ids = state.value.visibleSelection
        if (
            action in listOf(ReplaceManagementAction.Export, ReplaceManagementAction.Share) &&
                ids.isEmpty() &&
                action == ReplaceManagementAction.Share
        )
            return
        if (action == ReplaceManagementAction.Edit && ruleId == null) return
        if (action == ReplaceManagementAction.Edit) changed()
        val captured = epoch
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            var ownedExport: ReplaceManagementExport? = null
            try {
                val exported =
                    if (
                        action in
                            listOf(ReplaceManagementAction.Export, ReplaceManagementAction.Share)
                    )
                        repository.export(ids)
                    else null
                ownedExport = exported
                if (action == ReplaceManagementAction.ImportUrl)
                    repository.rememberImport(input.orEmpty())
                currentCoroutineContext().ensureActive()
                if (captured != epoch) return@launch
                val prepared =
                    ReplaceManagementPrepared(
                        action.name,
                        UUID.randomUUID().toString(),
                        ruleId,
                        input,
                        exported,
                        returningNonce,
                    )
                revision++
                saved["replaceManagement.revision"] = revision
                checkpoint =
                    checkpoint.copy(
                        revision = revision,
                        pending = prepared,
                        dialog = null,
                        draft = "",
                        draftStart = 0,
                        draftEnd = 0,
                        targets = emptyList(),
                        exportFile = exported ?: checkpoint.exportFile,
                        ownedExports =
                            (checkpoint.ownedExports + listOfNotNull(exported?.path)).distinct(),
                    )
                ownedExport = null // The private pending checkpoint now owns the file, including
                // write-failure retry.
                sessions.write(id, checkpoint)
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    clearPersistedReturn()
                    mutable.value = state.value.copy(busy = false, error = null)
                    project()
                    drainResult()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            } finally {
                ownedExport?.let { export ->
                    withContext(Dispatchers.IO + NonCancellable) {
                        repository.releaseExport(export.path)
                    }
                }
            }
        }
    }

    fun native(nonce: String): ReplaceManagementNative? =
        checkpoint.pending
            ?.takeIf { it.nonce == nonce && state.value.pending?.nonce == nonce }
            ?.let {
                ReplaceManagementNative(
                    checkNotNull(state.value.pending),
                    it.ruleId,
                    it.input,
                    it.export,
                )
            }

    fun delivered(nonce: String): Boolean {
        if (state.value.pending?.nonce != nonce) return false
        val action = state.value.pending!!.action
        if (
            action in
                listOf(
                    ReplaceManagementAction.Export,
                    ReplaceManagementAction.ImportLocal,
                    ReplaceManagementAction.ImportQr,
                )
        ) {
            saved["replaceManagement.waitingNonce"] = nonce
            saved["replaceManagement.waitingAction"] = action.name
        }
        saved["replaceManagement.delivered"] = nonce
        checkpoint = checkpoint.copy(pending = null)
        project()
        persist()
        return true
    }

    fun waiting(action: ReplaceManagementAction): String? =
        saved.get<String>("replaceManagement.waitingNonce").takeIf {
            saved.get<String>("replaceManagement.waitingAction") == action.name
        }

    private fun clearPersistedReturn() {
        val waiting = saved.get<String>("replaceManagement.waitingNonce") ?: return
        if (checkpoint.returnedNonce == waiting || checkpoint.pending?.returningNonce == waiting) {
            saved.remove<String>("replaceManagement.waitingNonce")
            saved.remove<String>("replaceManagement.waitingAction")
        }
    }

    fun returned(nonce: String, input: String?) {
        if (saved.get<String>("replaceManagement.waitingNonce") != nonce) return
        if (!state.value.loaded) {
            earlyResult = nonce to input
            return
        }
        if (state.value.busy) {
            if (earlyResult == null) earlyResult = nonce to input
            return
        }
        val action = saved.get<String>("replaceManagement.waitingAction") ?: return
        if (input != null && action != ReplaceManagementAction.Export.name) {
            effect(ReplaceManagementAction.ImportInput, input = input, returningNonce = nonce)
            return
        }
        val captured = epoch
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val feedback = input?.let { sharing.feedback(it) }
                currentCoroutineContext().ensureActive()
                if (captured != epoch) return@launch
                revision++
                saved["replaceManagement.revision"] = revision
                checkpoint =
                    checkpoint.copy(
                        revision = revision,
                        returnedNonce = nonce,
                        feedback = feedback,
                        dialog =
                            if (feedback != null) ReplaceManagementDialog.ExportResult.name
                            else null,
                        draft = feedback?.url.orEmpty(),
                        draftStart = 0,
                        draftEnd = feedback?.url?.length ?: 0,
                    )
                sessions.write(id, checkpoint)
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    clearPersistedReturn()
                    mutable.value = state.value.copy(busy = false, error = null)
                    project()
                    drainResult()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            }
        }
    }

    fun passphrase() {
        val feedback = checkpoint.feedback?.takeIf { it.canEncode } ?: return
        nativeMutate {
            val text = sharing.passphrase(feedback.url)
            currentCoroutineContext().ensureActive()
            checkpoint =
                checkpoint.copy(
                    feedback = feedback.copy(passphrase = text),
                    dialog = ReplaceManagementDialog.Passphrase.name,
                    draft = text,
                    draftStart = 0,
                    draftEnd = text.length,
                )
            project()
            persist()
        }
    }

    fun copyFeedback() {
        val feedback = checkpoint.feedback ?: return
        effect(
            ReplaceManagementAction.Copy,
            input =
                if (state.value.dialog == ReplaceManagementDialog.Passphrase) feedback.passphrase
                else feedback.url,
        )
    }

    private fun nativeMutate(block: suspend () -> Unit) {
        if (!editable()) return
        val captured = epoch
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    drainResult()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            }
        }
    }

    private fun drainResult() {
        if (!state.value.loaded || state.value.busy || state.value.pending != null) return
        earlyResult?.also {
            earlyResult = null
            returned(it.first, it.second)
        }
    }

    fun nativeFailed(message: String) {
        mutable.value = state.value.copy(error = message)
    }

    fun scroll(index: Int, offset: Int) {
        val position = index.coerceAtLeast(0)
        val pixels = offset.coerceAtLeast(0)
        saved["replaceManagement.scrollIndex"] = position
        saved["replaceManagement.scrollOffset"] = pixels
        mutable.value = state.value.copy(scrollIndex = position, scrollOffset = pixels)
    }

    fun retry() {
        if (state.value.busy) return
        mutable.value = state.value.copy(error = null)
        if (!state.value.loaded) {
            labels?.let(::bind)
            return
        }
        val captured = epoch
        mutable.value = state.value.copy(busy = true)
        operation = viewModelScope.launch {
            try {
                sessions.write(id, checkpoint)
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    clearPersistedReturn()
                    mutable.value = state.value.copy(busy = false)
                    project()
                    observe()
                    observeGroups()
                    drainResult()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (captured == epoch) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            }
        }
    }

    suspend fun flush() {
        sessions.write(id, checkpoint)
    }

    fun stop() {
        epoch++
        cancelGesture()
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        val owned =
            (checkpoint.ownedExports +
                    listOfNotNull(checkpoint.exportFile?.path, checkpoint.pending?.export?.path))
                .distinct()
        cleanup.launch {
            runCatching { sessions.release(id) }
            owned.forEach { path -> runCatching { repository.releaseExport(path) } }
        }
        super.onCleared()
    }
}
