package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookSourcePickerItem
import io.legado.app.data.repository.BookSourcePickerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookSourcePickerState(
    val query: String = "",
    val items: List<BookSourcePickerItem> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val finished: Boolean = false,
    val delayOpen: Boolean = false,
    val delayLoading: Boolean = false,
    val delayDraft: String = "0",
    val error: String? = null,
) {
    val validDelay: Int?
        get() = delayDraft.toIntOrNull()?.takeIf { it in 0..9999 }
}

class BookSourcePickerViewModel(
    private val repository: BookSourcePickerRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            BookSourcePickerState(
                query = saved["picker.query"] ?: "",
                finished = saved["picker.finished"] ?: false,
                delayOpen = saved["picker.delayOpen"] ?: false,
                delayLoading = saved["picker.delayLoading"] ?: false,
                delayDraft = saved["picker.delayDraft"] ?: "0",
            )
        )
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var observationGeneration = 0
    private var selection: Job? = null
    private var delayJob: Job? = null
    private var generation = 0
    private var delayGeneration = 0
    private var payload: String? = null

    init {
        if (saved.get<Boolean>("picker.pending") == true) {
            saved.get<String>("picker.selected")?.let(::resolveSelection)
        }
        if (!state.value.finished) observe()
        if (!state.value.finished && state.value.delayOpen && state.value.delayLoading) loadDelay()
    }

    fun search(query: String) {
        if (state.value.busy || state.value.finished || state.value.query == query) return
        saved["picker.query"] = query
        mutable.update { it.copy(query = query, items = emptyList(), loading = true) }
        observe()
    }

    fun retry() {
        if (!state.value.finished && !state.value.busy) observe()
    }

    private fun observe() {
        val ticket = ++observationGeneration
        observation?.cancel()
        val query = state.value.query
        mutable.update { it.copy(loading = true, error = null) }
        observation = viewModelScope.launch {
            try {
                repository.observe(query).collect { rows ->
                    if (
                        ticket == observationGeneration &&
                            state.value.query == query &&
                            !state.value.finished
                    )
                        mutable.update { it.copy(items = rows, loading = false) }
                }
            } catch (error: Exception) {
                if (ticket == observationGeneration && !state.value.finished) {
                    failure(error) { it.copy(loading = false) }
                } else if (error is CancellationException) throw error
            }
        }
    }

    fun select(url: String) {
        if (state.value.busy || state.value.finished || state.value.items.none { it.url == url })
            return
        saved["picker.selected"] = url
        saved["picker.pending"] = true
        resolveSelection(url)
    }

    private fun resolveSelection(url: String) {
        val ticket = ++generation
        mutable.update { it.copy(busy = true, finished = false, error = null) }
        selection = viewModelScope.launch {
            try {
                val json = repository.source(url)
                if (ticket != generation) return@launch
                payload = json
                if (json == null) saved["picker.pending"] = false
                saved["picker.finished"] = true
                mutable.update { it.copy(busy = false, finished = true) }
            } catch (error: Exception) {
                if (ticket == generation) {
                    saved["picker.pending"] = false
                    failure(error) { it.copy(busy = false) }
                } else if (error is CancellationException) throw error
            }
        }
    }

    /** The Route invokes the host only after this marks the delivery consumed. */
    fun consumeSource(): String? {
        if (!state.value.finished || saved.get<Boolean>("picker.pending") != true) return null
        saved["picker.pending"] = false
        return payload.also { payload = null }
    }

    fun cancel() {
        ++observationGeneration
        observation?.cancel()
        ++generation
        selection?.cancel()
        payload = null
        saved["picker.pending"] = false
        saved["picker.finished"] = true
        mutable.update { it.copy(busy = false, finished = true) }
        closeDelay()
    }

    fun openDelay() {
        if (state.value.busy || state.value.finished || state.value.delayOpen) return
        saved["picker.delayOpen"] = true
        saved["picker.delayLoading"] = true
        mutable.update { it.copy(delayOpen = true, delayLoading = true, error = null) }
        loadDelay()
    }

    private fun loadDelay() {
        val ticket = ++delayGeneration
        delayJob = viewModelScope.launch {
            try {
                val value = repository.delay().toString()
                if (ticket == delayGeneration && state.value.delayOpen) {
                    saved["picker.delayDraft"] = value
                    saved["picker.delayLoading"] = false
                    mutable.update { it.copy(delayDraft = value, delayLoading = false) }
                }
            } catch (error: Exception) {
                if (ticket == delayGeneration) {
                    saved["picker.delayLoading"] = false
                    failure(error) { it.copy(delayLoading = false) }
                } else if (error is CancellationException) throw error
            }
        }
    }

    fun delayDraft(value: String) {
        if (!state.value.delayOpen || state.value.busy) return
        ++delayGeneration
        delayJob?.cancel()
        saved["picker.delayDraft"] = value
        saved["picker.delayLoading"] = false
        mutable.update { it.copy(delayDraft = value, delayLoading = false) }
    }

    fun stepDelay(delta: Int) =
        delayDraft(((state.value.validDelay ?: 0) + delta).coerceIn(0, 9999).toString())

    fun closeDelay() {
        if (state.value.busy && !state.value.finished) return
        ++delayGeneration
        delayJob?.cancel()
        saved["picker.delayOpen"] = false
        saved["picker.delayLoading"] = false
        mutable.update { it.copy(delayOpen = false, delayLoading = false) }
    }

    fun saveDelay() {
        val value = state.value.validDelay ?: return
        if (
            !state.value.delayOpen ||
                state.value.delayLoading ||
                state.value.busy ||
                state.value.finished
        )
            return
        mutable.update { it.copy(busy = true, error = null) }
        delayJob = viewModelScope.launch {
            try {
                repository.saveDelay(value)
                mutable.update { it.copy(busy = false) }
                closeDelay()
            } catch (error: Exception) {
                failure(error) { it.copy(busy = false) }
            }
        }
    }

    private fun failure(
        error: Exception,
        transform: (BookSourcePickerState) -> BookSourcePickerState,
    ) {
        if (error is CancellationException) throw error
        mutable.update { transform(it).copy(error = error.localizedMessage ?: "Error") }
    }
}
