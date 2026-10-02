package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalFilePickerResult(val id: Long, val path: String)
data class LocalFilePickerState(val directory: String, val parent: String? = null,
    val crumbs: List<LocalFilePickerCrumb> = emptyList(), val rows: List<LocalFilePickerRow> = emptyList(),
    val selected: String? = null, val loaded: Boolean = false, val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val issue: LocalFilePickerIssue? = null, val creating: Boolean = false,
    val folderName: String = "", val folderStart: Int = 0, val folderEnd: Int = 0,
    val result: LocalFilePickerResult? = null, val finished: Boolean = false) {
    val canAct get() = loaded && !loading && !busy && result == null && !finished
}
class LocalFilePickerViewModel(private val repository: LocalFilePickerRepository,
    private val saved: SavedStateHandle, val config: LocalFilePickerConfig) : ViewModel() {
    private var generation = 0L
    private var work: Job? = null
    private val mutable = MutableStateFlow(LocalFilePickerState(
        directory = saved.get<String>("filePicker.directory") ?: config.root,
        selected = saved.get<String>("filePicker.selected"),
        creating = saved.get<Boolean>("filePicker.creating") == true,
        folderName = saved.get<String>("filePicker.folder") ?: "",
        folderStart = saved.get<Int>("filePicker.folderStart") ?: 0,
        folderEnd = saved.get<Int>("filePicker.folderEnd") ?: 0,
        result = saved.get<String>("filePicker.result")?.let { LocalFilePickerResult(saved.get<Long>("filePicker.resultId") ?: 1L, it) },
        finished = saved.get<Boolean>("filePicker.finished") == true))
    val state = mutable.asStateFlow()
    init { if (!state.value.finished && state.value.result == null) load() }
    fun load() = navigate(state.value.directory, clearSelection = false)
    fun navigate(path: String, clearSelection: Boolean = true) {
        if (state.value.busy || state.value.result != null || state.value.finished) return
        val token = ++generation; work?.cancel()
        mutable.value = state.value.copy(loading = true, error = null, issue = null, selected = if (clearSelection) null else state.value.selected)
        if (clearSelection) saved["filePicker.selected"] = null
        work = viewModelScope.launch {
            try {
                val snapshot = repository.list(config, path)
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                val selection = state.value.selected?.takeIf { value -> snapshot.rows.any { it.path == value && it.enabled && !it.directory } }
                saved["filePicker.directory"] = snapshot.directory; saved["filePicker.selected"] = selection
                mutable.value = state.value.copy(directory = snapshot.directory, parent = snapshot.parent, crumbs = snapshot.crumbs,
                    rows = snapshot.rows, selected = selection, loaded = true, loading = false)
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); failure(error) }
        }
    }
    fun click(path: String) {
        if (!state.value.canAct) return
        if (path == state.value.parent) { navigate(path); return }
        val row = state.value.rows.firstOrNull { it.path == path && it.enabled } ?: return
        if (row.directory) navigate(row.path)
        else if (!config.selectDirectory) { saved["filePicker.selected"] = path; mutable.value = state.value.copy(selected = path, error = null, issue = null) }
    }
    fun confirm() {
        if (!state.value.canAct) return
        val path = if (config.selectDirectory) state.value.directory else state.value.selected
        if (path == null) { mutable.value = state.value.copy(error = null, issue = LocalFilePickerIssue.FileRequired); return }
        mutable.value = state.value.copy(busy = true, error = null, issue = null)
        work = viewModelScope.launch {
            try {
                val validated = repository.validate(config, path); currentCoroutineContext().ensureActive()
                val id = (saved.get<Long>("filePicker.nextResultId") ?: 0L) + 1
                saved["filePicker.nextResultId"] = id; saved["filePicker.resultId"] = id; saved["filePicker.result"] = validated
                mutable.value = state.value.copy(busy = false, result = LocalFilePickerResult(id, validated))
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); failure(error) }
        }
    }
    fun delivered(id: Long) {
        if (state.value.result?.id != id) return
        saved["filePicker.result"] = null; finish()
    }
    fun showCreate() {
        if (!state.value.canAct) return
        saved["filePicker.creating"] = true; mutable.value = state.value.copy(creating = true, error = null, issue = null)
    }
    fun folderName(value: String, start: Int, end: Int) {
        if (!state.value.creating || state.value.busy) return
        val first = start.coerceIn(0, value.length); val last = end.coerceIn(0, value.length)
        saved["filePicker.folder"] = value; saved["filePicker.folderStart"] = first; saved["filePicker.folderEnd"] = last
        mutable.value = state.value.copy(folderName = value, folderStart = first, folderEnd = last)
    }
    fun cancelCreate() {
        if (state.value.busy) return
        saved["filePicker.creating"] = false; mutable.value = state.value.copy(creating = false, error = null, issue = null)
    }
    fun create() {
        if (!state.value.canAct || !state.value.creating) return
        if (state.value.folderName.isBlank()) { mutable.value = state.value.copy(error = null, issue = LocalFilePickerIssue.FolderNameRequired); return }
        val directory = state.value.directory; val name = state.value.folderName.trim()
        mutable.value = state.value.copy(busy = true, error = null, issue = null)
        work = viewModelScope.launch {
            try {
                repository.create(config, directory, name); currentCoroutineContext().ensureActive()
                saved["filePicker.creating"] = false; saved["filePicker.folder"] = ""; saved["filePicker.folderStart"] = 0; saved["filePicker.folderEnd"] = 0
                mutable.value = state.value.copy(busy = false, creating = false, folderName = "", folderStart = 0, folderEnd = 0)
                navigate(directory)
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); failure(error) }
        }
    }
    fun scroll(path: String): Pair<Int, Int> {
        val prefix = scrollKey(path); return (saved.get<Int>("$prefix.index") ?: 0) to (saved.get<Int>("$prefix.offset") ?: 0)
    }
    fun scrolled(path: String, index: Int, offset: Int) {
        if (!state.value.canAct || state.value.directory != path) return
        val prefix = scrollKey(path); saved["$prefix.index"] = index.coerceAtLeast(0); saved["$prefix.offset"] = offset.coerceAtLeast(0)
    }
    private fun scrollKey(path: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "filePicker.scroll.$hash"
    }
    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        mutable.value = state.value.copy(loading = false, busy = false,
            issue = (error as? LocalFilePickerIssueException)?.issue,
            error = if (error is LocalFilePickerIssueException) null else error.localizedMessage ?: error.toString())
    }
    fun cancel() { if (!state.value.busy && state.value.result == null) { work?.cancel(); finish() } }
    private fun finish() { saved["filePicker.finished"] = true; mutable.value = state.value.copy(finished = true, loading = false, result = null) }
    fun stop() { work?.cancel() }
    override fun onCleared() { stop() }
}
