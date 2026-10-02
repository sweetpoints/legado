package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SharedLocalBookPreviewAction { Import, Directory }
data class SharedLocalBookPreviewEffect(val id: Long, val action: SharedLocalBookPreviewAction, val selection: List<String>)
data class SharedLocalBookPreviewState(val rows: List<SharedLocalBookPreviewRow> = emptyList(), val selected: Set<String> = emptySet(),
    val loaded: Boolean = false, val loading: Boolean = false, val importing: Boolean = false, val choosingDirectory: Boolean = false,
    val error: String? = null, val effect: SharedLocalBookPreviewEffect? = null, val finished: Boolean = false) {
    val busy get() = importing || choosingDirectory || effect != null
    val canAct get() = loaded && !loading && !busy && !finished
    val selectableCount get() = rows.count { it.selectable }
}
class SharedLocalBookPreviewViewModel(private val repository: SharedLocalBookPreviewRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(SharedLocalBookPreviewState(selected = saved.get<ArrayList<String>>("sharedPreview.selection").orEmpty().toSet(),
        effect = saved.get<String>("sharedPreview.action")?.let { action -> runCatching {
            SharedLocalBookPreviewEffect(saved.get<Long>("sharedPreview.effectId") ?: 1, SharedLocalBookPreviewAction.valueOf(action),
                saved.get<ArrayList<String>>("sharedPreview.effectSelection").orEmpty()) }.getOrNull() },
        finished = saved.get<Boolean>("sharedPreview.finished") == true))
    val state = mutable.asStateFlow()
    private var work: Job? = null; private var generation = 0L
    private var seeds: List<SharedLocalBookPreviewSeed> = emptyList()
    private var sourceSelection: Set<String> = emptySet()
    fun batch(seeds: List<SharedLocalBookPreviewSeed>, selectedUris: Collection<String>) {
        if (state.value.finished) return
        this.seeds = seeds.toList(); sourceSelection = selectedUris.map(FileSharedLocalBookPreviewRepository::id).toSet()
        load()
    }
    fun load() {
        if (state.value.finished) return
        val input = seeds; val signature = FileSharedLocalBookPreviewRepository.batch(input)
        val token = ++generation; work?.cancel(); mutable.value = state.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val rows = repository.project(input); currentCoroutineContext().ensureActive()
                if (token != generation || state.value.finished) return@launch
                val sameBatch = saved.get<String>("sharedPreview.batch") == signature
                val ids = rows.filter { it.selectable }.map { it.id }.toSet()
                val selection = (if (sameBatch) state.value.selected else sourceSelection).intersect(ids)
                if (!sameBatch) { clearEffect(); saved["sharedPreview.scrollIndex"] = 0; saved["sharedPreview.scrollOffset"] = 0 }
                saved["sharedPreview.batch"] = signature; saveSelection(selection)
                mutable.value = state.value.copy(rows = rows, selected = selection, loaded = true, loading = false)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                if (token == generation) mutable.value = state.value.copy(loading = false, error = error.localizedMessage ?: error.toString())
            }
        }
    }
    fun source(importing: Boolean, choosingDirectory: Boolean) {
        mutable.value = state.value.copy(importing = importing, choosingDirectory = choosingDirectory)
    }
    fun toggle(id: String) {
        if (!state.value.canAct || state.value.rows.none { it.id == id && it.selectable }) return
        val selected = state.value.selected.toMutableSet(); if (!selected.add(id)) selected.remove(id)
        selection(selected)
    }
    fun selectAll() {
        if (!state.value.canAct) return
        val ids = state.value.rows.filter { it.selectable }.map { it.id }.toSet()
        selection(if (state.value.selected.containsAll(ids)) emptySet() else ids)
    }
    private fun saveSelection(value: Set<String>) { saved["sharedPreview.selection"] = ArrayList(value) }
    private fun selection(value: Set<String>) { saveSelection(value); mutable.value = state.value.copy(selected = value) }
    fun confirm() { if (state.value.canAct && state.value.selected.isNotEmpty()) effect(SharedLocalBookPreviewAction.Import) }
    fun directory() { if (state.value.canAct) effect(SharedLocalBookPreviewAction.Directory) }
    private fun effect(action: SharedLocalBookPreviewAction) {
        val id = (saved.get<Long>("sharedPreview.nextEffect") ?: 0L) + 1
        val selection = state.value.rows.filter { it.id in state.value.selected }.map { it.id }
        saved["sharedPreview.nextEffect"] = id; saved["sharedPreview.effectId"] = id; saved["sharedPreview.action"] = action.name
        saved["sharedPreview.effectSelection"] = ArrayList(selection)
        mutable.value = state.value.copy(effect = SharedLocalBookPreviewEffect(id, action, selection))
    }
    fun consume(id: Long): SharedLocalBookPreviewEffect? {
        val effect = state.value.effect?.takeIf { it.id == id } ?: return null
        if (state.value.loading || !state.value.loaded || state.value.finished) return null
        clearEffect(); return effect
    }
    private fun clearEffect() {
        saved["sharedPreview.action"] = null; saved["sharedPreview.effectSelection"] = null
        mutable.value = state.value.copy(effect = null)
    }
    fun scroll(): Pair<Int, Int> = (saved.get<Int>("sharedPreview.scrollIndex") ?: 0) to (saved.get<Int>("sharedPreview.scrollOffset") ?: 0)
    fun scrolled(index: Int, offset: Int) {
        if (!state.value.loaded || state.value.loading) return
        saved["sharedPreview.scrollIndex"] = index.coerceAtLeast(0); saved["sharedPreview.scrollOffset"] = offset.coerceAtLeast(0)
    }
    fun cancel() {
        if (state.value.busy || state.value.finished) return
        work?.cancel(); saved["sharedPreview.finished"] = true; mutable.value = state.value.copy(finished = true, loading = false)
    }
    fun stop() { work?.cancel() }
    override fun onCleared() { stop() }
}
