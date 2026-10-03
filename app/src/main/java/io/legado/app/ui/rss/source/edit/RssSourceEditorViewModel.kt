package io.legado.app.ui.rss.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RssSourceEditorIssue {
    Required,
    Format,
    Focus,
    NoLogin,
}

enum class RssSourceEditorEffectKind {
    Close,
    SavedClose,
    SavedDebug,
    SavedLogin,
    SavedVariable,
    SavedOnly,
    Clipboard,
    Paste,
    Help,
    JsHelp,
    RegexHelp,
    File,
    UrlOptions,
    ScanQr,
    ShareText,
    ShareQr,
    Log,
    Editor,
}

data class RssSourceEditorEffect(
    val id: String,
    val kind: RssSourceEditorEffectKind,
    val path: String? = null,
    val field: RssSourceEditorField? = null,
    val cursor: Int = 0,
)

data class RssSourceEditorState(
    val draft: RssSourceEditorDraft = RssSourceEditorDraft(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val missing: Boolean = false,
    val issue: RssSourceEditorIssue? = null,
    val error: String? = null,
    val focus: RssSourceEditorField? = null,
    val tab: Int = 0,
    val expanded: Boolean = false,
    val autoComplete: Boolean = false,
    val exit: Boolean = false,
    val editorPending: Boolean = false,
    val finished: Boolean = false,
    val savedResult: Boolean = false,
    val effects: List<RssSourceEditorEffect> = emptyList(),
) {
    val canEdit
        get() = loaded && !loading && !busy && !editorPending && !finished
}

private const val EDITOR = "rss.source.editor."

class RssSourceEditorViewModel(
    private val repository: RssSourceEditorRepository,
    private val saved: SavedStateHandle,
    initialId: String? = null,
) : ViewModel() {
    private val initialKey =
        saved.get<String>(EDITOR + "initialKey")
            ?: initialId.also { saved[EDITOR + "initialKey"] = it }
    private val session =
        saved.get<String>(EDITOR + "session")
            ?: UUID.randomUUID().toString().also { saved[EDITOR + "session"] = it }
    private var document: RssSourceEditorDocument? = null
    private val mutable =
        MutableStateFlow(
            RssSourceEditorState(
                focus = saved[EDITOR + "focus"],
                tab = saved[EDITOR + "tab"] ?: 0,
                expanded = saved[EDITOR + "expanded"] ?: false,
                autoComplete = saved[EDITOR + "autoComplete"] ?: false,
                exit = saved[EDITOR + "exit"] ?: false,
                editorPending = saved.contains(EDITOR + "editorField"),
                finished = saved[EDITOR + "finished"] ?: false,
                savedResult = saved[EDITOR + "savedResult"] ?: false,
                effects =
                    saved
                        .get<String>(EDITOR + "effects")
                        ?.let { GSON.fromJsonArray<RssSourceEditorEffect>(it).getOrNull() }
                        .orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var loadJob: Job? = null
    private var stopped = false
    private val writes = Channel<RssSourceEditorDocument>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (value in writes) try {
            repository.writeDraft(session, value)
        } catch (error: Exception) {
            fail(error)
        }
    }

    init {
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            try {
                val stored = repository.readDraft(session)
                val loaded =
                    stored
                        ?: if (initialKey != null) repository.load(initialKey)
                        else RssSourceEditorDocument(null, RssSourceEditorDraft())
                val draft = loaded?.draft
                currentCoroutineContext().ensureActive()
                if (draft == null) {
                    mutable.value = state.value.copy(loading = false, missing = true)
                    enqueue(RssSourceEditorEffectKind.Close)
                    return@launch
                }
                document = requireNotNull(loaded)
                mutable.value = state.value.copy(draft = draft, loading = false, loaded = true)
                if (stored == null) checkpoint()
                document?.delivery?.let(::savedDelivery)
                if (saved.get<Boolean>(EDITOR + "returning") == true) completeEditor()
                else if (
                    saved.contains(EDITOR + "editorField") &&
                        saved.get<Boolean>(EDITOR + "editorLaunched") != true
                ) {
                    val field = saved.get<RssSourceEditorField>(EDITOR + "editorField")!!
                    if (state.value.effects.none { it.kind == RssSourceEditorEffectKind.Editor })
                        enqueue(
                            RssSourceEditorEffectKind.Editor,
                            path = saved[EDITOR + "editorPath"],
                            field = field,
                            cursor = draft[field].start,
                        )
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                fail(error)
                mutable.value = state.value.copy(loading = false)
            }
        }
    }

    fun retry() {
        if (document == null && !state.value.busy) {
            mutable.value = state.value.copy(loading = true, error = null)
            load()
        }
    }

    private fun checkpoint() {
        document = document?.copy(draft = state.value.draft, revision = document!!.revision + 1)
        document?.let { writes.trySend(it) }
    }

    private val undo = mutableMapOf<RssSourceEditorField, List<RssSourceEditorText>>()
    private val redo = mutableMapOf<RssSourceEditorField, List<RssSourceEditorText>>()

    fun field(field: RssSourceEditorField, value: RssSourceEditorText) {
        if (!state.value.canEdit) return
        val before = state.value.draft[field]
        if (before.text != value.text) {
            undo[field] = (undo[field].orEmpty() + before).takeLast(20)
            redo.remove(field)
        }
        mutable.value =
            state.value.copy(
                draft = state.value.draft.with(field, value),
                issue = null,
                error = null,
            )
        checkpoint()
    }

    fun focus(field: RssSourceEditorField) {
        saved[EDITOR + "focus"] = field
        mutable.value = state.value.copy(focus = field)
    }

    fun tab(tab: Int) {
        val value = tab.coerceIn(0, 3)
        saved[EDITOR + "tab"] = value
        saved.remove<RssSourceEditorField>(EDITOR + "focus")
        mutable.value = state.value.copy(tab = value, focus = null)
    }

    fun expanded(value: Boolean) {
        saved[EDITOR + "expanded"] = value
        mutable.value = state.value.copy(expanded = value)
    }

    fun autoComplete(value: Boolean) {
        if (state.value.canEdit) {
            saved[EDITOR + "autoComplete"] = value
            mutable.value = state.value.copy(autoComplete = value)
        }
    }

    fun options(change: (RssSourceEditorDraft) -> RssSourceEditorDraft) {
        if (state.value.canEdit) {
            mutable.value = state.value.copy(draft = change(state.value.draft))
            checkpoint()
        }
    }

    fun enabled(value: Boolean) {
        if (state.value.canEdit) {
            mutable.value = state.value.copy(draft = state.value.draft.copy(enabled = value))
            checkpoint()
        }
    }

    fun cookieJar(value: Boolean) {
        if (state.value.canEdit) {
            mutable.value = state.value.copy(draft = state.value.draft.copy(cookieJar = value))
            checkpoint()
        }
    }

    fun undo() = history(undo, redo)

    fun redo() = history(redo, undo)

    private fun history(
        from: MutableMap<RssSourceEditorField, List<RssSourceEditorText>>,
        to: MutableMap<RssSourceEditorField, List<RssSourceEditorText>>,
    ) {
        val field = state.value.focus ?: return
        if (!state.value.canEdit) return
        val history = from[field].orEmpty()
        val value = history.lastOrNull() ?: return
        to[field] = (to[field].orEmpty() + state.value.draft[field]).takeLast(20)
        from[field] = history.dropLast(1)
        mutable.value = state.value.copy(draft = state.value.draft.with(field, value))
        checkpoint()
    }

    fun requestExit() {
        if (state.value.busy || state.value.editorPending) return
        if (document?.let { !state.value.draft.sameContent(it.baseline) } == true) {
            saved[EDITOR + "exit"] = true
            mutable.value = state.value.copy(exit = true)
        } else enqueue(RssSourceEditorEffectKind.Close)
    }

    fun keepEditing() {
        saved[EDITOR + "exit"] = false
        mutable.value = state.value.copy(exit = false)
    }

    fun discard() {
        if (!state.value.busy) {
            keepEditing()
            enqueue(RssSourceEditorEffectKind.Close)
        }
    }

    private fun enqueue(
        kind: RssSourceEditorEffectKind,
        token: String = UUID.randomUUID().toString(),
        path: String? = null,
        field: RssSourceEditorField? = null,
        cursor: Int = 0,
    ) {
        if (stopped || state.value.effects.any { it.id == token }) return
        mutable.value =
            state.value.copy(
                effects =
                    state.value.effects + RssSourceEditorEffect(token, kind, path, field, cursor)
            )
        effectsSaved()
    }

    private fun effectsSaved() {
        saved[EDITOR + "effects"] = GSON.toJson(state.value.effects)
    }

    fun consume(effect: RssSourceEditorEffect) {
        if (state.value.effects.none { it.id == effect.id }) return
        mutable.value =
            state.value.copy(effects = state.value.effects.filterNot { it.id == effect.id })
        effectsSaved()
        if (effect.kind == RssSourceEditorEffectKind.Editor) saved[EDITOR + "editorLaunched"] = true
        if (document?.delivery?.token == effect.id) {
            saved[EDITOR + "delivered"] = effect.id
            document = document!!.copy(delivery = null)
            checkpoint()
        }
        if (
            effect.kind in
                listOf(RssSourceEditorEffectKind.Close, RssSourceEditorEffectKind.SavedClose)
        ) {
            saved[EDITOR + "finished"] = true
            mutable.value = state.value.copy(finished = true)
        }
    }

    private fun savedDelivery(delivery: RssSourceEditorDelivery) {
        saved[EDITOR + "savedResult"] = true
        mutable.value = state.value.copy(savedResult = true)
        if (saved.get<String>(EDITOR + "delivered") == delivery.token) return
        val kind =
            when (delivery.action) {
                RssSourceEditorSaveAction.Close -> RssSourceEditorEffectKind.SavedClose
                RssSourceEditorSaveAction.Debug -> RssSourceEditorEffectKind.SavedDebug
                RssSourceEditorSaveAction.Login ->
                    if (delivery.loginAvailable) RssSourceEditorEffectKind.SavedLogin
                    else RssSourceEditorEffectKind.SavedOnly
                RssSourceEditorSaveAction.Variable -> RssSourceEditorEffectKind.SavedVariable
            }
        if (kind == RssSourceEditorEffectKind.SavedOnly)
            mutable.value = state.value.copy(issue = RssSourceEditorIssue.NoLogin)
        enqueue(kind, delivery.token)
    }

    private fun operation(action: suspend () -> Unit) {
        if (!state.value.canEdit || document == null) return
        mutable.value = state.value.copy(busy = true, error = null, issue = null)
        job = viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                fail(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun save(action: RssSourceEditorSaveAction) {
        if (!state.value.canEdit) return
        if (!state.value.draft.valid()) {
            mutable.value = state.value.copy(issue = RssSourceEditorIssue.Required)
            return
        }
        operation {
            val result =
                repository.save(
                    session,
                    document!!.copy(draft = state.value.draft),
                    action,
                    state.value.autoComplete,
                )
            currentCoroutineContext().ensureActive()
            document = result
            mutable.value = state.value.copy(draft = result.draft)
            result.delivery?.let(::savedDelivery)
        }
    }

    fun copy() {
        if (state.value.canEdit) enqueue(RssSourceEditorEffectKind.Clipboard)
    }

    fun requestPaste() {
        if (state.value.canEdit) enqueue(RssSourceEditorEffectKind.Paste)
    }

    suspend fun copyText(): String =
        repository.export(
            requireNotNull(document).copy(draft = state.value.draft),
            state.value.autoComplete,
        )

    fun paste(text: String?) {
        operation {
            val draft = text?.let { repository.parse(it) }
            currentCoroutineContext().ensureActive()
            if (draft == null) mutable.value = state.value.copy(issue = RssSourceEditorIssue.Format)
            else {
                undo.clear()
                redo.clear()
                mutable.value = state.value.copy(draft = draft)
                tab(0)
                checkpoint()
            }
        }
    }

    fun help() {
        if (!state.value.busy) enqueue(RssSourceEditorEffectKind.Help)
    }

    fun openEditor() {
        val field = state.value.focus
        if (!state.value.canEdit) return
        if (field == null) {
            mutable.value = state.value.copy(issue = RssSourceEditorIssue.Focus)
            return
        }
        val text = state.value.draft[field]
        operation {
            val path =
                withContext(NonCancellable) {
                    repository.editorInput(text.text).also {
                        saved[EDITOR + "editorField"] = field
                        saved[EDITOR + "editorPath"] = it
                        saved[EDITOR + "editorRevision"] = document!!.revision
                        saved[EDITOR + "editorLaunched"] = false
                    }
                }
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(editorPending = true)
            enqueue(
                RssSourceEditorEffectKind.Editor,
                path = path,
                field = field,
                cursor = text.start,
            )
        }
    }

    private var inlineResult: String? = null

    fun editorReturned(accepted: Boolean, text: String?, output: String?, cursor: Int) {
        if (
            !saved.contains(EDITOR + "editorField") ||
                saved.get<Boolean>(EDITOR + "returning") == true
        )
            return
        saved[EDITOR + "returning"] = true
        saved[EDITOR + "accepted"] = accepted
        saved[EDITOR + "output"] = output
        saved[EDITOR + "cursor"] = cursor
        inlineResult = text
        if (document != null) completeEditor()
    }

    private fun completeEditor() {
        job = viewModelScope.launch {
            mutable.value = state.value.copy(busy = true)
            try {
                val field = saved.get<RssSourceEditorField>(EDITOR + "editorField") ?: return@launch
                val input = saved.get<String>(EDITOR + "editorPath")
                val output = saved.get<String>(EDITOR + "output")
                val before = document!!
                val cursor = saved.get<Int>(EDITOR + "cursor") ?: 0
                val result =
                    withContext(NonCancellable) {
                        val accepted = saved.get<Boolean>(EDITOR + "accepted") == true
                        val returnedRevision = saved.get<Long>(EDITOR + "returnedRevision")
                        val restored =
                            (returnedRevision != null && before.revision >= returnedRevision) ||
                                before.revision >
                                    (saved.get<Long>(EDITOR + "editorRevision") ?: Long.MAX_VALUE)
                        val text =
                            if (accepted && !restored)
                                output?.let { repository.editorText(it) }
                                    ?: inlineResult
                                    ?: before.draft[field].text
                            else null
                        val changed =
                            if (text != null)
                                before.copy(
                                    draft =
                                        before.draft.with(
                                            field,
                                            RssSourceEditorText(
                                                text,
                                                if (cursor >= 0) cursor
                                                else before.draft[field].start,
                                            ),
                                        ),
                                    revision = before.revision + 1,
                                )
                            else before
                        repository.writeDraft(session, changed)
                        saved[EDITOR + "returnedRevision"] = changed.revision
                        repository.clearEditor(input, output)
                        changed
                    }
                currentCoroutineContext().ensureActive()
                document = result
                mutable.value =
                    state.value.copy(draft = result.draft, focus = field, tab = field.tab)
                saved[EDITOR + "focus"] = field
                saved[EDITOR + "tab"] = field.tab
                clearEditorState()
            } catch (error: Exception) {
                fail(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    private fun clearEditorState() {
        listOf(
                "editorField",
                "editorPath",
                "editorRevision",
                "editorLaunched",
                "returning",
                "accepted",
                "output",
                "cursor",
                "returnedRevision",
            )
            .forEach { saved.remove<Any>(EDITOR + it) }
        inlineResult = null
        mutable.value = state.value.copy(editorPending = false)
    }

    fun retryEditorResult() {
        if (
            saved.get<Boolean>(EDITOR + "returning") == true &&
                !state.value.busy &&
                document != null
        )
            completeEditor()
    }

    fun discardEditorResult() {
        if (state.value.busy || !state.value.editorPending) return
        job = viewModelScope.launch {
            mutable.value = state.value.copy(busy = true)
            try {
                withContext(NonCancellable) {
                    repository.clearEditor(saved[EDITOR + "editorPath"], saved[EDITOR + "output"])
                }
                currentCoroutineContext().ensureActive()
                clearEditorState()
                mutable.value = state.value.copy(error = null)
            } catch (error: Exception) {
                fail(error)
            } finally {
                if (!stopped) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    val sourceUrl
        get() = document?.originalKey

    fun action(kind: RssSourceEditorEffectKind) {
        if (state.value.canEdit) enqueue(kind)
    }

    fun clearCookie() {
        operation {
            repository.clearCookie(state.value.draft[RssSourceEditorField.SourceUrl].text)
            currentCoroutineContext().ensureActive()
        }
    }

    suspend fun variable(): String? = sourceUrl?.let { repository.variable(it) }

    val variableComment
        get() = state.value.draft[RssSourceEditorField.VariableComment].text

    fun setVariable(key: String, value: String?) {
        if (key != sourceUrl || stopped) return
        viewModelScope.launch {
            try {
                repository.setVariable(key, value)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                fail(error)
            }
        }
    }

    fun insert(text: String) {
        val focused = state.value.focus ?: return
        val value = state.value.draft[focused].bounded()
        val start = minOf(value.start, value.end)
        val end = maxOf(value.start, value.end)
        if (text.isNotEmpty())
            field(
                focused,
                RssSourceEditorText(value.text.replaceRange(start, end, text), start + text.length),
            )
    }

    suspend fun flush() {
        document?.let { repository.writeDraft(session, it.copy(draft = state.value.draft)) }
    }

    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
    }

    internal fun stop() {
        stopped = true
        loadJob?.cancel()
        job?.cancel()
        writer.cancel()
        writes.close()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
