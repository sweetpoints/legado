package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.HighlightGroupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class HighlightGroupStage { None, Rename, Delete, Move }
internal data class HighlightGroupState(val groups: List<String> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val stage: HighlightGroupStage = HighlightGroupStage.None,
    val source: String? = null, val name: String = "", val refresh: Boolean = false)
internal class HighlightGroupViewModel(private val repository: HighlightGroupRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(HighlightGroupState(
        stage = saved.get<String>("stage")?.let { runCatching { HighlightGroupStage.valueOf(it) }.getOrNull() } ?: HighlightGroupStage.None,
        source = saved["source"], name = saved.get<String>("name").orEmpty(), refresh = saved.get<Boolean>("refresh") == true))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var mutation: Job? = null
    private var stopped = false
    init { observe() }
    fun observe() {
        if (stopped || observation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try { repository.groups().collect { groups ->
                currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(groups = groups.distinct().toList(), loading = false)
            } } catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(loading = false, error = failure.localizedMessage.orEmpty()) }
        }
    }
    fun pauseObservation() { observation?.cancel(); observation = null }
    fun retry() { pauseObservation(); observe() }
    fun rename(source: String) = open(source, HighlightGroupStage.Rename)
    fun delete(source: String) = open(source, HighlightGroupStage.Delete)
    private fun open(source: String, stage: HighlightGroupStage) {
        if (stopped || state.value.loading || state.value.busy || source !in state.value.groups) return
        saved["source"] = source; saved["stage"] = stage.name; saved["name"] = source
        mutable.value = state.value.copy(source = source, stage = stage, name = source, error = null)
    }
    fun name(value: String) { if (stopped || state.value.busy || state.value.stage != HighlightGroupStage.Rename) return
        saved["name"] = value; mutable.value = state.value.copy(name = value) }
    fun cancel() { if (stopped || state.value.busy) return; clearDialog() }
    private fun clearDialog() {
        saved.remove<String>("source"); saved.remove<String>("stage"); saved.remove<String>("name")
        mutable.value = state.value.copy(stage = HighlightGroupStage.None, source = null, name = "")
    }
    fun confirmRename() {
        val current = state.value; val source = current.source ?: return
        if (current.stage != HighlightGroupStage.Rename) return
        val name = current.name.trim()
        if (name.isEmpty()) { cancel(); return }
        mutate(false) { repository.rename(source, name) }
    }
    fun confirmDelete() { val current = state.value; val source = current.source ?: return
        if (current.stage == HighlightGroupStage.Delete) mutate(true) { repository.delete(source) } }
    fun chooseMove() { if (stopped || state.value.busy || state.value.stage != HighlightGroupStage.Delete) return
        saved["stage"] = HighlightGroupStage.Move.name; mutable.value = state.value.copy(stage = HighlightGroupStage.Move) }
    fun move(target: String?) {
        val current = state.value; val source = current.source ?: return
        if (current.stage != HighlightGroupStage.Move || target == source || (target != null && target !in current.groups)) return
        mutate(true) { repository.move(source, target) }
    }
    private fun mutate(refresh: Boolean, action: suspend () -> Unit) {
        if (stopped || state.value.busy || state.value.loading) return
        mutable.value = state.value.copy(busy = true, error = null)
        mutation = viewModelScope.launch {
            try { action(); currentCoroutineContext().ensureActive()
                if (!stopped) { clearDialog(); if (refresh) { saved["refresh"] = true; mutable.value = state.value.copy(refresh = true) } }
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = failure.localizedMessage.orEmpty()) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun consumeRefresh() { if (stopped || !state.value.refresh) return; saved.remove<Boolean>("refresh"); mutable.value = state.value.copy(refresh = false) }
    fun stop() { stopped = true; pauseObservation(); mutation?.cancel() }
    override fun onCleared() { stop(); super.onCleared() }
}
