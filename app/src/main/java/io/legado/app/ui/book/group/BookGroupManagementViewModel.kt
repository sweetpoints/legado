package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.BookGroupManagementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY = "book.group.management."

data class BookGroupManagementUiState(
    val groups: List<BookGroupEditorSnapshot> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val pendingAdd: Boolean = false,
    val finished: Boolean = false,
) {
    val canAdd
        get() = groups.count { it.id > 0 } < 63
}

class BookGroupManagementViewModel(
    private val repository: BookGroupManagementRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var watchJob: Job? = null
    private var committed = saved[KEY + "committed"] ?: false
    private var baseline = saved.get<ArrayList<Long>>(KEY + "baseline")?.toList()
    private var preview =
        if (committed) saved.get<ArrayList<Long>>(KEY + "order")?.toList() else baseline
    private var recovering = baseline != null && !committed
    private val mutableState =
        MutableStateFlow(
            BookGroupManagementUiState(
                pendingAdd = saved[KEY + "add"] ?: false,
                finished = saved[KEY + "finished"] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    init {
        if (recovering) clearSavedOrder()
        observe()
        if (committed && preview != null && !state.value.finished) finishReorder()
    }

    private fun clearSavedOrder() {
        saved.remove<ArrayList<Long>>(KEY + "baseline")
        saved.remove<ArrayList<Long>>(KEY + "order")
        saved.remove<Boolean>(KEY + "committed")
    }

    fun observe() {
        watchJob?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        watchJob = viewModelScope.launch {
            try {
                repository.observe().collect { rows ->
                    val byId = rows.associateBy { it.id }
                    val order = preview
                    val groups =
                        if (order == null) rows
                        else order.mapNotNull(byId::get) + rows.filterNot { it.id in order }
                    mutableState.value = state.value.copy(groups = groups, loading = false)
                    if (recovering) {
                        recovering = false
                        preview = null
                        baseline = null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    fun retry() {
        if (committed && preview != null) finishReorder() else observe()
    }

    fun requestAdd() {
        if (
            state.value.busy ||
                state.value.loading ||
                state.value.finished ||
                state.value.pendingAdd
        )
            return
        if (!state.value.canAdd) {
            mutableState.value = state.value.copy(error = "分组已达上限(63个)")
            return
        }
        saved[KEY + "add"] = true
        mutableState.value = state.value.copy(pendingAdd = true)
    }

    fun consumeAdd(): Boolean {
        if (!state.value.pendingAdd) return false
        saved[KEY + "add"] = false
        mutableState.value = state.value.copy(pendingAdd = false)
        return true
    }

    fun setShown(id: Long, shown: Boolean) = operation { repository.setShown(id, shown) }

    fun move(from: Long, to: Long) {
        if (state.value.busy || state.value.loading || state.value.finished || from == to) return
        val rows = state.value.groups.toMutableList()
        val start = rows.indexOfFirst { it.id == from }
        val end = rows.indexOfFirst { it.id == to }
        if (start < 0 || end < 0) return
        if (baseline == null) {
            baseline = rows.map { it.id }
            saved[KEY + "baseline"] = ArrayList(checkNotNull(baseline))
        }
        rows.add(end, rows.removeAt(start))
        preview = rows.map { it.id }
        committed = false
        saved[KEY + "order"] = ArrayList(checkNotNull(preview))
        saved[KEY + "committed"] = false
        mutableState.value = state.value.copy(groups = rows)
    }

    fun finishReorder() {
        if (recovering || state.value.busy || state.value.finished) return
        val order = preview ?: return
        committed = true
        saved[KEY + "committed"] = true
        operation {
            repository.reorder(order)
            preview = null
            baseline = null
            committed = false
            clearSavedOrder()
        }
    }

    fun cancelReorder() {
        if (committed) return
        baseline?.let { order ->
            val rows = state.value.groups
            val byId = rows.associateBy { it.id }
            mutableState.value =
                state.value.copy(
                    groups = order.mapNotNull(byId::get) + rows.filterNot { it.id in order }
                )
        }
        preview = null
        baseline = null
        clearSavedOrder()
    }

    fun close() {
        if (state.value.busy) return
        cancelReorder()
        saved[KEY + "finished"] = true
        saved[KEY + "add"] = false
        mutableState.value = state.value.copy(finished = true, pendingAdd = false)
    }

    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy || state.value.finished) return
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
            } finally {
                mutableState.value = state.value.copy(busy = false)
            }
        }
    }
}
