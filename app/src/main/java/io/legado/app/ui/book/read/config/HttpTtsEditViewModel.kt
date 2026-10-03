package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HttpTtsEditorSelection(val start: Int = 0, val end: Int = start)

enum class HttpTtsEditorAction {
    Code,
    Saved,
    Login,
    Copy,
    Paste,
    Log,
    Help,
    Rebuild,
}

data class HttpTtsEditorEffect(
    val id: Long,
    val action: HttpTtsEditorAction,
    val text: String = "",
    val cursor: Int = 0,
    val field: HttpTtsEditorField? = null,
)

data class HttpTtsEditUiState(
    val draft: HttpTtsEditorDraft,
    val selections: Map<HttpTtsEditorField, HttpTtsEditorSelection> = emptyMap(),
    val focus: HttpTtsEditorField? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val exit: Boolean = false,
    val header: String? = null,
    val finished: Boolean = false,
    val error: String? = null,
    val pending: List<HttpTtsEditorEffect> = emptyList(),
)

class HttpTtsEditViewModel(
    private val repository: HttpTtsEditorRepository,
    private val saved: SavedStateHandle,
    editId: Long? = null,
) : ViewModel() {
    private val id = saved.get<Long>("ttsEdit.id") ?: editId ?: System.currentTimeMillis()
    private var baseline =
        saved.get<String>("ttsEdit.baseline")?.let {
            GSON.fromJson(it, HttpTtsEditorDraft::class.java)
        }
    private var nextId = saved.get<Long>("ttsEdit.next") ?: 0L
    private var editingRevision = 0L
    private var codeField = saved.get<String>("ttsEdit.codeField")?.let(HttpTtsEditorField::valueOf)
    private var pasteJob: Job? = null
    private var loadJob: Job? = null
    private var headerRevision = 0L
    private val restored = saved.get<Boolean>("ttsEdit.loaded") == true
    private val mutable =
        MutableStateFlow(
            HttpTtsEditUiState(
                draft =
                    saved.get<String>("ttsEdit.draft")?.let {
                        GSON.fromJson(it, HttpTtsEditorDraft::class.java)
                    } ?: HttpTtsEditorDraft(id),
                selections =
                    HttpTtsEditorField.entries.associateWith { field ->
                        HttpTtsEditorSelection(
                            saved["ttsEdit.${field.name}.start"] ?: 0,
                            saved["ttsEdit.${field.name}.end"] ?: 0,
                        )
                    },
                focus = saved.get<String>("ttsEdit.focus")?.let(HttpTtsEditorField::valueOf),
                exit = saved["ttsEdit.exit"] ?: false,
                header = saved["ttsEdit.header"],
                finished = saved["ttsEdit.finished"] ?: false,
                loading = !restored && editId != null,
                pending =
                    (saved.get<ArrayList<String>>("ttsEdit.effects") ?: arrayListOf()).map { value
                        ->
                        val pieces = value.split('\n', limit = 5)
                        HttpTtsEditorEffect(
                            pieces[0].toLong(),
                            HttpTtsEditorAction.valueOf(pieces[1]),
                            pieces[4],
                            pieces[2].toInt(),
                            pieces[3].takeIf(String::isNotBlank)?.let(HttpTtsEditorField::valueOf),
                        )
                    },
            )
        )
    val state = mutable.asStateFlow()

    init {
        saved["ttsEdit.id"] = id
        if (!restored && editId != null) retryLoad() else persist()
    }

    fun retryLoad() {
        if (loadJob?.isActive == true) return
        mutable.update { it.copy(loading = true, loadFailed = false, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val loaded = repository.load(id) ?: error("未找到朗读引擎")
                baseline = loaded
                if (editingRevision == 0L) mutable.update { it.copy(draft = loaded) }
                mutable.update { it.copy(loading = false, loadFailed = false) }
                persist()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update {
                    it.copy(
                        loading = false,
                        loadFailed = true,
                        error = error.localizedMessage ?: error.toString(),
                    )
                }
                persist()
            }
        }
    }

    private fun persist() {
        val value = state.value
        saved["ttsEdit.loaded"] = !value.loading && !value.loadFailed
        saved["ttsEdit.draft"] = GSON.toJson(value.draft)
        saved["ttsEdit.baseline"] = baseline?.let { GSON.toJson(it) }
        saved["ttsEdit.focus"] = value.focus?.name
        saved["ttsEdit.codeField"] = codeField?.name
        saved["ttsEdit.exit"] = value.exit
        saved["ttsEdit.header"] = value.header
        saved["ttsEdit.finished"] = value.finished
        saved["ttsEdit.next"] = nextId
        value.selections.forEach { (field, selection) ->
            saved["ttsEdit.${field.name}.start"] = selection.start
            saved["ttsEdit.${field.name}.end"] = selection.end
        }
        saved["ttsEdit.effects"] =
            ArrayList(
                value.pending.map {
                    "${it.id}\n${it.action}\n${it.cursor}\n${it.field?.name.orEmpty()}\n${it.text}"
                }
            )
    }

    private fun execute(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun fail(error: Exception) {
        mutable.update {
            it.copy(
                error = error.localizedMessage ?: error.toString(),
                busy = false,
                loading = false,
            )
        }
    }

    private fun effect(
        action: HttpTtsEditorAction,
        text: String = "",
        cursor: Int = 0,
        field: HttpTtsEditorField? = null,
    ) {
        if (state.value.finished) return
        val value = HttpTtsEditorEffect(++nextId, action, text, cursor, field)
        mutable.update { it.copy(pending = it.pending + value) }
        persist()
    }

    fun consume(id: Long) {
        mutable.update { it.copy(pending = it.pending.filterNot { event -> event.id == id }) }
        persist()
    }

    fun edit(field: HttpTtsEditorField, text: String, start: Int, end: Int) {
        if (
            state.value.busy ||
                state.value.finished ||
                state.value.loading ||
                state.value.loadFailed
        )
            return
        editingRevision++
        mutable.update {
            it.copy(
                draft = it.draft.edit(field, text),
                selections =
                    it.selections +
                        (field to
                            HttpTtsEditorSelection(
                                start.coerceIn(0, text.length),
                                end.coerceIn(0, text.length),
                            )),
                error = null,
            )
        }
        persist()
    }

    fun cookie(value: Boolean) {
        if (
            state.value.busy ||
                state.value.finished ||
                state.value.loading ||
                state.value.loadFailed
        )
            return
        editingRevision++
        mutable.update { it.copy(draft = it.draft.copy(cookie = value)) }
        persist()
    }

    fun focus(field: HttpTtsEditorField) {
        mutable.update { it.copy(focus = field) }
        persist()
    }

    fun fullEdit() {
        val field = state.value.focus
        if (field == null) {
            mutable.update { it.copy(error = "请将光标定位在文本框") }
            return
        }
        if (state.value.busy || state.value.loading || state.value.loadFailed || codeField != null)
            return
        codeField = field
        effect(
            HttpTtsEditorAction.Code,
            state.value.draft.text(field),
            state.value.selections[field]?.start ?: 0,
            field,
        )
    }

    fun codeResult(text: String?, cursor: Int?) {
        val field =
            codeField
                ?: run {
                    mutable.update { it.copy(error = "文本框失去焦点") }
                    return
                }
        if (text != null) {
            val position = cursor?.takeIf { it in 0..text.length } ?: text.length
            edit(field, text, position, position)
            focus(field)
        }
        codeField = null
        persist()
    }

    fun codeCancelled() {
        codeField = null
        persist()
    }

    fun paste(text: String?) {
        if (
            state.value.busy ||
                state.value.loading ||
                state.value.loadFailed ||
                state.value.finished
        )
            return
        if (text.isNullOrBlank()) {
            mutable.update { it.copy(error = "剪贴板为空") }
            return
        }
        pasteJob?.cancel()
        val revision = editingRevision
        pasteJob = execute {
            val draft = repository.parse(text, id)
            if (revision == editingRevision && !state.value.finished && !state.value.busy) {
                editingRevision++
                mutable.update { it.copy(draft = draft, selections = emptyMap(), error = null) }
                persist()
            }
        }
    }

    fun request(action: HttpTtsEditorAction) {
        if (
            state.value.busy ||
                state.value.loading ||
                state.value.loadFailed ||
                state.value.finished
        )
            return
        when (action) {
            HttpTtsEditorAction.Code -> fullEdit()
            HttpTtsEditorAction.Saved -> save(false)
            HttpTtsEditorAction.Login -> save(true)
            HttpTtsEditorAction.Copy -> {
                val draft = state.value.draft
                execute { effect(action, repository.copy(draft)) }
            }
            else -> effect(action)
        }
    }

    fun showHeader() {
        val draft = state.value.draft
        val revision = ++headerRevision
        execute {
            val header = repository.loginHeader(draft)
            if (revision == headerRevision && !state.value.finished) {
                mutable.update { it.copy(header = header?.takeIf(String::isNotBlank) ?: "") }
                persist()
            }
        }
    }

    fun closeHeader() {
        headerRevision++
        mutable.update { it.copy(header = null) }
        persist()
    }

    fun deleteHeader() {
        val draft = state.value.draft
        execute { repository.deleteLoginHeader(draft) }
    }

    fun save(login: Boolean) {
        if (
            state.value.busy ||
                state.value.loading ||
                state.value.loadFailed ||
                state.value.finished
        )
            return
        val draft = state.value.draft
        if (login && draft.loginUrl.isBlank()) {
            mutable.update { it.copy(error = "登录url不能为空") }
            return
        }
        pasteJob?.cancel()
        editingRevision++
        mutable.update { it.copy(busy = true, error = null) }
        execute {
            val rebuild = repository.save(baseline, draft)
            baseline = draft
            if (rebuild) effect(HttpTtsEditorAction.Rebuild)
            effect(
                if (login) HttpTtsEditorAction.Login else HttpTtsEditorAction.Saved,
                id.toString(),
            )
            mutable.update { it.copy(busy = false, finished = !login, exit = false) }
            persist()
        }
    }

    fun requestExit() {
        if (state.value.busy || state.value.loading) return
        val changed =
            baseline?.let { !state.value.draft.entity().equal(it.entity()) }
                ?: (state.value.draft != HttpTtsEditorDraft(id))
        if (!changed) {
            pasteJob?.cancel()
            codeField = null
        }
        mutable.update {
            if (changed) it.copy(exit = true) else it.copy(finished = true, pending = emptyList())
        }
        persist()
    }

    fun keepEditing() {
        mutable.update { it.copy(exit = false) }
        persist()
    }

    fun discard() {
        if (state.value.busy) return
        pasteJob?.cancel()
        codeField = null
        mutable.update { it.copy(exit = false, finished = true, pending = emptyList()) }
        persist()
    }
}
