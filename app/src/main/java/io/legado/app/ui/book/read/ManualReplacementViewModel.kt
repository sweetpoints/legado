package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ManualReplacementRepository
import io.legado.app.data.repository.ManualReplacementRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ManualReplacementState(
    val rows: List<ManualReplacementRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
    val finished: Boolean = false,
    val confirmationPending: Boolean = false,
    val completionPending: Boolean = false,
    val result: List<Long> = emptyList(),
) {
    val allSelected: Boolean
        get() = selected.size == rows.size
}

data class ManualReplacementCompletion(val selection: List<Long>?)

/** Selection is a draft; only the resumed host applies a confirmed result. */
class ManualReplacementViewModel(
    private val repository: ManualReplacementRepository,
    private val saved: SavedStateHandle,
    private val source: Boolean,
    initialIds: List<Long>,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            ManualReplacementState(
                selected =
                    (saved.get<LongArray>("manual.selected")?.toList() ?: initialIds).toSet(),
                finished = saved["manual.finished"] ?: false,
                confirmationPending = saved["manual.pending"] ?: false,
                completionPending =
                    saved["manual.completionPending"] ?: (saved["manual.finished"] ?: false),
                result = saved.get<LongArray>("manual.result")?.toList().orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var rangeStart: Int? = null
    private var baseline: Set<Long>? = null
    private var rangeSelect = false

    init {
        if (state.value.finished) mutable.value = state.value.copy(loading = false) else load()
    }

    private fun persist() {
        saved["manual.selected"] = (baseline ?: state.value.selected).toLongArray()
        saved["manual.finished"] = state.value.finished
        saved["manual.pending"] = state.value.confirmationPending
        saved["manual.completionPending"] = state.value.completionPending
        saved["manual.result"] = state.value.result.toLongArray()
    }

    fun load() {
        if (state.value.finished || loading?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        loading = viewModelScope.launch {
            try {
                val rows = repository.candidates(source).distinctBy { it.id }
                if (!state.value.finished) {
                    mutable.value =
                        state.value.copy(
                            rows = rows,
                            selected = state.value.selected.intersect(rows.map { it.id }.toSet()),
                            loading = false,
                        )
                    persist()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    private fun editable() =
        !state.value.loading && state.value.error == null && !state.value.finished

    fun toggle(id: Long) {
        if (!editable() || state.value.rows.none { it.id == id }) return
        cancelRange()
        mutable.value =
            state.value.copy(
                selected =
                    if (id in state.value.selected) state.value.selected - id
                    else state.value.selected + id
            )
        persist()
    }

    fun all() {
        if (!editable()) return
        cancelRange()
        mutable.value =
            state.value.copy(
                selected =
                    if (state.value.allSelected) emptySet()
                    else state.value.rows.map { it.id }.toSet()
            )
        persist()
    }

    fun beginRange(index: Int) {
        if (!editable() || index !in state.value.rows.indices) return
        cancelRange()
        baseline = state.value.selected.toSet()
        rangeStart = index
        rangeSelect = state.value.rows[index].id !in baseline!!
        moveRange(index)
    }

    fun moveRange(index: Int) {
        val first = rangeStart ?: return
        val original = baseline ?: return
        if (index !in state.value.rows.indices) return
        val ids =
            state.value.rows
                .subList(minOf(first, index), maxOf(first, index) + 1)
                .map { it.id }
                .toSet()
        mutable.value =
            state.value.copy(selected = if (rangeSelect) original + ids else original - ids)
    }

    fun endRange() {
        baseline = null
        rangeStart = null
        persist()
    }

    fun cancelRange() {
        baseline?.let { mutable.value = state.value.copy(selected = it) }
        baseline = null
        rangeStart = null
    }

    fun confirm() {
        if (!editable()) return
        endRange()
        mutable.value =
            state.value.copy(
                finished = true,
                confirmationPending = true,
                completionPending = true,
                result = state.value.rows.filter { it.id in state.value.selected }.map { it.id },
            )
        persist()
    }

    fun cancel() {
        if (state.value.finished) return
        cancelRange()
        loading?.cancel()
        mutable.value =
            state.value.copy(
                loading = false,
                finished = true,
                confirmationPending = false,
                completionPending = true,
            )
        persist()
    }

    /** Claim selection and dismissal together before calling an external host. */
    fun consumeCompletion(): ManualReplacementCompletion? {
        while (true) {
            val current = mutable.value
            if (!current.finished || !current.completionPending) return null
            val consumed = current.copy(confirmationPending = false, completionPending = false)
            if (mutable.compareAndSet(current, consumed)) {
                persist()
                return ManualReplacementCompletion(
                    if (current.confirmationPending) current.result.toList() else null
                )
            }
        }
    }

    fun consumeConfirmation(): List<Long>? {
        if (!state.value.confirmationPending) return null
        val result = state.value.result.toList()
        mutable.value = state.value.copy(confirmationPending = false)
        persist()
        return result
    }

    fun stop() {
        cancelRange()
        loading?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
