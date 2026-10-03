package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.AutoTaskImportItem
import io.legado.app.data.repository.AutoTaskImportRepository
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AutoTaskImportEditor(val key: String, val requestId: String)

data class AutoTaskImportState(
    val items: List<AutoTaskImportItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val editor: AutoTaskImportEditor? = null,
    val openEditor: Boolean = false,
    val finished: Boolean = false,
) {
    val selectedCount
        get() = selected.size

    val allSelected
        get() = items.isNotEmpty() && selected.size == items.size

    val canChange
        get() = !loading && !busy && !finished
}

class AutoTaskImportViewModel(
    private val repository: AutoTaskImportRepository,
    private val saved: SavedStateHandle,
    sessionId: String,
) : ViewModel() {
    private val id =
        saved.get<String>("autoImport.session")
            ?: sessionId.also { saved["autoImport.session"] = it }
    private val mutable =
        MutableStateFlow(AutoTaskImportState(finished = saved["autoImport.finished"] ?: false))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var selectionRestored = saved.contains("autoImport.selected")

    init {
        load()
    }

    fun load() {
        if (work?.isActive == true || state.value.finished) return
        mutable.value = state.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val session = repository.load(id)
                if (state.value.finished) return@launch
                val keys = session.items.mapTo(hashSetOf()) { it.key }
                val selected =
                    if (selectionRestored)
                        saved
                            .get<ArrayList<String>>("autoImport.selected")
                            .orEmpty()
                            .toSet()
                            .intersect(keys)
                    else
                        session.items
                            .filter { it.selectedByDefault }
                            .mapTo(linkedSetOf()) { it.key }
                val editorKey = saved.get<String>("autoImport.editorKey")
                val request = saved.get<String>("autoImport.editorRequest")
                val editor =
                    if (editorKey in keys && request != null)
                        AutoTaskImportEditor(editorKey!!, request)
                    else null
                mutable.value =
                    state.value.copy(
                        items = session.items.toList(),
                        selected = selected,
                        loading = false,
                        editor = editor,
                        openEditor =
                            editor != null && saved.get<Boolean>("autoImport.openEditor") == true,
                        finished = session.committed,
                    )
                saveSelection()
                if (session.committed) finish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        loading = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    fun toggle(key: String) {
        if (!state.value.canChange || state.value.items.none { it.key == key }) return
        val next = state.value.selected.toMutableSet()
        if (!next.add(key)) next.remove(key)
        mutable.value = state.value.copy(selected = next.toSet())
        saveSelection()
    }

    fun selectAll() {
        select(state.value.items.mapTo(linkedSetOf()) { it.key })
    }

    fun clearSelection() {
        select(emptySet())
    }

    private fun select(keys: Set<String>) {
        if (!state.value.canChange) return
        mutable.value = state.value.copy(selected = keys)
        saveSelection()
    }

    private fun saveSelection() {
        selectionRestored = true
        saved["autoImport.selected"] = ArrayList(state.value.selected)
    }

    fun edit(key: String) {
        if (!state.value.canChange || state.value.items.none { it.key == key }) return
        val next = (saved.get<Long>("autoImport.editorSequence") ?: 0) + 1
        saved["autoImport.editorSequence"] = next
        saved.remove<String>("autoImport.editorCodeHash")
        val editor = AutoTaskImportEditor(key, "$id:$next")
        saved["autoImport.editorKey"] = key
        saved["autoImport.editorRequest"] = editor.requestId
        saved["autoImport.openEditor"] = true
        mutable.value = state.value.copy(editor = editor, openEditor = true, error = null)
    }

    fun editorOpened(requestId: String) {
        if (state.value.editor?.requestId != requestId) return
        saved["autoImport.openEditor"] = false
        mutable.value = state.value.copy(openEditor = false)
    }

    fun codeSaved(json: String, requestId: String?) {
        val editor = state.value.editor ?: return
        if (!state.value.canChange || editor.requestId != requestId) return
        val hash =
            MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        if (saved.get<String>("autoImport.editorCodeHash") == hash) return
        editorOpened(editor.requestId)
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                val item = repository.edit(id, editor.key, json)
                saved["autoImport.editorCodeHash"] = hash
                val selected = state.value.selected.toMutableSet()
                if (item.selectedByDefault) selected.add(item.key) else selected.remove(item.key)
                mutable.value =
                    state.value.copy(
                        items = state.value.items.map { if (it.key == item.key) item else it },
                        selected = selected.toSet(),
                        busy = false,
                    )
                saveSelection()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    private fun clearEditor() {
        saved.remove<String>("autoImport.editorKey")
        saved.remove<String>("autoImport.editorRequest")
        saved.remove<String>("autoImport.editorCodeHash")
        saved["autoImport.openEditor"] = false
        mutable.value = state.value.copy(editor = null, openEditor = false)
    }

    fun confirm() {
        if (!state.value.canChange) return
        val selected = state.value.selected.toSet()
        mutable.value = state.value.copy(busy = true, error = null)
        clearEditor()
        work = viewModelScope.launch {
            try {
                repository.commit(id, selected)
                finish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    fun cancel() {
        if (state.value.busy || state.value.finished) return
        work?.cancel()
        clearEditor()
        finish()
    }

    private fun finish() {
        saved["autoImport.finished"] = true
        mutable.value = state.value.copy(finished = true, loading = false, busy = false)
    }

    fun stop() {
        work?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
