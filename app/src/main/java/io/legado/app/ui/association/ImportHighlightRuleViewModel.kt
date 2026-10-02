package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// Existing parser/comparison consumers retain their pure APIs; the repository owns the implementation.
internal typealias HighlightRuleImportStatus = HighlightImportStatus
internal typealias HighlightRuleImportItem = HighlightImportComparison
internal fun parseHighlightRuleFile(text: String) = parseHighlightImportFile(text)
internal fun validateHighlightRuleFile(file: HighlightRuleFile) = validateHighlightImportFile(file)
internal fun compareImportedHighlightRules(imported: List<HighlightRule>, local: List<HighlightRule>) = compareHighlightImports(imported, local)

data class HighlightImportState(val items: List<HighlightImportItem> = emptyList(), val selected: Set<String> = emptySet(),
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val finished: Boolean = false, val refreshPending: Boolean = false) {
    val isSelectAll: Boolean get() = items.isNotEmpty() && items.all { it.key in selected }
    val selectCount: Int get() = selected.size
    val interactive: Boolean get() = !loading && !busy && !finished
}
class ImportHighlightRuleViewModel(private val repository: HighlightImportRepository,
    private val saved: SavedStateHandle, private val source: String) : ViewModel() {
    private val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(HighlightImportState(finished = saved["finished"] ?: false,
        refreshPending = saved["refreshPending"] ?: false))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init { if (state.value.finished) mutable.value = state.value.copy(loading = false) else load() }
    private fun failure(error: Exception) {
        if (!state.value.finished) mutable.value = state.value.copy(loading = false, busy = false, error = "ImportError:${error.localizedMessage}")
    }
    fun load() {
        if (state.value.finished || operation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (source.isEmpty()) { cancel(); return@launch }
                val staged = repository.restore(session) ?: HighlightImportSession(repository.read(source)).also { repository.stage(session, it.items) }
                if (state.value.finished) return@launch
                if (staged.committed) { imported(); return@launch }
                val keys = staged.items.mapTo(mutableSetOf()) { it.key }
                val selected = saved.get<ArrayList<String>>("selected")?.toSet()?.intersect(keys)
                    ?: staged.items.filter { it.selectedByDefault }.mapTo(mutableSetOf()) { it.key }
                saved["selected"] = ArrayList(selected)
                mutable.value = HighlightImportState(staged.items, selected, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    fun toggle(key: String) {
        val value = state.value
        if (!value.interactive || value.items.none { it.key == key }) return
        select(if (key in value.selected) value.selected - key else value.selected + key)
    }
    private fun select(keys: Set<String>) { saved["selected"] = ArrayList(keys); mutable.value = state.value.copy(selected = keys) }
    fun toggleAll() {
        val value = state.value
        if (value.interactive) select(if (value.isSelectAll) emptySet() else value.items.mapTo(mutableSetOf()) { it.key })
    }
    fun confirm() {
        val value = state.value
        if (!value.interactive || value.selected.isEmpty()) return
        mutable.value = value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { repository.insert(session, value.items, value.selected); imported() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    private fun imported() {
        saved["finished"] = true; saved["refreshPending"] = true
        mutable.value = state.value.copy(loading = false, busy = false, finished = true, refreshPending = true)
    }
    fun consumeRefresh() { saved["refreshPending"] = false; mutable.value = state.value.copy(refreshPending = false) }
    fun cancel() {
        if (state.value.busy || state.value.finished) return
        saved["finished"] = true; saved["refreshPending"] = false
        mutable.value = state.value.copy(loading = false, busy = false, finished = true, refreshPending = false)
        operation?.cancel()
    }
}
