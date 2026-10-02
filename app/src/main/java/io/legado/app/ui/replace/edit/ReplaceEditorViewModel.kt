package io.legado.app.ui.replace.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.model.replace.ReplacePreview
import io.legado.app.model.replace.ReplacePreviewException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ReplaceEditorPreviewIssue { Timeout, ContextUnavailable, JsEvaluation }

data class ReplaceEditorLaunch(val nonce: String, val field: ReplaceEditorField, val path: String)
data class ReplaceEditorState(
    val draft: ReplaceEditorDraft = ReplaceEditorDraft(), val loaded: Boolean = false, val busy: Boolean = false,
    val focus: ReplaceEditorField? = null, val exit: Boolean = false, val finished: Boolean = false,
    val saved: Boolean = false, val error: String? = null, val preview: String = "",
    val previewError: ReplaceEditorPreviewIssue? = null, val previewFailed: Boolean = false,
    val truncated: Boolean = false, val focusRequired: Boolean = false, val editor: ReplaceEditorLaunch? = null, val editorLaunch: Boolean = false,
    val editorReturning: Boolean = false, val scroll: Int = 0
) { val editable: Boolean get() = loaded && !busy && !finished && editor == null }
class ReplaceEditorViewModel(private val repository: ReplaceEditorRepository,
    private val savedState: SavedStateHandle, private val request: ReplaceEditorRequest
) : ViewModel() {
    val session: String = savedState.get<String>("replaceEditor.session") ?: UUID.randomUUID().toString().also { savedState["replaceEditor.session"] = it }
    private var document: ReplaceEditorDocument? = null
    private val initialEditor = runCatching {
        ReplaceEditorLaunch(checkNotNull(savedState.get<String>("replaceEditor.editorNonce")),
            ReplaceEditorField.valueOf(checkNotNull(savedState.get<String>("replaceEditor.editorField"))),
            checkNotNull(savedState.get<String>("replaceEditor.editorPath")))
    }.getOrNull()
    private val mutable = MutableStateFlow(ReplaceEditorState(
        focus = savedState.get<String>("replaceEditor.focus")?.let { runCatching { ReplaceEditorField.valueOf(it) }.getOrNull() },
        exit = savedState.get<Boolean>("replaceEditor.exit") == true,
        finished = savedState.get<Boolean>("replaceEditor.finished") == true,
        saved = savedState.get<Boolean>("replaceEditor.saved") == true,
        editor = initialEditor, editorLaunch = savedState.get<Boolean>("replaceEditor.editorLaunch") == true,
        editorReturning = savedState.get<Boolean>("replaceEditor.returning") == true,
        scroll = savedState.get<Int>("replaceEditor.scroll") ?: 0))
    val state: StateFlow<ReplaceEditorState> = mutable.asStateFlow()
    private var loading: Job? = null
    private var previewJob: Job? = null
    private val undo = mutableMapOf<ReplaceEditorField, ArrayDeque<ReplaceEditorText>>()
    private val redo = mutableMapOf<ReplaceEditorField, ArrayDeque<ReplaceEditorText>>()
    init { load() }
    fun load() {
        if (loading?.isActive == true || state.value.finished) return
        mutable.value = state.value.copy(busy = true, error = null)
        loading = viewModelScope.launch {
            try {
                val restored = repository.read(session)
                val value = restored ?: repository.load(request).let(::ReplaceEditorDocument).also { repository.write(session, it) }
                currentCoroutineContext().ensureActive()
                if (state.value.finished) return@launch
                document = value
                mutable.value = state.value.copy(draft = value.draft, loaded = true, busy = false,
                    finished = value.receipt != null, saved = value.receipt != null)
                if (value.receipt != null) markFinished(true) else { schedulePreview(); if (state.value.editorReturning) completeEditor() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false, error = error.localizedMessage ?: "Error") }
        }
    }
    private fun change(draft: ReplaceEditorDraft, history: Boolean = true) {
        val current = document ?: return
        if (history) recordHistory(current.draft, draft)
        document = current.copy(draft = draft, revision = current.revision + 1)
        mutable.value = state.value.copy(draft = draft)
        persist(); schedulePreview()
    }
    private fun recordHistory(before: ReplaceEditorDraft, after: ReplaceEditorDraft) {
        ReplaceEditorField.entries.forEach { field ->
            if (after[field].text != before[field].text) {
                val stack = undo.getOrPut(field) { ArrayDeque() }
                stack.addLast(before[field]); if (stack.size > 100) stack.removeFirst()
                redo.remove(field)
            }
        }
    }
    private fun persist() {
        val value = document ?: return
        viewModelScope.launch {
            try { repository.write(session, value) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") }
        }
    }
    suspend fun flushDraft() { document?.let { repository.write(session, it) } }
    fun field(field: ReplaceEditorField, value: ReplaceEditorText) {
        if (!state.value.editable) return
        if (field == ReplaceEditorField.Sample && value.text != ReplacePreview.normalizeSample(value.text)) mutable.value = state.value.copy(truncated = true)
        change(state.value.draft.with(field, value))
    }
    fun focus(field: ReplaceEditorField) { if (!state.value.loaded) return; savedState["replaceEditor.focus"] = field.name; mutable.value = state.value.copy(focus = field) }
    fun flags(regex: Boolean = state.value.draft.regex, title: Boolean = state.value.draft.title,
        source: Boolean = state.value.draft.source, content: Boolean = state.value.draft.content) {
        if (state.value.editable) change(state.value.draft.copy(regex = regex, title = title, source = source, content = content))
    }
    fun insert(text: String) {
        if (text.isEmpty() || !state.value.editable) return
        val field = state.value.focus ?: return
        val value = state.value.draft[field].bounded()
        val start = minOf(value.start, value.end); val end = maxOf(value.start, value.end)
        this.field(field, ReplaceEditorText(value.text.replaceRange(start, end, text), start + text.length))
    }
    fun undo() {
        if (!state.value.editable) return
        val field = state.value.focus ?: return; val stack = undo[field]?.takeIf { it.isNotEmpty() } ?: return
        redo.getOrPut(field) { ArrayDeque() }.addLast(state.value.draft[field])
        change(state.value.draft.with(field, stack.removeLast()), false)
    }
    fun redo() {
        if (!state.value.editable) return
        val field = state.value.focus ?: return; val stack = redo[field]?.takeIf { it.isNotEmpty() } ?: return
        undo.getOrPut(field) { ArrayDeque() }.addLast(state.value.draft[field])
        change(state.value.draft.with(field, stack.removeLast()), false)
    }
    fun scroll(offset: Int) { savedState["replaceEditor.scroll"] = offset; mutable.value = state.value.copy(scroll = offset) }
    private fun schedulePreview() {
        previewJob?.cancel(); val value = document ?: return
        previewJob = viewModelScope.launch {
            delay(250)
            try {
                val output = repository.preview(value.draft)
                currentCoroutineContext().ensureActive()
                if (document?.revision == value.revision) mutable.value = state.value.copy(preview = output, previewFailed = false, previewError = null)
            } catch (error: CancellationException) { throw error }
            catch (error: StackOverflowError) { previewFailure(value, error) }
            catch (error: Exception) { previewFailure(value, error) }
        }
    }
    private suspend fun previewFailure(value: ReplaceEditorDocument, error: Throwable) {
        currentCoroutineContext().ensureActive()
        if (document?.revision == value.revision) mutable.value = state.value.copy(preview = value.draft[ReplaceEditorField.Sample].text,
            previewFailed = true, previewError = when ((error as? ReplacePreviewException)?.reason) {
                ReplacePreviewException.Reason.TIMEOUT -> ReplaceEditorPreviewIssue.Timeout
                ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE -> ReplaceEditorPreviewIssue.ContextUnavailable
                ReplacePreviewException.Reason.JS_EVALUATION -> ReplaceEditorPreviewIssue.JsEvaluation
                null -> null
            })
    }
    fun paste(text: String) {
        if (!state.value.editable) return
        val revision = document?.revision; val id = state.value.draft.id
        viewModelScope.launch {
            try {
                val parsed = repository.parse(text, id); currentCoroutineContext().ensureActive()
                if (state.value.editable && document?.revision == revision) change(state.value.draft.pasted(parsed))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") }
        }
    }
    suspend fun copyJson(): String? {
        if (!state.value.editable) return null
        val value = state.value.draft; val json = repository.export(value)
        currentCoroutineContext().ensureActive(); return json.takeIf { state.value.editable && state.value.draft == value }
    }
    fun save() {
        if (!state.value.editable) return
        val value = document ?: return
        mutable.value = state.value.copy(busy = true, error = null); previewJob?.cancel()
        viewModelScope.launch {
            try {
                val persisted = repository.save(session, value)
                currentCoroutineContext().ensureActive(); document = persisted
                mutable.value = state.value.copy(draft = persisted.draft, busy = false)
                markFinished(true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false, error = "save error, ${error.localizedMessage}"); schedulePreview() }
        }
    }
    private fun markFinished(saved: Boolean) {
        savedState["replaceEditor.finished"] = true; savedState["replaceEditor.saved"] = saved
        mutable.value = state.value.copy(finished = true, saved = saved, exit = false)
    }
    fun close() {
        if (state.value.busy || state.value.editor != null || state.value.finished) return
        val value = document
        if (value != null && !value.draft.sameContent(value.baseline)) {
            savedState["replaceEditor.exit"] = true; mutable.value = state.value.copy(exit = true)
        } else markFinished(false)
    }
    fun keepEditing() { savedState["replaceEditor.exit"] = false; mutable.value = state.value.copy(exit = false) }
    fun discard() { if (state.value.exit) { previewJob?.cancel(); markFinished(false) } }
    fun clearNotice() { mutable.value = state.value.copy(error = null, truncated = false, focusRequired = false) }
    fun openEditor(field: ReplaceEditorField? = state.value.focus) {
        if (!state.value.editable) return
        if (field == null) { mutable.value = state.value.copy(focusRequired = true); return }
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val path = repository.editorInput(state.value.draft[field].text); currentCoroutineContext().ensureActive()
                val launch = ReplaceEditorLaunch(UUID.randomUUID().toString(), field, path)
                savedState["replaceEditor.editorNonce"] = launch.nonce; savedState["replaceEditor.editorField"] = field.name
                savedState["replaceEditor.editorPath"] = path; savedState["replaceEditor.editorLaunch"] = true
                mutable.value = state.value.copy(busy = false, editor = launch, editorLaunch = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false, error = error.localizedMessage ?: "Error") }
        }
    }
    fun editorLaunched(nonce: String) {
        if (state.value.editor?.nonce != nonce) return
        savedState["replaceEditor.editorLaunch"] = false; mutable.value = state.value.copy(editorLaunch = false)
    }
    private var editorJob: Job? = null
    private var inlineResult: String? = null
    fun editorResult(nonce: String, text: String?, path: String?, cursor: Int?, accepted: Boolean) {
        if (state.value.editor?.nonce != nonce || state.value.editorReturning) return
        inlineResult = text.takeIf { accepted }
        savedState["replaceEditor.returning"] = true
        savedState["replaceEditor.returnAccepted"] = accepted
        savedState["replaceEditor.returnCursor"] = cursor
        savedState["replaceEditor.returnPath"] = path
        savedState["replaceEditor.returnHasText"] = accepted && (text != null || path != null)
        mutable.value = state.value.copy(editorReturning = true)
        if (state.value.loaded) completeEditor()
    }
    fun retryEditor() { if (state.value.editorReturning && editorJob?.isActive != true) completeEditor() }
    private fun completeEditor() {
        if (editorJob?.isActive == true || !state.value.loaded || state.value.finished) return
        val launch = state.value.editor ?: return
        mutable.value = state.value.copy(busy = true, error = null)
        editorJob = viewModelScope.launch {
            try {
                inlineResult?.let { text ->
                    // Legacy inline results are staged through the same transfer protocol as
                    // CodeEditActivity. Only its small durable path enters SavedState.
                    withContext(NonCancellable) {
                        val path = repository.editorInput(text)
                        savedState["replaceEditor.returnPath"] = path
                        inlineResult = null
                    }
                }
                currentCoroutineContext().ensureActive()
                val accepted = savedState.get<Boolean>("replaceEditor.returnAccepted") == true
                val path = savedState.get<String>("replaceEditor.returnPath")
                val cursor = savedState.get<Int>("replaceEditor.returnCursor")
                val targetRevision = savedState.get<Long>("replaceEditor.returnRevision") ?: (checkNotNull(document).revision + 1).also {
                    savedState["replaceEditor.returnRevision"] = it
                }
                if (checkNotNull(document).revision < targetRevision) {
                    check(savedState.get<Boolean>("replaceEditor.returnHasText") != true || path != null) { "Editor text unavailable" }
                    val replacement = if (accepted) path?.let { repository.editorText(it) } else null
                    currentCoroutineContext().ensureActive()
                    if (state.value.editor?.nonce != launch.nonce || state.value.finished) return@launch
                    val original = state.value.draft[launch.field]
                    val value = original.copy(text = replacement ?: original.text,
                        start = cursor?.takeIf { accepted && it >= 0 } ?: original.start,
                        end = cursor?.takeIf { accepted && it >= 0 } ?: original.end).bounded()
                    val draft = if (accepted && (replacement != null || cursor != null)) state.value.draft.with(launch.field, value) else state.value.draft
                    recordHistory(state.value.draft, draft)
                    document = checkNotNull(document).copy(draft = draft, revision = targetRevision)
                    mutable.value = state.value.copy(draft = draft)
                }
                repository.write(session, checkNotNull(document))
                currentCoroutineContext().ensureActive()
                repository.clearEditor(launch.path, path)
                currentCoroutineContext().ensureActive()
                clearEditorState(); mutable.value = state.value.copy(busy = false); schedulePreview()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false, error = error.localizedMessage ?: "Error") }
        }
    }
    fun discardEditor() {
        if (!state.value.editorReturning || editorJob?.isActive == true) return
        val launch = state.value.editor ?: return
        mutable.value = state.value.copy(busy = true)
        editorJob = viewModelScope.launch {
            try {
                val persisted = checkNotNull(repository.read(session)); currentCoroutineContext().ensureActive()
                document = persisted; mutable.value = state.value.copy(draft = persisted.draft)
                repository.clearEditor(launch.path, savedState.get<String>("replaceEditor.returnPath"))
                currentCoroutineContext().ensureActive(); clearEditorState(); mutable.value = state.value.copy(busy = false); schedulePreview()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(busy = false, error = error.localizedMessage ?: "Error") }
        }
    }
    private fun clearEditorState() {
        savedState.remove<String>("replaceEditor.editorNonce"); savedState.remove<String>("replaceEditor.editorField")
        savedState.remove<String>("replaceEditor.editorPath"); savedState.remove<Boolean>("replaceEditor.editorLaunch")
        savedState.remove<Boolean>("replaceEditor.returning"); savedState.remove<Boolean>("replaceEditor.returnAccepted")
        savedState.remove<Boolean>("replaceEditor.returnHasText"); savedState.remove<String>("replaceEditor.returnPath")
        savedState.remove<Int>("replaceEditor.returnCursor"); savedState.remove<Long>("replaceEditor.returnRevision")
        inlineResult = null
        mutable.value = state.value.copy(editor = null, editorLaunch = false, editorReturning = false)
    }
    fun stop() { viewModelScope.cancel() }
}
