package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookGroupSelectionRepository
import io.legado.app.data.repository.BookGroupEditorSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY = "book.group.selection."
data class BookGroupSelectionResult(val requestCode: Int, val groupId: Long)
data class BookGroupSelectionUiState(val groups: List<BookGroupEditorSnapshot> = emptyList(),
    val groupId: Long = 0, val requestCode: Int = -1, val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val pendingResult: Boolean = false, val finished: Boolean = false) {
    fun checked(id: Long) = id > 0 && (groupId and id) != 0L
}
class BookGroupSelectionViewModel(private val repository: BookGroupSelectionRepository, private val saved: SavedStateHandle, initialGroupId: Long = 0, initialRequestCode: Int = -1) : ViewModel() {
    private var watchJob: Job? = null
    private var committed = saved[KEY + "committed"] ?: false
    private var baseline = saved.get<ArrayList<Long>>(KEY + "baseline")?.toList()
    private var preview = if (committed) saved.get<ArrayList<Long>>(KEY + "order")?.toList() else baseline
    private var recovering = baseline != null && !committed
    private val mutableState = MutableStateFlow(BookGroupSelectionUiState(
        groupId = saved[KEY + "mask"] ?: initialGroupId, requestCode = saved[KEY + "code"] ?: initialRequestCode, pendingResult = saved[KEY + "result"] ?: false, finished = saved[KEY + "finished"] ?: false))
    val state = mutableState.asStateFlow()
    init {
        saved[KEY + "mask"] = state.value.groupId; saved[KEY + "code"] = state.value.requestCode
        if (recovering) clearSavedOrder()
        observe()
        if (committed && preview != null && !state.value.finished) finishReorder()
    }
    private fun clearSavedOrder() { saved.remove<ArrayList<Long>>(KEY + "baseline"); saved.remove<ArrayList<Long>>(KEY + "order"); saved.remove<Boolean>(KEY + "committed") }
    fun observe() {
        watchJob?.cancel(); mutableState.value = state.value.copy(loading = true, error = null)
        watchJob = viewModelScope.launch {
            try { repository.observe().collect { rows ->
                val byId = rows.associateBy { it.id }; val order = preview
                val groups = if (order == null) rows else order.mapNotNull(byId::get) + rows.filterNot { it.id in order }
                mutableState.value = state.value.copy(groups = groups, loading = false)
                if (recovering) { recovering = false; preview = null; baseline = null }
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun retry() { if (committed && preview != null) finishReorder() else observe() }
    fun setChecked(id: Long, checked: Boolean) {
        if (state.value.busy || state.value.finished || id <= 0 || state.value.groups.none { it.id == id }) return
        val mask = if (checked) state.value.groupId or id else state.value.groupId and id.inv()
        saved[KEY + "mask"] = mask; mutableState.value = state.value.copy(groupId = mask)
    }
    fun confirm() {
        if (state.value.busy || state.value.finished) return
        cancelReorder(); saved[KEY + "result"] = true; saved[KEY + "finished"] = true
        mutableState.value = state.value.copy(pendingResult = true, finished = true)
    }
    fun consumeResult(): BookGroupSelectionResult? {
        if (!state.value.pendingResult) return null
        saved[KEY + "result"] = false; mutableState.value = state.value.copy(pendingResult = false)
        return BookGroupSelectionResult(state.value.requestCode, state.value.groupId)
    }
    fun move(from: Long, to: Long) {
        if (state.value.busy || state.value.loading || state.value.finished || from == to) return
        val rows = state.value.groups.toMutableList(); val start = rows.indexOfFirst { it.id == from }; val end = rows.indexOfFirst { it.id == to }
        if (start < 0 || end < 0) return
        if (baseline == null) { baseline = rows.map { it.id }; saved[KEY + "baseline"] = ArrayList(checkNotNull(baseline)) }
        rows.add(end, rows.removeAt(start)); preview = rows.map { it.id }; committed = false
        saved[KEY + "order"] = ArrayList(checkNotNull(preview)); saved[KEY + "committed"] = false
        mutableState.value = state.value.copy(groups = rows)
    }
    fun finishReorder() {
        if (recovering || state.value.busy || state.value.finished) return
        val order = preview ?: return
        committed = true; saved[KEY + "committed"] = true
        operation {
            repository.reorder(order); preview = null; baseline = null; committed = false; clearSavedOrder()
        }
    }
    fun cancelReorder() {
        if (committed) return
        baseline?.let { order -> val rows = state.value.groups; val byId = rows.associateBy { it.id }
            mutableState.value = state.value.copy(groups = order.mapNotNull(byId::get) + rows.filterNot { it.id in order }) }
        preview = null; baseline = null; clearSavedOrder()
    }
    fun close() {
        if (state.value.busy) return
        cancelReorder(); saved[KEY + "finished"] = true; saved[KEY + "result"] = false
        mutableState.value = state.value.copy(finished = true, pendingResult = false)
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy || state.value.finished) return
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(error = error.localizedMessage ?: "ERROR") }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
