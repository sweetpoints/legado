package io.legado.app.ui.widget.keyboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class KeyboardAssistSettingsState(
    val rows: List<KeyboardAssistSettingsRow> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val lineCount: Int = 1,
    val linePicker: Boolean = false,
    val selectedLines: Int = 1,
    val editor: KeyboardAssistSettingsDraft? = null,
    val editorLoading: Boolean = false,
    val dragging: Boolean = false,
    val pendingLines: Int? = null,
    val error: String? = null,
    val scroll: Int = 0,
    val offset: Int = 0,
)

class KeyboardAssistSettingsViewModel(
    private val repository: KeyboardAssistSettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            KeyboardAssistSettingsState(
                linePicker = saved.get<Boolean>("keyboardSettings.linePicker") == true,
                selectedLines = saved.get<Int>("keyboardSettings.selectedLines") ?: 1,
                pendingLines = saved.get<Int>("keyboardSettings.pendingLines"),
                scroll = saved.get<Int>("keyboardSettings.scroll") ?: 0,
                offset = saved.get<Int>("keyboardSettings.offset") ?: 0,
            )
        )
    val state = mutable.asStateFlow()
    val editorSession: String?
        get() = saved.get<String>("keyboardSettings.editorSession")

    private var latestRows: List<KeyboardAssistSettingsRow> = emptyList()
    private var dragBase: List<KeyboardAssistSettingsRow>? = null
    private var observation: Job? = null
    private var editorJob: Job? = null

    init {
        load()
        if (saved.get<String>("keyboardSettings.editorSession") != null) loadEditor()
    }

    fun load() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try {
                val lines = repository.initialRows()
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(
                        lineCount = lines,
                        selectedLines =
                            if (state.value.linePicker) state.value.selectedLines else lines,
                    )
                repository.observe().collect { rows ->
                    latestRows = rows
                    if (!state.value.dragging)
                        mutable.value = state.value.copy(rows = rows, loaded = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun openEditor(id: String? = null) {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.dragging ||
                saved.get<String>("keyboardSettings.editorSession") != null
        )
            return
        saved["keyboardSettings.editorSession"] = UUID.randomUUID().toString()
        saved["keyboardSettings.editorId"] = id
        loadEditor()
    }

    fun loadEditor() {
        if (editorJob?.isActive == true) return
        val session = saved.get<String>("keyboardSettings.editorSession") ?: return
        mutable.value = state.value.copy(editorLoading = true, error = null)
        editorJob = viewModelScope.launch {
            try {
                val draft =
                    repository.loadEditor(session, saved.get<String>("keyboardSettings.editorId"))
                currentCoroutineContext().ensureActive()
                if (saved.get<String>("keyboardSettings.editorSession") != session) return@launch
                if (draft.open)
                    mutable.value = state.value.copy(editor = draft, editorLoading = false)
                else clearEditor()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(
                        editorLoading = false,
                        error = error.localizedMessage ?: "Error",
                    )
            }
        }
    }

    fun editorText(key: Boolean, value: KeyboardAssistSettingsText) {
        if (state.value.busy || state.value.editorLoading) return
        val draft = state.value.editor ?: return
        val next =
            if (key) draft.copy(key = value.bounded(), revision = draft.revision + 1)
            else draft.copy(value = value.bounded(), revision = draft.revision + 1)
        mutable.value = state.value.copy(editor = next)
        persist(next)
    }

    private fun persist(draft: KeyboardAssistSettingsDraft) {
        val session = saved.get<String>("keyboardSettings.editorSession") ?: return
        viewModelScope.launch {
            try {
                repository.writeEditor(session, draft)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    suspend fun flushDraft() {
        val session = saved.get<String>("keyboardSettings.editorSession") ?: return
        state.value.editor?.let { repository.writeEditor(session, it) }
    }

    fun saveEditor() {
        if (state.value.busy || state.value.editorLoading) return
        val session = saved.get<String>("keyboardSettings.editorSession") ?: return
        val draft = state.value.editor ?: return
        mutable.value = state.value.copy(busy = true)
        editorJob = viewModelScope.launch {
            try {
                repository.saveEditor(session, draft)
                currentCoroutineContext().ensureActive()
                clearEditor()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun cancelEditor() {
        if (state.value.busy || state.value.editorLoading || editorJob?.isActive == true) return
        val session = saved.get<String>("keyboardSettings.editorSession") ?: return
        val draft = state.value.editor
        if (draft == null) {
            clearEditor()
            return
        }
        mutable.value = state.value.copy(busy = true)
        editorJob = viewModelScope.launch {
            try {
                repository.writeEditor(
                    session,
                    draft.copy(open = false, revision = draft.revision + 1),
                )
                currentCoroutineContext().ensureActive()
                clearEditor()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, error = error.localizedMessage ?: "Error")
            }
        }
    }

    private fun clearEditor() {
        saved.remove<String>("keyboardSettings.editorSession")
        saved.remove<String>("keyboardSettings.editorId")
        mutable.value = state.value.copy(editor = null, editorLoading = false, busy = false)
    }

    fun delete(id: String) {
        if (
            state.value.busy ||
                state.value.dragging ||
                state.value.editorLoading ||
                state.value.editor != null ||
                !state.value.loaded
        )
            return
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repository.delete(id)
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun beginDrag(): Boolean {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.editorLoading ||
                state.value.editor != null ||
                state.value.dragging
        )
            return false
        dragBase = state.value.rows
        mutable.value = state.value.copy(dragging = true)
        return true
    }

    fun move(from: Int, to: Int) {
        if (
            !state.value.dragging ||
                from !in state.value.rows.indices ||
                to !in state.value.rows.indices ||
                from == to
        )
            return
        val rows = state.value.rows.toMutableList()
        rows.add(to, rows.removeAt(from))
        mutable.value = state.value.copy(rows = rows)
    }

    fun cancelDrag() {
        dragBase = null
        mutable.value = state.value.copy(rows = latestRows, dragging = false)
    }

    fun finishDrag() {
        if (!state.value.dragging) return
        val rows = state.value.rows
        val base = dragBase
        dragBase = null
        mutable.value = state.value.copy(dragging = false)
        if (rows.map { it.id } == base?.map { it.id }) {
            mutable.value = state.value.copy(rows = latestRows)
            return
        }
        reorder(rows.map { it.id })
    }

    fun moveAccessibly(id: String, direction: Int) {
        if (!beginDrag()) return
        val from = state.value.rows.indexOfFirst { it.id == id }
        move(from, from + direction)
        finishDrag()
    }

    private fun reorder(ids: List<String>) {
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repository.reorder(ids)
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(rows = latestRows, busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(
                        rows = latestRows,
                        busy = false,
                        error = error.localizedMessage ?: "Error",
                    )
            }
        }
    }

    fun openLinePicker() {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.editorLoading ||
                state.value.editor != null ||
                state.value.dragging
        )
            return
        saved["keyboardSettings.linePicker"] = true
        saved["keyboardSettings.selectedLines"] = state.value.lineCount
        mutable.value = state.value.copy(linePicker = true, selectedLines = state.value.lineCount)
    }

    fun chooseLines(rows: Int) {
        if (state.value.linePicker && rows in 1..5) {
            saved["keyboardSettings.selectedLines"] = rows
            mutable.value = state.value.copy(selectedLines = rows)
        }
    }

    fun cancelLines() {
        if (state.value.busy) return
        saved["keyboardSettings.linePicker"] = false
        mutable.value = state.value.copy(linePicker = false)
    }

    fun saveLines() {
        if (!state.value.linePicker || state.value.busy) return
        val rows = state.value.selectedLines
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repository.setRows(rows)
                currentCoroutineContext().ensureActive()
                saved["keyboardSettings.linePicker"] = false
                saved["keyboardSettings.pendingLines"] = rows
                mutable.value =
                    state.value.copy(
                        lineCount = rows,
                        linePicker = false,
                        busy = false,
                        pendingLines = rows,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(busy = false, error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun linesDelivered(rows: Int) {
        if (state.value.pendingLines == rows) {
            saved.remove<Int>("keyboardSettings.pendingLines")
            mutable.value = state.value.copy(pendingLines = null)
        }
    }

    fun clearError() {
        mutable.value = state.value.copy(error = null)
    }

    fun scroll(index: Int, offset: Int) {
        saved["keyboardSettings.scroll"] = index
        saved["keyboardSettings.offset"] = offset
        mutable.value = state.value.copy(scroll = index, offset = offset)
    }

    fun stop() {
        viewModelScope.cancel()
    }
}
