package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.RemoteServerChoice
import io.legado.app.data.repository.RemoteServerListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServersUiState(
    val rows: List<RemoteServerChoice> = emptyList(),
    val selected: Long,
    val loading: Boolean = true,
    val deleting: Boolean = false,
    val deleteId: Long? = null,
    val error: String? = null,
    val finished: Boolean = false,
)

class ServersViewModel(
    private val repository: RemoteServerListRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var collection: Job? = null
    private val mutable =
        MutableStateFlow(
            ServersUiState(
                selected = saved["servers.selected"] ?: repository.selected,
                deleteId = saved["servers.delete"],
                finished = saved["servers.finished"] ?: false,
            )
        )
    val state = mutable.asStateFlow()

    fun start() {
        if (collection != null || state.value.finished) return
        mutable.value = state.value.copy(loading = true, error = null)
        collection = viewModelScope.launch {
            try {
                repository.observe().collect {
                    if (!state.value.finished)
                        mutable.value = state.value.copy(rows = it.toList(), loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(
                        loading = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    fun stop() {
        collection?.cancel()
        collection = null
    }

    fun retry() {
        stop()
        start()
    }

    fun choose(id: Long) {
        if (state.value.finished || state.value.rows.none { it.id == id }) return
        saved["servers.selected"] = id
        mutable.value = state.value.copy(selected = id)
    }

    fun requestDelete(id: Long?) {
        if (state.value.finished || state.value.deleting) return
        saved["servers.delete"] = id
        mutable.value = state.value.copy(deleteId = id)
    }

    fun delete() {
        val id = state.value.deleteId ?: return
        if (state.value.deleting || state.value.finished) return
        requestDelete(null)
        mutable.value = state.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!state.value.finished)
                    mutable.value =
                        state.value.copy(error = error.localizedMessage ?: error.toString())
            } finally {
                mutable.value = state.value.copy(deleting = false)
            }
        }
    }

    fun applySelection() = finish(state.value.selected)

    fun useDefault() = finish(repository.defaultId)

    private fun finish(id: Long) {
        if (state.value.finished) return
        try {
            repository.select(id)
            close()
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage ?: error.toString())
        }
    }

    fun close() {
        if (!state.value.finished) {
            saved["servers.finished"] = true
            mutable.value = state.value.copy(finished = true)
            stop()
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
