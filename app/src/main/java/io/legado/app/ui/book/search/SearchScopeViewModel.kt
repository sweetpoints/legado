package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.SearchScopeRepository
import io.legado.app.data.repository.SearchScopeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SearchScopeTab { Groups, Sources }
internal data class SearchScopeResult(val confirm: Boolean, val scope: String)
internal data class SearchScopeState(
    val tab: SearchScopeTab = SearchScopeTab.Groups,
    val groups: List<String> = emptyList(),
    val sources: List<SearchScopeSource> = emptyList(),
    val selectedGroups: List<String> = emptyList(),
    val selectedSource: SearchScopeSource? = null,
    val query: String = "",
    val searchExpanded: Boolean = false,
    val groupsLoading: Boolean = true,
    val sourcesLoading: Boolean = false,
    val groupsError: String? = null,
    val sourcesError: String? = null,
    val finished: Boolean = false,
    val result: SearchScopeResult? = null,
)
internal class SearchScopeViewModel(private val repository: SearchScopeRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(SearchScopeState(
        tab = if (saved.get<Boolean>("sources") == true) SearchScopeTab.Sources else SearchScopeTab.Groups,
        query = saved["query"] ?: "", searchExpanded = saved["expanded"] ?: false,
        selectedGroups = saved.get<ArrayList<String>>("groups")?.toList().orEmpty(),
        selectedSource = saved.get<String>("sourceUrl")?.let { SearchScopeSource(it, saved["sourceName"] ?: "") },
        finished = saved["finished"] ?: false,
        result = saved.get<Boolean>("pending")?.let { SearchScopeResult(it, saved["scope"] ?: "") },
    ))
    val state = mutable.asStateFlow()
    private var active = false
    private var sourceJob: Job? = null
    private var groupJob: Job? = null
    private var sourceGeneration = 0L
    private var groupGeneration = 0L
    init { if (!state.value.finished) loadGroups() }
    private fun update(value: SearchScopeState) {
        mutable.value = value
        saved["sources"] = value.tab == SearchScopeTab.Sources
        saved["query"] = value.query; saved["expanded"] = value.searchExpanded
        saved["groups"] = ArrayList(value.selectedGroups)
        saved["sourceUrl"] = value.selectedSource?.url; saved["sourceName"] = value.selectedSource?.name
        saved["finished"] = value.finished
        if (value.result == null) { saved.remove<Boolean>("pending"); saved.remove<String>("scope") }
        else { saved["pending"] = value.result.confirm; saved["scope"] = value.result.scope }
    }
    fun setActive(value: Boolean) { if (active != value) { active = value; observeSources() } }
    fun tab(value: SearchScopeTab) {
        if (state.value.finished || state.value.tab == value) return
        update(state.value.copy(tab = value)); observeSources()
    }
    fun query(value: String) {
        if (state.value.finished || state.value.query == value) return
        update(state.value.copy(query = value)); observeSources()
    }
    fun expandSearch() { if (!state.value.finished) update(state.value.copy(searchExpanded = !state.value.searchExpanded)) }
    fun group(value: String) {
        if (state.value.finished || value !in state.value.groups) return
        val selected = state.value.selectedGroups
        update(state.value.copy(selectedGroups = if (value in selected) selected - value else selected + value))
    }
    fun source(url: String) {
        if (state.value.finished) return
        state.value.sources.find { it.url == url }?.let { update(state.value.copy(selectedSource = it)) }
    }
    fun retry() { if (!state.value.finished) {
        if (state.value.tab == SearchScopeTab.Groups) loadGroups() else observeSources()
    } }
    private fun loadGroups() {
        val generation = ++groupGeneration
        groupJob?.cancel()
        update(state.value.copy(groupsLoading = true, groupsError = null))
        groupJob = viewModelScope.launch {
            try { val groups = repository.groups()
                if (generation == groupGeneration && !state.value.finished) update(state.value.copy(groups = groups, groupsLoading = false))
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { if (generation == groupGeneration && !state.value.finished) update(state.value.copy(groupsLoading = false, groupsError = error.localizedMessage.orEmpty())) }
        }
    }
    private fun observeSources() {
        val generation = ++sourceGeneration
        sourceJob?.cancel(); sourceJob = null
        if (!active || state.value.tab != SearchScopeTab.Sources || state.value.finished) {
            if (state.value.sourcesLoading) update(state.value.copy(sourcesLoading = false))
            return
        }
        val query = state.value.query
        update(state.value.copy(sourcesLoading = true, sourcesError = null))
        sourceJob = viewModelScope.launch {
            try { repository.sources(query).collect { rows ->
                if (generation == sourceGeneration && !state.value.finished) update(state.value.copy(sources = rows, sourcesLoading = false))
            } } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { if (generation == sourceGeneration && !state.value.finished)
                update(state.value.copy(sourcesLoading = false, sourcesError = error.localizedMessage.orEmpty())) }
        }
    }
    fun confirm(all: Boolean = false) {
        val value = state.value
        val scope = when {
            all -> ""
            value.tab == SearchScopeTab.Groups -> value.selectedGroups.joinToString(",")
            else -> value.selectedSource?.let { "${it.name.replace(":", "")}::${it.url}" }.orEmpty()
        }
        finish(SearchScopeResult(true, scope))
    }
    fun cancel() = finish(SearchScopeResult(false, ""))
    private fun finish(result: SearchScopeResult) {
        if (state.value.finished) return
        groupJob?.cancel(); sourceGeneration++; sourceJob?.cancel()
        update(state.value.copy(finished = true, groupsLoading = false, sourcesLoading = false, result = result))
    }
    fun consume(result: SearchScopeResult) { if (state.value.result == result) update(state.value.copy(result = null)) }
}
