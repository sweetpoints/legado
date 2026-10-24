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
    val selectionStart: Int = name.length,
    val selectionEnd: Int = selectionStart,
    val recoveryFailed: Boolean = false,
    val mutationUncertain: Boolean = false,
)

internal class NamedGroupViewModel(
    private val repository: NamedGroupRepository,
    private val saved: SavedStateHandle,
    private val session: NamedGroupDraftSession? = null,
) : ViewModel() {
    private val restored = runCatching { session?.restore() }
    private val mutable = MutableStateFlow(
        (restored.getOrNull() ?: NamedGroupState(
            editing = saved.get<Boolean>("editing") == true,
            original = saved["original"], name = saved.get<String>("name").orEmpty(),
            selectionStart = saved.get<Int>("selectionStart") ?: saved.get<String>("name").orEmpty().length,
            selectionEnd = saved.get<Int>("selectionEnd") ?: saved.get<String>("name").orEmpty().length,
        )).let { state -> if (restored.isFailure) state.copy(recoveryFailed = true, editing = false,
            error = "Group draft recovery failed; retry recovery. Closing preserves the draft file") else state }
    )
    val state = mutable.asStateFlow()
    private var pendingRecoveryDraft: NamedGroupState? = null
    private var observation: Job? = null
    private var mutation: Job? = null
    init {
        if (session != null && !state.value.recoveryFailed) {
            try {
                session.save(state.value)
                listOf("editing", "original", "name", "selectionStart", "selectionEnd").forEach { saved.remove<Any?>(it) }
            } catch (_: Exception) {
                pendingRecoveryDraft = state.value
                mutable.value = state.value.copy(recoveryFailed = true, editing = false,
                    error = "Group draft could not be persisted; retry recovery")
            }
        }
        observe()
    }
    fun observe() {
        if (state.value.busy) return
        observation?.cancel()
        mutable.value = state.value.copy(loading = true,
            error = if (state.value.recoveryFailed || state.value.mutationUncertain) state.value.error else null)
        observation = viewModelScope.launch {
            try {
                repository.groups().collect { groups ->
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(groups = groups.distinct().toList(), loading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(loading = false, error = failure.localizedMessage)
            }
        }
    }
    fun retry() {
        if (state.value.busy) return
        if (state.value.recoveryFailed) {
            try {
                val recovered = pendingRecoveryDraft ?: session?.restore() ?: NamedGroupState()
                session?.save(recovered)
                pendingRecoveryDraft = null
                listOf("editing", "original", "name", "selectionStart", "selectionEnd").forEach { saved.remove<Any?>(it) }
                mutable.value = recovered.copy(groups = state.value.groups)
            } catch (_: Exception) {
                mutable.value = state.value.copy(error = "Group draft recovery failed; retry recovery. Closing preserves the draft file")
                return
            }
        }
        if (!state.value.editing && !state.value.recoveryFailed) {
            try { session?.save(state.value) }
            catch (_: Exception) {
                mutable.value = state.value.copy(error = "Group change is saved; draft cleanup failed. This completed write will not be repeated")
                return
            }
        }
        observe()
    }
    fun add() = open(null)
    fun edit(group: String) { if (group in state.value.groups) open(group) }
    private fun open(group: String?) {
        if (state.value.loading || state.value.busy || state.value.recoveryFailed || state.value.mutationUncertain) return
        updateDraft(state.value.copy(editing = true, original = group, name = group.orEmpty(),
            selectionStart = group.orEmpty().length, selectionEnd = group.orEmpty().length, error = null))
    }
    fun name(value: String) = input(value, value.length, value.length)
    fun input(value: String, start: Int, end: Int) {
        if (!state.value.editing || state.value.busy || state.value.recoveryFailed || state.value.mutationUncertain) return
        require(start in 0..value.length && end in 0..value.length)
        updateDraft(state.value.copy(name = value, selectionStart = start, selectionEnd = end))
    }
    private fun updateDraft(next: NamedGroupState) {
        // Persist before acknowledging input. A failed write leaves the previous native draft intact.
        if (session != null) session.save(next)
        else {
            saved["editing"] = next.editing; saved["original"] = next.original
            saved["name"] = next.name; saved["selectionStart"] = next.selectionStart; saved["selectionEnd"] = next.selectionEnd
        }
        mutable.value = next
    }
    fun cancelEdit() {
        if (state.value.busy || state.value.recoveryFailed) return
        updateDraft(state.value.copy(editing = false, original = null, name = "", selectionStart = 0,
            selectionEnd = 0, mutationUncertain = false, error = null))
    }
    fun clearSession() { if (!state.value.recoveryFailed) session?.clear() }
    fun confirm() {
        val current = state.value
        if (!current.editing || current.busy || current.loading || current.recoveryFailed || current.mutationUncertain) return
        if (current.original == null && current.name.isBlank()) { cancelEdit(); return }
        mutate {
            // If process death happens after Room commits but before file cleanup, restoration
            // reports an uncertain receipt instead of permitting an unguarded duplicate write.
            session?.save(current.copy(mutationUncertain = true))
            if (current.original == null) repository.add(current.name) else repository.rename(current.original, current.name)
            currentCoroutineContext().ensureActive()
            val completed = state.value.copy(busy = false, editing = false, original = null, name = "",
                selectionStart = 0, selectionEnd = 0, mutationUncertain = false)
            mutable.value = completed
            try { updateDraft(completed) }
            catch (_: Exception) {
                mutable.value = completed.copy(error = "Group change is saved; draft cleanup failed. This completed write will not be repeated")
            }
        }
    }
    fun delete(group: String) {
        if (group !in state.value.groups || state.value.loading || state.value.busy || state.value.recoveryFailed || state.value.mutationUncertain) return
        mutate { repository.rename(group, null) }
    }
    private fun mutate(action: suspend () -> Unit) {
        mutable.value = state.value.copy(busy = true, error = null)
        mutation = viewModelScope.launch {
            try { action(); currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                val failed = state.value.copy(error = failure.localizedMessage)
                mutable.value = failed
                // Room repository transactions roll back on failure. Persist the still editable
                // draft; if that persistence also fails, retain the uncertainty marker on disk.
                try { session?.save(failed) } catch (_: Exception) { }
            } finally {
                if (currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false)
            }
        }
    }
    fun stop() { observation?.cancel(); mutation?.cancel() }
    override fun onCleared() { stop() }
}
