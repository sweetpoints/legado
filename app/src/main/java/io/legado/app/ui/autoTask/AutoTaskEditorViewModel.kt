package io.legado.app.ui.autoTask

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

enum class AutoTaskEditorEffectKind {
    Close,
    SavedClose,
    SavedDebug,
    SavedLogin,
    SavedOnly,
    Clipboard,
    Paste,
    Help,
    Editor,
}

data class AutoTaskEditorEffect(
    val id: String,
    val kind: AutoTaskEditorEffectKind,
    val path: String? = null,
    val field: AutoTaskEditorField? = null,
    val cursor: Int = 0,
)

data class AutoTaskEditorState(
    val draft: AutoTaskEditorDraft = AutoTaskEditorDraft(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val missing: Boolean = false,
    val issue: AutoTaskEditorIssue? = null,
    val error: String? = null,
    val focus: AutoTaskEditorField? = null,
    val exit: Boolean = false,
    val editorPending: Boolean = false,
    val finished: Boolean = false,
    val savedResult: Boolean = false,
    val effects: List<AutoTaskEditorEffect> = emptyList(),
) {
    val canEdit
        get() = !loading && !busy && !editorPending && !finished
}

private const val EDITOR = "autoTask.editor."

class AutoTaskEditorViewModel(
    private val repository: AutoTaskEditorRepository,
    private val saved: SavedStateHandle,
    initialId: String? = null,
) : ViewModel() {
    private val id =
        saved.get<String>(EDITOR + "id")
            ?: (initialId ?: UUID.randomUUID().toString()).also { saved[EDITOR + "id"] = it }
    private val session =
        saved.get<String>(EDITOR + "session")
            ?: UUID.randomUUID().toString().also { saved[EDITOR + "session"] = it }
    private var document: AutoTaskEditorDocument? = null
    private val mutable =
        MutableStateFlow(
            AutoTaskEditorState(
                focus = saved[EDITOR + "focus"],
                exit = saved[EDITOR + "exit"] ?: false,
                editorPending = saved.contains(EDITOR + "editorField"),
                finished = saved[EDITOR + "finished"] ?: false,
                savedResult = saved[EDITOR + "savedResult"] ?: false,
                effects =
                    saved
                        .get<String>(EDITOR + "effects")
                        ?.let { GSON.fromJsonArray<AutoTaskEditorEffect>(it).getOrNull() }
                        .orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var loadJob: Job? = null
    private var stopped = false
    private val writes = Channel<AutoTaskEditorDocument>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (value in writes) try {
            repository.writeDraft(session, value)
        } catch (error: Exception) {
            fail(error)
        }
    }

    init {
        if (initialId != null) saved[EDITOR + "existing"] = true
        load(initialId)
    }

    private fun load(initialId: String?) {
        loadJob = viewModelScope.launch {
            try {
                val stored = repository.readDraft(session)
                check(stored == null || stored.id == id) { "Invalid draft identity" }
                val draft =
                    stored?.draft
                        ?: if (initialId != null) repository.load(initialId)
                        else AutoTaskEditorDraft()
                currentCoroutineContext().ensureActive()
                if (draft == null) {
                    mutable.value = state.value.copy(loading = false, missing = true)
                    enqueue(AutoTaskEditorEffectKind.Close)
                    return@launch
                }
                document = stored ?: AutoTaskEditorDocument(id, draft, existing = initialId != null)
                mutable.value = state.value.copy(draft = draft, loading = false)
                if (stored == null) checkpoint()
                document?.delivery?.let(::savedDelivery)
                if (saved.get<Boolean>(EDITOR + "returning") == true) completeEditor()
                else if (
                    saved.contains(EDITOR + "editorField") &&
                        saved.get<Boolean>(EDITOR + "editorLaunched") != true
                ) {
                    val field = saved.get<AutoTaskEditorField>(EDITOR + "editorField")!!
                    enqueue(
                        AutoTaskEditorEffectKind.Editor,
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
            load(if (saved.get<Boolean>(EDITOR + "existing") == true) id else null)
        }
    }

    private fun checkpoint() {
        document = document?.copy(draft = state.value.draft, revision = document!!.revision + 1)
        document?.let { writes.trySend(it) }
    }

    fun field(field: AutoTaskEditorField, value: AutoTaskEditorText) {
        if (!state.value.canEdit) return
        mutable.value =
            state.value.copy(
                draft = state.value.draft.with(field, value),
                issue = null,
                error = null,
            )
        checkpoint()
    }

    fun focus(field: AutoTaskEditorField) {
        saved[EDITOR + "focus"] = field
        mutable.value = state.value.copy(focus = field)
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

    fun requestExit() {
        if (state.value.busy || state.value.editorPending) return
        if (document?.let { !state.value.draft.sameContent(it.baseline) } == true) {
            saved[EDITOR + "exit"] = true
            mutable.value = state.value.copy(exit = true)
        } else enqueue(AutoTaskEditorEffectKind.Close)
    }

    fun keepEditing() {
        saved[EDITOR + "exit"] = false
        mutable.value = state.value.copy(exit = false)
    }

    fun discard() {
        if (!state.value.busy) {
            keepEditing()
            enqueue(AutoTaskEditorEffectKind.Close)
        }
    }

    private fun enqueue(
        kind: AutoTaskEditorEffectKind,
        token: String = UUID.randomUUID().toString(),
        path: String? = null,
        field: AutoTaskEditorField? = null,
        cursor: Int = 0,
    ) {
        if (stopped || state.value.effects.any { it.id == token }) return
        mutable.value =
            state.value.copy(
                effects =
                    state.value.effects + AutoTaskEditorEffect(token, kind, path, field, cursor)
            )
        effectsSaved()
    }

    private fun effectsSaved() {
        saved[EDITOR + "effects"] = GSON.toJson(state.value.effects)
    }

    fun consume(effect: AutoTaskEditorEffect) {
        if (state.value.effects.none { it.id == effect.id }) return
        mutable.value =
            state.value.copy(effects = state.value.effects.filterNot { it.id == effect.id })
        effectsSaved()
        if (effect.kind == AutoTaskEditorEffectKind.Editor) saved[EDITOR + "editorLaunched"] = true
        if (document?.delivery?.token == effect.id) {
            saved[EDITOR + "delivered"] = effect.id
            document = document!!.copy(delivery = null)
            checkpoint()
        }
        if (
            effect.kind in
                listOf(AutoTaskEditorEffectKind.Close, AutoTaskEditorEffectKind.SavedClose)
        ) {
            saved[EDITOR + "finished"] = true
            mutable.value = state.value.copy(finished = true)
        }
    }

    private fun savedDelivery(delivery: AutoTaskEditorDelivery) {
        saved[EDITOR + "existing"] = true
        saved[EDITOR + "savedResult"] = true
        mutable.value = state.value.copy(savedResult = true)
        if (saved.get<String>(EDITOR + "delivered") == delivery.token) return
        val kind =
            when (delivery.action) {
                AutoTaskEditorSaveAction.Close -> AutoTaskEditorEffectKind.SavedClose
                AutoTaskEditorSaveAction.Debug -> AutoTaskEditorEffectKind.SavedDebug
                AutoTaskEditorSaveAction.Login ->
                    if (delivery.loginAvailable) AutoTaskEditorEffectKind.SavedLogin
                    else AutoTaskEditorEffectKind.SavedOnly
            }
        if (kind == AutoTaskEditorEffectKind.SavedOnly)
            mutable.value = state.value.copy(issue = AutoTaskEditorIssue.NoLogin)
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

    fun save(action: AutoTaskEditorSaveAction) {
        if (!state.value.canEdit) return
        state.value.draft.validation()?.let {
            mutable.value = state.value.copy(issue = it)
            return
        }
        operation {
            val result =
                repository.save(session, document!!.copy(draft = state.value.draft), action)
            currentCoroutineContext().ensureActive()
            document = result
            mutable.value = state.value.copy(draft = result.draft)
            result.delivery?.let(::savedDelivery)
        }
    }

    fun copy() {
        if (state.value.canEdit) enqueue(AutoTaskEditorEffectKind.Clipboard)
    }

    fun requestPaste() {
        if (state.value.canEdit) enqueue(AutoTaskEditorEffectKind.Paste)
    }

    suspend fun copyText(): String = repository.export(id, state.value.draft)

    fun paste(text: String?) {
        operation {
            val draft = text?.let { repository.parse(it) }
            currentCoroutineContext().ensureActive()
            if (draft == null) mutable.value = state.value.copy(issue = AutoTaskEditorIssue.Format)
            else {
                mutable.value = state.value.copy(draft = draft)
                checkpoint()
            }
        }
    }

    fun help() {
        if (!state.value.busy) enqueue(AutoTaskEditorEffectKind.Help)
    }

    fun openEditor() {
        val field = state.value.focus
        if (!state.value.canEdit) return
        if (field?.code != true) {
            mutable.value = state.value.copy(issue = AutoTaskEditorIssue.Focus)
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
                AutoTaskEditorEffectKind.Editor,
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
                val field = saved.get<AutoTaskEditorField>(EDITOR + "editorField") ?: return@launch
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
                                        before.draft.with(field, AutoTaskEditorText(text, cursor)),
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
                mutable.value = state.value.copy(draft = result.draft, focus = field)
                saved[EDITOR + "focus"] = field
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

    val taskId
        get() = id

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
