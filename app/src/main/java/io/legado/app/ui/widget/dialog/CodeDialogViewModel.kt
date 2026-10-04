package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.CodeDialogDraft
import io.legado.app.data.repository.CodeDialogRepository
import io.legado.app.data.repository.CodeDialogTransferRepository
import io.legado.app.help.findTextRanges
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CodeDialogAction {
    Save,
    Editor,
    ReplaceRules,
    Effective,
    Manual,
    EditorSaved,
}

internal data class CodeDialogEffect(val id: Long, val action: CodeDialogAction)

internal data class CodeDialogState(
    val original: String = "",
    val alternate: String? = null,
    val loaded: Boolean = false,
    val showingAlternate: Boolean = false,
    val query: String = "",
    val searchOpen: Boolean = false,
    val matchIndex: Int = -1,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val editorPending: Boolean = false,
    val editorReadOnly: Boolean = false,
    val editorPrepared: Boolean = false,
    val editorPath: String? = null,
    val refreshPending: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
    val effects: List<CodeDialogEffect> = emptyList(),
    val matches: List<IntRange> = emptyList(),
) {
    val displayed: String
        get() = if (showingAlternate) alternate ?: original else original

    val busy: Boolean
        get() = editorPending || refreshPending
}

/** Host owns Activity results and callbacks; effects are small and consumed before delivery. */
internal class CodeDialogViewModel(
    private val repository: CodeDialogRepository,
    private val saved: SavedStateHandle,
    private val initialOriginal: String,
    private val initialAlternate: String?,
    val editable: Boolean,
    val sourcePreview: Boolean,
    initialShowAlternate: Boolean = false,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val transfer: CodeDialogTransferRepository? = null,
) : ViewModel() {
    private val session =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private var revision = saved.get<Long>("revision") ?: 0L
    private var effectId = saved.get<Long>("effectId") ?: 0L
    private var previewRequested = saved.get<Boolean>("alternateVisible") ?: initialShowAlternate
    private val mutable =
        MutableStateFlow(
            CodeDialogState(
                showingAlternate = saved.get<Boolean>("alternateVisible") ?: initialShowAlternate,
                query = saved["query"] ?: "",
                searchOpen = saved["searchOpen"] ?: false,
                matchIndex = saved["matchIndex"] ?: -1,
                selectionStart = saved["selectionStart"] ?: 0,
                selectionEnd = saved["selectionEnd"] ?: 0,
                editorPending = saved["editorPending"] ?: false,
                editorReadOnly = saved["editorReadOnly"] ?: false,
                editorPrepared = saved["editorPrepared"] ?: false,
                editorPath = saved["editorPath"],
                finished = saved["finished"] ?: false,
                effects =
                    saved
                        .get<String>("effects")
                        ?.let { GSON.fromJsonArray<CodeDialogEffect>(it).getOrNull() }
                        .orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private val writes = Channel<CodeDialogDraft>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (draft in writes) try {
            repository.write(session, draft)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
        }
    }
    private var loader: Job? = null
    private var searchJob: Job? = null
    private var transferJob: Job? = null
    private var pendingInlineResult: String? = null
    private var durableReturnedDraft = false

    init {
        if (!state.value.finished) load()
    }

    fun load() {
        if (state.value.finished || loader?.isActive == true) return
        loader = viewModelScope.launch {
            try {
                val disk = repository.read(session)
                if (state.value.finished) return@launch
                val original = disk?.original ?: initialOriginal
                val alternate = if (disk != null) disk.alternate else initialAlternate
                durableReturnedDraft =
                    saved.get<Boolean>("editorReturning") == true &&
                        disk != null &&
                        (disk.revision > revision ||
                            saved.get<Long>("editorResultRevision")?.let { disk.revision >= it } ==
                                true)
                revision = maxOf(revision, disk?.revision ?: 0L)
                saved["revision"] = revision
                mutable.value =
                    state.value.copy(
                        original = original,
                        alternate = alternate,
                        loaded = true,
                        showingAlternate = editable && alternate != null && previewRequested,
                        error = null,
                    )
                selection(state.value.selectionStart, state.value.selectionEnd)
                if (disk == null) checkpoint()
                computeSearch(state.value.matchIndex)
                if (saved.get<Boolean>("editorReturning") == true) restoreEditorResult()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
            }
        }
    }

    private fun checkpoint() {
        revision++
        saved["revision"] = revision
        writes.trySend(CodeDialogDraft(state.value.original, state.value.alternate, revision))
    }

    suspend fun flushDraft() {
        if (!state.value.loaded) return
        try {
            repository.write(
                session,
                CodeDialogDraft(state.value.original, state.value.alternate, revision),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
        }
    }

    fun text(value: String, start: Int, end: Int) {
        val previous = state.value
        if (
            !editable ||
                !previous.loaded ||
                previous.showingAlternate ||
                previous.busy ||
                previous.finished
        )
            return
        mutable.value = previous.copy(original = value, error = null)
        selection(start, end)
        checkpoint()
        computeSearch(state.value.matchIndex, selectMatch = false)
    }

    fun selection(start: Int, end: Int) {
        val value = state.value
        val length = value.displayed.length
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(0, length)
        saved["selectionStart"] = a
        saved["selectionEnd"] = b
        mutable.value = value.copy(selectionStart = a, selectionEnd = b)
    }

    fun preview(show: Boolean) {
        if (
            !editable ||
                !state.value.loaded ||
                state.value.busy ||
                state.value.finished ||
                show && state.value.alternate == null
        )
            return
        previewRequested = show
        saved["alternateVisible"] = show
        mutable.value = state.value.copy(showingAlternate = show)
        selection(0, 0)
        search(state.value.query)
    }

    fun alternate(value: String?) {
        if (state.value.finished || !state.value.loaded) return
        val show = value != null && previewRequested
        mutable.value = state.value.copy(alternate = value, showingAlternate = show)
        selection(state.value.selectionStart, state.value.selectionEnd)
        checkpoint()
        search(state.value.query)
    }

    fun refreshPending(value: Boolean) {
        if (!state.value.finished) mutable.value = state.value.copy(refreshPending = value)
    }

    fun searchOpen(value: Boolean) {
        saved["searchOpen"] = value
        mutable.value = state.value.copy(searchOpen = value)
        search(state.value.query)
    }

    fun search(query: String) {
        saved["query"] = query
        saved["matchIndex"] = -1
        mutable.value = state.value.copy(query = query, matchIndex = -1)
        computeSearch(0)
    }

    private fun computeSearch(preferred: Int, selectMatch: Boolean = true) {
        searchJob?.cancel()
        val value = state.value
        mutable.value = value.copy(matches = emptyList())
        if (!value.searchOpen || value.query.isEmpty() || !value.loaded) return
        searchJob = viewModelScope.launch {
            val ranges =
                withContext(searchDispatcher) { findTextRanges(value.displayed, value.query) }
            if (
                state.value.finished ||
                    state.value.displayed != value.displayed ||
                    state.value.query != value.query ||
                    !state.value.searchOpen
            )
                return@launch
            val index = if (ranges.isEmpty()) -1 else preferred.coerceIn(0, ranges.lastIndex)
            saved["matchIndex"] = index
            mutable.value = state.value.copy(matches = ranges, matchIndex = index)
            if (selectMatch && index >= 0) ranges[index].let { selection(it.first, it.last + 1) }
        }
    }

    fun match(index: Int) {
        val ranges = state.value.matches
        if (ranges.isEmpty()) return
        val next = ((index % ranges.size) + ranges.size) % ranges.size
        saved["matchIndex"] = next
        mutable.value = state.value.copy(matchIndex = next)
        ranges[next].let { selection(it.first, it.last + 1) }
    }

    fun action(action: CodeDialogAction, manualEnabled: Boolean = false) {
        val value = state.value
        if (!value.loaded || value.finished || value.busy) return
        when (action) {
            CodeDialogAction.Save ->
                if (!editable || value.showingAlternate && !sourcePreview) return
            CodeDialogAction.Editor -> {
                if (!editable) return
                saved["editorPending"] = true
                saved["editorReadOnly"] = value.showingAlternate && !sourcePreview
                mutable.value =
                    value.copy(
                        editorPending = true,
                        editorReadOnly = value.showingAlternate && !sourcePreview,
                    )
            }
            CodeDialogAction.ReplaceRules,
            CodeDialogAction.Effective -> if (!sourcePreview) return
            CodeDialogAction.Manual -> if (!sourcePreview || !manualEnabled) return
            CodeDialogAction.EditorSaved -> if (!editable || !sourcePreview) return
        }
        if (state.value.effects.any { it.action == action }) return
        effectId++
        saved["effectId"] = effectId
        mutable.value =
            state.value.copy(effects = state.value.effects + CodeDialogEffect(effectId, action))
        saveEffects()
    }

    fun consume(id: Long) {
        mutable.value = state.value.copy(effects = state.value.effects.filterNot { it.id == id })
        saveEffects()
    }

    private fun saveEffects() {
        saved["effects"] = GSON.toJson(state.value.effects)
    }

    fun editorResult(text: String?, cursor: Int = 0, error: String? = null) {
        val value = state.value
        if (!value.editorPending || value.finished) return
        saved["editorPending"] = false
        saved["editorReadOnly"] = false
        saved["editorPrepared"] = false
        saved["editorPath"] = null
        mutable.value =
            value.copy(
                editorPending = false,
                editorReadOnly = false,
                editorPrepared = false,
                editorPath = null,
                error = error,
            )
        if (text != null && !value.editorReadOnly && error == null) {
            mutable.value =
                state.value.copy(
                    original = text,
                    alternate = if (sourcePreview) null else state.value.alternate,
                    showingAlternate = if (sourcePreview) false else state.value.showingAlternate,
                )
            if (!state.value.showingAlternate) selection(cursor, cursor)
            checkpoint()
            computeSearch(state.value.matchIndex, selectMatch = false)
            if (sourcePreview) action(CodeDialogAction.EditorSaved)
        }
    }

    fun prepareEditor() {
        val files = transfer ?: return
        val value = state.value
        if (
            !value.editorPending ||
                value.editorPrepared ||
                !value.loaded ||
                transferJob?.isActive == true
        )
            return
        transferJob = viewModelScope.launch {
            try {
                val path =
                    files.write(if (value.editorReadOnly) value.displayed else value.original)
                saved["editorPath"] = path
                saved["editorPrepared"] = true
                mutable.value = state.value.copy(editorPath = path, editorPrepared = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value.effects
                    .filter { it.action == CodeDialogAction.Editor }
                    .forEach { consume(it.id) }
                editorResult(null, error = error.localizedMessage)
            }
        }
    }

    fun editorReturned(accepted: Boolean, inlineText: String?, outputPath: String?, cursor: Int) {
        if (!state.value.editorPending || saved.get<Boolean>("editorReturning") == true) {
            if (outputPath != saved.get<String>("editorOutputPath"))
                transfer?.let { files ->
                    viewModelScope.launch { files.delete(outputPath) }
                }
            return
        }
        saved["editorReturning"] = true
        saved["editorAccepted"] = accepted
        saved["editorOutputPath"] = outputPath
        saved["editorCursor"] = cursor
        // Native editor returns a durable file path. Compatibility inline results never enter
        // Bundle state.
        pendingInlineResult = inlineText
        if (state.value.loaded) restoreEditorResult()
    }

    private fun restoreEditorResult() {
        val files = transfer ?: return
        if (transferJob?.isActive == true) return
        transferJob = viewModelScope.launch {
            val input = state.value.editorPath
            val output = saved.get<String>("editorOutputPath")
            val cursor = saved.get<Int>("editorCursor") ?: 0
            var text: String? = null
            var failure: String? = null
            // Once the editor has returned, cancellation must not discard the only copy.
            // A cleared VM finishes durable I/O, but never resumes UI effects below this block.
            withContext(NonCancellable) {
                try {
                    val accepted =
                        saved.get<Boolean>("editorAccepted") == true && !state.value.editorReadOnly
                    text =
                        if (!accepted) null
                        else
                            pendingInlineResult
                                ?: output?.let {
                                    try {
                                        files.read(it)
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        if (durableReturnedDraft) state.value.original
                                        else throw error
                                    }
                                }
                    if (text != null) {
                        val nextRevision = revision + 1
                        repository.write(
                            session,
                            CodeDialogDraft(
                                text,
                                if (sourcePreview) null else state.value.alternate,
                                nextRevision,
                            ),
                        )
                        saved["editorResultRevision"] = nextRevision
                    }
                    files.delete(input, output)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Retain failed transfer paths for recovery; never delete an unsaved result.
                    saved["editorFailedInput"] = input
                    saved["editorFailedOutput"] = output
                    failure = error.localizedMessage ?: "无法读取代码编辑结果"
                }
            }
            currentCoroutineContext().ensureActive()
            saved["editorReturning"] = false
            saved["editorOutputPath"] = null
            pendingInlineResult = null
            saved["editorResultRevision"] = null
            durableReturnedDraft = false
            editorResult(text, cursor, failure)
        }
    }

    fun close() {
        if (state.value.busy) return
        saved["finished"] = true
        saved["effects"] = "[]"
        mutable.value = state.value.copy(finished = true, effects = emptyList())
    }

    internal fun stop() {
        loader?.cancel()
        searchJob?.cancel()
        transferJob?.cancel()
        writer.cancel()
        writes.close()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
