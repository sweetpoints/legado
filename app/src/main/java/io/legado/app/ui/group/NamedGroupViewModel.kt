package io.legado.app.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.NamedGroupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class NamedGroupState(
    val groups: List<String> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val editing: Boolean = false,
    val original: String? = null,
    val name: String = "",
)

internal class NamedGroupViewModel(
    private val repository: NamedGroupRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            NamedGroupState(
                editing = saved.get<Boolean>("editing") == true,
                original = saved["original"],
                name = saved.get<String>("name").orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var mutation: Job? = null

    init {
        observe()
    }

    fun observe() {
        if (state.value.busy) return
        observation?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try {
                repository.groups().collect { groups ->
                    currentCoroutineContext().ensureActive()
                    mutable.value =
                        state.value.copy(groups = groups.distinct().toList(), loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(loading = false, error = failure.localizedMessage)
            }
        }
    }

    fun add() = open(null)

    fun edit(group: String) {
        if (group in state.value.groups) open(group)
    }

    private fun open(group: String?) {
        if (state.value.loading || state.value.busy) return
        saved["editing"] = true
        saved["original"] = group
        saved["name"] = group.orEmpty()
        mutable.value =
            state.value.copy(editing = true, original = group, name = group.orEmpty(), error = null)
    }

    fun name(value: String) {
        if (!state.value.editing || state.value.busy) return
        saved["name"] = value
        mutable.value = state.value.copy(name = value)
    }

    fun cancelEdit() {
        if (state.value.busy) return
        saved.remove<Boolean>("editing")
        saved.remove<String>("original")
        saved.remove<String>("name")
        mutable.value = state.value.copy(editing = false, original = null, name = "")
    }

    fun confirm() {
        val current = state.value
        if (!current.editing || current.busy || current.loading) return
        // A blank add is a no-op. A blank rename retains the original remove-membership behavior.
        if (current.original == null && current.name.isBlank()) {
            cancelEdit()
            return
        }
        mutate {
            if (current.original == null) repository.add(current.name)
            else repository.rename(current.original, current.name)
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(busy = false)
            cancelEdit()
        }
    }

    fun delete(group: String) {
        if (group !in state.value.groups || state.value.loading || state.value.busy) return
        mutate { repository.rename(group, null) }
    }

    private fun mutate(action: suspend () -> Unit) {
        mutable.value = state.value.copy(busy = true, error = null)
        mutation = viewModelScope.launch {
            try {
                action()
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(error = failure.localizedMessage)
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun stop() {
        observation?.cancel()
        mutation?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
