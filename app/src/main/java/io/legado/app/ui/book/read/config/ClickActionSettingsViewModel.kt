package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.ClickActionRegion
import io.legado.app.data.preferences.ClickActionSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClickActionSettingsUiState(
    val actions: Map<ClickActionRegion, Int>,
    val editing: ClickActionRegion? = null,
)

class ClickActionSettingsViewModel(
    private val repository: ClickActionSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            ClickActionSettingsUiState(
                repository.load(),
                savedState.get<String>("clickAction.editing")?.let { name ->
                    ClickActionRegion.entries.find { it.name == name }
                },
            )
        )
    val state = mutableState.asStateFlow()
    private var observer: AutoCloseable? = null
    private var generation = 0
    private var closed = false

    fun selectRegion(region: ClickActionRegion) {
        if (!closed) editing(region)
    }

    fun dismissPicker() = editing(null)

    private fun editing(region: ClickActionRegion?) {
        savedState["clickAction.editing"] = region?.name
        mutableState.value = state.value.copy(editing = region)
    }

    fun selectAction(action: Int) {
        if (closed || action !in -1..13) return
        val region = state.value.editing ?: return
        if (repository.load()[region] != action) repository.setAction(region, action)
        mutableState.value = state.value.copy(actions = repository.load())
        editing(null)
    }

    fun startObserving() {
        if (closed || observer != null) return
        refresh()
        val version = ++generation
        observer = repository.observe { if (version == generation && !closed) refresh() }
    }

    private fun refresh() {
        mutableState.value = state.value.copy(actions = repository.load())
    }

    fun stopObserving() {
        generation++
        observer?.close()
        observer = null
    }

    fun close() {
        if (closed) return
        closed = true
        stopObserving()
        editing(null)
        repository.ensureMenuAction()
        refresh()
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }
}
