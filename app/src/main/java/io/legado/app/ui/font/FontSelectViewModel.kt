package io.legado.app.ui.font

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.FontEntry
import io.legado.app.data.repository.FontSelectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FontSelectUiState(
    val entries: List<FontEntry> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val error: String? = null,
    val importSucceeded: Boolean = false,
    val invalidImport: Boolean = false,
    val openFolder: Boolean = false,
    val systemPicker: Boolean = false,
    val selectedPath: String? = null,
    val finished: Boolean = false,
)

class FontSelectViewModel(val repository: FontSelectionRepository, private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(FontSelectUiState(
        systemPicker = savedState["font.systemPicker"] ?: false,
        selectedPath = savedState["font.selectedPath"],
        openFolder = savedState["font.openFolder"] ?: false,
    ))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var folder: String? = repository.storedFolder()
    private var generation = 0

    fun load(folder: String? = this.folder, openWhenEmpty: Boolean = false) {
        if (state.value.finished) return
        this.folder = folder
        val currentGeneration = ++generation
        loadJob?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val result = repository.load(folder)
                coroutineContext.ensureActive()
                if (generation != currentGeneration) return@launch
                mutableState.value = state.value.copy(entries = result.entries, loading = false, error = result.error)
                if (openWhenEmpty && result.entries.isEmpty() && (folder.isNullOrEmpty() || result.unavailableFolder)) requestFolder()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: error.toString())
            }
        }
    }
    fun rememberFolder(folder: String) { repository.storeFolder(folder); this.folder = folder }
    fun importFont(uri: String) {
        if (state.value.importing || state.value.finished) return
        mutableState.value = state.value.copy(importing = true, error = null, importSucceeded = false, invalidImport = false)
        viewModelScope.launch {
            try {
                repository.importFont(uri)
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(importing = false, importSucceeded = true)
                load()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(importing = false, invalidImport = error is IllegalArgumentException,
                    error = error.localizedMessage ?: error.toString())
            }
        }
    }
    fun requestFolder() {
        if (state.value.finished) return
        mutableState.value = state.value.copy(openFolder = true)
        savedState["font.openFolder"] = true
    }
    fun folderOpened() {
        mutableState.value = state.value.copy(openFolder = false)
        savedState["font.openFolder"] = false
    }
    fun select(path: String) {
        if (state.value.finished || state.value.selectedPath != null || state.value.loading || state.value.importing) return
        if (state.value.entries.none { it.path == path }) return
        emitSelection(path)
    }
    fun defaultFont(chooseSystemTypeface: Boolean) {
        if (state.value.finished || state.value.selectedPath != null || state.value.importing) return
        if (chooseSystemTypeface) {
            mutableState.value = state.value.copy(systemPicker = true)
            savedState["font.systemPicker"] = true
        } else emitSelection("")
    }
    fun cancelSystemPicker() {
        mutableState.value = state.value.copy(systemPicker = false)
        savedState["font.systemPicker"] = false
    }
    fun selectSystemTypeface(index: Int) {
        if (!state.value.systemPicker || index !in 0..2) return
        repository.selectSystemTypeface(index)
        cancelSystemPicker()
        emitSelection("")
    }
    private fun emitSelection(path: String) {
        mutableState.value = state.value.copy(selectedPath = path)
        savedState["font.selectedPath"] = path
    }
    fun selectionHandled() {
        loadJob?.cancel()
        generation++
        mutableState.value = state.value.copy(selectedPath = null, openFolder = false, finished = true)
        savedState["font.selectedPath"] = null as String?
        savedState["font.openFolder"] = false
    }
}
