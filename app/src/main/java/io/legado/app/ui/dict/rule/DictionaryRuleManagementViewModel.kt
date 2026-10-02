package io.legado.app.ui.dict.rule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.DictionaryRuleManagementRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY = "dictionary.management."
enum class DictionaryManagementEffectKind { ShareFile, ExportJson, ImportText, Clipboard }
data class DictionaryManagementEffect(val kind: DictionaryManagementEffectKind, val value: String)
data class DictionaryRuleManagementUiState(val rules: List<DictionaryRuleSnapshot> = emptyList(), val selected: Set<String> = emptySet(),
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val deleteName: String? = null, val online: Boolean = false, val onlineInput: String = "", val history: List<String> = emptyList(),
    val exportUrl: String? = null, val exportSummary: String = "", val passphrase: String? = null, val effect: DictionaryManagementEffect? = null) {
    val selection: List<DictionaryRuleSnapshot> get() = rules.filter { it.name in selected }
    val allSelected: Boolean get() = rules.isNotEmpty() && selection.size == rules.size
}

class DictionaryRuleManagementViewModel(private val repository: DictionaryRuleManagementRepository, private val saved: SavedStateHandle) : ViewModel() {
    private var watchJob: Job? = null
    private var range: DictionaryRangeSelection? = null
    private var historyJob: Job? = null
    private var historyRevision = 0
    private var reorderCommitted = saved[KEY + "order.committed"] ?: false
    private var baselineOrder = saved.get<ArrayList<String>>(KEY + "order.baseline")?.toList()
    private var previewOrder = if (reorderCommitted) saved.get<ArrayList<String>>(KEY + "order")?.toList() else baselineOrder
    private var recoveringGesture = baselineOrder != null && !reorderCommitted
    private val mutableState = MutableStateFlow(DictionaryRuleManagementUiState(
        selected = (saved.get<ArrayList<String>>(KEY + "selection.baseline") ?: saved.get<ArrayList<String>>(KEY + "selection"))?.toSet().orEmpty(),
        deleteName = saved[KEY + "delete"], online = saved[KEY + "online"] ?: false, onlineInput = saved[KEY + "input"] ?: "",
        exportUrl = saved[KEY + "export"], exportSummary = saved[KEY + "summary"] ?: "", passphrase = saved[KEY + "passphrase"],
        effect = saved.get<String>(KEY + "effect.kind")?.let { DictionaryManagementEffect(DictionaryManagementEffectKind.valueOf(it), saved[KEY + "effect.value"] ?: "") }))
    val state = mutableState.asStateFlow()
    init {
        saved.remove<ArrayList<String>>(KEY + "selection.baseline")
        saved[KEY + "selection"] = ArrayList(state.value.selected)
        if (recoveringGesture) clearSavedOrder()
        observe()
        if (reorderCommitted && previewOrder != null) finishReorder()
        if (state.value.online) loadHistory()
    }
    private fun clearSavedOrder() {
        saved.remove<ArrayList<String>>(KEY + "order")
        saved.remove<ArrayList<String>>(KEY + "order.baseline")
        saved.remove<Boolean>(KEY + "order.committed")
    }
    fun retry() { if (reorderCommitted && previewOrder != null) finishReorder() else observe() }
    fun observe() {
        watchJob?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        watchJob = viewModelScope.launch {
            try { repository.observe().collect { rows ->
                val names = rows.map { it.name }.toSet()
                val selected = state.value.selected.intersect(names)
                saved[KEY + "selection"] = ArrayList(selected)
                val byName = rows.associateBy { it.name }
                val order = previewOrder
                val rules = if (order == null) rows else order.mapNotNull(byName::get) + rows.filterNot { it.name in order }
                mutableState.value = state.value.copy(rules = rules, selected = selected, loading = false)
                if (recoveringGesture) { recoveringGesture = false; previewOrder = null; baselineOrder = null }
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun toggle(name: String) {
        if (state.value.busy) return
        val selected = state.value.selected.toMutableSet().apply { if (!add(name)) remove(name) }
        setSelection(selected)
    }
    private fun setSelection(selected: Set<String>) {
        val available = state.value.rules.map { it.name }.toSet()
        val valid = selected.intersect(available)
        saved[KEY + "selection"] = ArrayList(valid)
        mutableState.value = state.value.copy(selected = valid)
    }
    fun selectAll() { if (!state.value.busy) setSelection(if (state.value.allSelected) emptySet() else state.value.rules.map { it.name }.toSet()) }
    fun invert() { if (!state.value.busy) setSelection(state.value.rules.map { it.name }.toSet() - state.value.selected) }
    fun beginSlide(name: String) {
        if (state.value.busy) return
        val names = state.value.rules.map { it.name }; val anchor = names.indexOf(name)
        if (anchor >= 0) {
            saved[KEY + "selection.baseline"] = ArrayList(state.value.selected)
            range = DictionaryRangeSelection(names, state.value.selected, anchor); slideTo(name)
        }
    }
    fun slideTo(name: String) { range?.let { val index = it.names.indexOf(name); if (index >= 0) setSelection(it.at(index)) } }
    fun endSlide() { range = null; saved.remove<ArrayList<String>>(KEY + "selection.baseline") }
    fun cancelSlide() {
        range?.let { setSelection(it.initial) }
        endSlide()
    }
    fun move(from: String, to: String) {
        if (state.value.busy || from == to) return
        val rows = state.value.rules.toMutableList(); val start = rows.indexOfFirst { it.name == from }; val end = rows.indexOfFirst { it.name == to }
        if (start < 0 || end < 0) return
        if (baselineOrder == null) {
            baselineOrder = rows.map { it.name }
            saved[KEY + "order.baseline"] = ArrayList(checkNotNull(baselineOrder))
        }
        reorderCommitted = false
        saved[KEY + "order.committed"] = false
        rows.add(end, rows.removeAt(start)); previewOrder = rows.map { it.name }
        saved[KEY + "order"] = ArrayList(checkNotNull(previewOrder))
        mutableState.value = state.value.copy(rules = rows)
    }
    fun finishReorder() {
        if (recoveringGesture) return
        val order = previewOrder ?: return
        reorderCommitted = true
        saved[KEY + "order.committed"] = true
        operation { repository.reorder(order); previewOrder = null; baselineOrder = null; reorderCommitted = false; clearSavedOrder() }
    }
    fun cancelReorder() {
        if (reorderCommitted) return
        baselineOrder?.let { order ->
            val rows = state.value.rules
            val byName = rows.associateBy { it.name }
            mutableState.value = state.value.copy(rules = order.mapNotNull(byName::get) + rows.filterNot { it.name in order })
        }
        previewOrder = null; baselineOrder = null; clearSavedOrder()
    }
    fun cancelGestures() { cancelSlide(); cancelReorder() }
    fun setEnabled(name: String, enabled: Boolean) = operation { repository.setEnabled(listOf(name), enabled) }
    fun enableSelection(enabled: Boolean) { val names = state.value.selection.map { it.name }; if (names.isNotEmpty()) operation { repository.setEnabled(names, enabled) } }
    fun requestDelete(name: String?) { saved[KEY + "delete"] = name; mutableState.value = state.value.copy(deleteName = name) }
    fun confirmDelete() { val name = state.value.deleteName ?: return; requestDelete(null); operation { repository.delete(listOf(name)) } }
    fun deleteSelection() { val names = state.value.selection.map { it.name }; if (names.isNotEmpty()) operation { repository.delete(names) } }
    fun importDefault() = operation { repository.importDefault() }
    fun share() { val rules = state.value.selection; if (rules.isNotEmpty()) operation { emit(DictionaryManagementEffectKind.ShareFile, repository.shareFile(rules)) } }
    fun export() { val rules = state.value.selection; if (rules.isNotEmpty()) operation { emit(DictionaryManagementEffectKind.ExportJson, repository.json(rules)) } }
    fun showOnline(show: Boolean) { saved[KEY + "online"] = show; mutableState.value = state.value.copy(online = show); if (show) loadHistory() }
    fun inputOnline(value: String) { saved[KEY + "input"] = value; mutableState.value = state.value.copy(onlineInput = value) }
    private fun loadHistory() {
        val revision = ++historyRevision
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            try { val history = repository.history(); if (revision == historyRevision) mutableState.value = state.value.copy(history = history) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {}
        }
    }
    fun deleteHistory(url: String) = operation {
        historyRevision++; historyJob?.cancel()
        repository.removeUrl(url); mutableState.value = state.value.copy(history = repository.history())
    }
    fun confirmOnline() {
        val input = state.value.onlineInput
        operation { repository.rememberUrl(input); showOnline(false); emit(DictionaryManagementEffectKind.ImportText, input) }
    }
    fun exportFinished(url: String) {
        saved[KEY + "export"] = url; saved[KEY + "summary"] = ""; mutableState.value = state.value.copy(exportUrl = url, exportSummary = "")
        viewModelScope.launch { try {
            val summary = repository.exportSummary(url)
            if (state.value.exportUrl == url) { saved[KEY + "summary"] = summary; mutableState.value = state.value.copy(exportSummary = summary) }
        } catch (error: CancellationException) { throw error } catch (_: Exception) {} }
    }
    fun closeExport() { saved.remove<String>(KEY + "export"); mutableState.value = state.value.copy(exportUrl = null) }
    fun copyExport() { val url = state.value.exportUrl ?: return; emit(DictionaryManagementEffectKind.Clipboard, url); closeExport() }
    fun createPassphrase() { val url = state.value.exportUrl ?: return
        operation { val phrase = repository.passphrase(url); saved[KEY + "passphrase"] = phrase; closeExport(); mutableState.value = state.value.copy(passphrase = phrase) }
    }
    fun closePassphrase() { saved.remove<String>(KEY + "passphrase"); mutableState.value = state.value.copy(passphrase = null) }
    fun copyPassphrase() { val phrase = state.value.passphrase ?: return; emit(DictionaryManagementEffectKind.Clipboard, phrase); closePassphrase() }
    private fun emit(kind: DictionaryManagementEffectKind, value: String) {
        saved[KEY + "effect.kind"] = kind.name; saved[KEY + "effect.value"] = value
        mutableState.value = state.value.copy(effect = DictionaryManagementEffect(kind, value))
    }
    fun consumeEffect(): DictionaryManagementEffect? {
        val effect = state.value.effect ?: return null
        saved.remove<String>(KEY + "effect.kind"); saved.remove<String>(KEY + "effect.value")
        mutableState.value = state.value.copy(effect = null)
        return effect
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(error = error.localizedMessage ?: error.toString()) }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
