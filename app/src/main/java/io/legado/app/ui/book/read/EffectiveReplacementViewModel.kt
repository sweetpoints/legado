package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.EffectiveReplacementRepository
import io.legado.app.data.repository.EffectiveReplacementRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EffectiveReplacementState(
    val rows: List<EffectiveReplacementRow> = emptyList(),
    val conversion: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val conversionPicker: Boolean = false,
    val editId: Long? = null,
    val finished: Boolean = false,
    val changed: Boolean = false,
    val refreshPending: Boolean = false,
)

class EffectiveReplacementViewModel(
    private val repository: EffectiveReplacementRepository,
    private val saved: SavedStateHandle,
    private val sourceIds: List<Long>?,
    private val readerRows: List<EffectiveReplacementRow>,
    private val conversionName: String,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            EffectiveReplacementState(
                conversionPicker = saved["effective.picker"] ?: false,
                editId = saved["effective.edit"],
                finished = saved["effective.finished"] ?: false,
                changed = saved["effective.changed"] ?: false,
                refreshPending = saved["effective.refresh"] ?: false,
            )
        )
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private val removed =
        saved.get<ArrayList<String>>("effective.removed")?.toMutableSet() ?: mutableSetOf()

    init {
        if (state.value.finished) mutable.value = state.value.copy(loading = false) else load()
    }

    fun load() {
        if (state.value.finished || operation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val value = repository.load(sourceIds, readerRows)
                val rows = value.rows.filterNot { it.key in removed }.toMutableList()
                if (sourceIds == null && value.conversion > 0 && "conversion" !in removed)
                    rows += EffectiveReplacementRow(0, conversionName, true)
                mutable.value =
                    state.value.copy(rows = rows, conversion = value.conversion, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    fun open(key: String) {
        if (state.value.loading || state.value.busy || state.value.finished) return
        val row = state.value.rows.find { it.key == key } ?: return
        if (row.conversion) picker(true)
        else {
            saved["effective.edit"] = row.id
            mutable.value = state.value.copy(editId = row.id)
        }
    }

    fun consumeEdit(): Long? {
        val id = state.value.editId ?: return null
        saved.remove<Long>("effective.edit")
        mutable.value = state.value.copy(editId = null)
        return id
    }

    fun edited() {
        if (!state.value.finished) changed()
    }

    private fun changed() {
        saved["effective.changed"] = true
        mutable.value = state.value.copy(changed = true)
    }

    fun picker(show: Boolean) {
        saved["effective.picker"] = show
        mutable.value = state.value.copy(conversionPicker = show)
    }

    fun chooseConversion(mode: Int) {
        if (!state.value.conversionPicker || mode !in 0..2) return
        if (mode == state.value.conversion) {
            picker(false)
            return
        }
        write {
            repository.conversion(mode)
            mutable.value = state.value.copy(conversion = mode)
            picker(false)
            changed()
        }
    }

    fun remove(key: String) {
        val row = state.value.rows.find { it.key == key } ?: return
        write {
            if (row.conversion) repository.conversion(0) else repository.disable(row.id)
            removed += key
            saved["effective.removed"] = ArrayList(removed)
            mutable.value =
                state.value.copy(
                    rows = state.value.rows.filterNot { it.key == key },
                    conversion = if (row.conversion) 0 else state.value.conversion,
                )
            changed()
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (state.value.busy || state.value.loading || state.value.finished) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
            } finally {
                mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun close() {
        if (state.value.busy || state.value.finished) return
        operation?.cancel()
        saved["effective.finished"] = true
        saved["effective.refresh"] = state.value.changed
        mutable.value =
            state.value.copy(loading = false, finished = true, refreshPending = state.value.changed)
    }

    fun consumeRefresh(): Boolean {
        if (!state.value.refreshPending) return false
        saved["effective.refresh"] = false
        mutable.value = state.value.copy(refreshPending = false)
        return true
    }
}
