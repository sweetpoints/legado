package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.TxtTocRuleManagementRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY = "txt.toc.management."
enum class TxtTocManagementEffectKind { ShareFile, ExportJson, ImportText, Clipboard, ReturnRegex }
data class TxtTocManagementEffect(val kind: TxtTocManagementEffectKind, val value: String)
data class TxtTocRuleManagementUiState(val rules: List<TxtTocRuleSnapshot> = emptyList(), val selected: Set<Long> = emptySet(),
    val query: String = "", val selectedId: Long? = null, val pickerFinished: Boolean = false,
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val deleteIds: List<Long> = emptyList(), val online: Boolean = false, val onlineInput: String = "", val history: List<String> = emptyList(),
    val exportUrl: String? = null, val exportSummary: String = "", val passphrase: String? = null, val effect: TxtTocManagementEffect? = null) {
    val visible: List<TxtTocRuleSnapshot> get() { val key = query.trim(); return if (key.isEmpty()) rules else rules.filter { it.name.contains(key, true) || it.example?.contains(key, true) == true } }
    val selection: List<TxtTocRuleSnapshot> get() = visible.filter { it.id in selected }
    val allSelected: Boolean get() = visible.isNotEmpty() && selection.size == visible.size
}

class TxtTocRuleManagementViewModel(private val repository: TxtTocRuleManagementRepository, private val saved: SavedStateHandle, private val picker: Boolean = false, private val initialRegex: String? = null) : ViewModel() {
    private var pickerSelectionInitialized = saved[KEY + "picker.initialized"] ?: false
    private var watchJob: Job? = null
    private var range: TxtTocRangeSelection? = null
    private var historyJob: Job? = null
    private var historyRevision = 0
    private var reorderCommitted = saved[KEY + "order.committed"] ?: false
    private var baselineOrder = saved.get<ArrayList<Long>>(KEY + "order.baseline")?.toList()
    private var previewOrder = if (reorderCommitted) saved.get<ArrayList<Long>>(KEY + "order")?.toList() else baselineOrder
    private var recoveringGesture = baselineOrder != null && !reorderCommitted
    private val mutableState = MutableStateFlow(TxtTocRuleManagementUiState(
        query = saved[KEY + "query"] ?: "", selectedId = saved[KEY + "picker.selected"], pickerFinished = saved[KEY + "picker.finished"] ?: false,
        selected = (saved.get<ArrayList<Long>>(KEY + "selection.baseline") ?: saved.get<ArrayList<Long>>(KEY + "selection"))?.toSet().orEmpty(),
        deleteIds = saved.get<ArrayList<Long>>(KEY + "delete")?.toList().orEmpty(), online = saved[KEY + "online"] ?: false, onlineInput = saved[KEY + "input"] ?: "",
        exportUrl = saved[KEY + "export"], exportSummary = saved[KEY + "summary"] ?: "", passphrase = saved[KEY + "passphrase"],
        effect = saved.get<String>(KEY + "effect.kind")?.let { TxtTocManagementEffect(TxtTocManagementEffectKind.valueOf(it), saved[KEY + "effect.value"] ?: "") }))
    val state = mutableState.asStateFlow()
    init {
        saved.remove<ArrayList<Long>>(KEY + "selection.baseline")
        saved[KEY + "selection"] = ArrayList(state.value.selected)
        if (recoveringGesture) clearSavedOrder()
        observe()
        if (reorderCommitted && previewOrder != null) finishReorder()
        if (state.value.online) loadHistory()
    }
    private fun clearSavedOrder() {
        saved.remove<ArrayList<Long>>(KEY + "order")
        saved.remove<ArrayList<Long>>(KEY + "order.baseline")
        saved.remove<Boolean>(KEY + "order.committed")
    }
    fun retry() { if (reorderCommitted && previewOrder != null) finishReorder() else observe() }
    fun observe() {
        watchJob?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        watchJob = viewModelScope.launch {
            try { repository.observe().collect { rows ->
                if (picker && !pickerSelectionInitialized) {
                    pickerSelectionInitialized = true; saved[KEY + "picker.initialized"] = true
                    val id = rows.lastOrNull { initialRegex != null && initialRegex == it.rule + io.legado.app.model.localBook.TextFile.spaceChars + it.replacement }?.id
                    saved[KEY + "picker.selected"] = id; mutableState.value = state.value.copy(selectedId = id)
                }
                val names = rows.map { it.id }.toSet()
                val selected = state.value.selected.intersect(names)
                saved[KEY + "selection"] = ArrayList(selected)
                val byName = rows.associateBy { it.id }
                val order = previewOrder
                val rules = if (order == null) rows else order.mapNotNull(byName::get) + rows.filterNot { it.id in order }
                mutableState.value = state.value.copy(rules = rules, selected = selected, loading = false)
                if (recoveringGesture) { recoveringGesture = false; previewOrder = null; baselineOrder = null }
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun toggle(id: Long) {
        if (state.value.busy) return
        val selected = state.value.selected.toMutableSet().apply { if (!add(id)) remove(id) }
        setSelection(selected)
    }
    private fun setSelection(selected: Set<Long>) {
        val available = state.value.rules.map { it.id }.toSet()
        val valid = selected.intersect(available)
        saved[KEY + "selection"] = ArrayList(valid)
        mutableState.value = state.value.copy(selected = valid)
    }
    fun selectAll() { if (!state.value.busy) setSelection(if (state.value.allSelected) emptySet() else state.value.visible.map { it.id }.toSet()) }
    fun invert() { if (!state.value.busy) setSelection(state.value.visible.map { it.id }.toSet() - state.value.selected) }
    fun beginSlide(id: Long) {
        if (state.value.busy) return
        val names = state.value.visible.map { it.id }; val anchor = names.indexOf(id)
        if (anchor >= 0) {
            saved[KEY + "selection.baseline"] = ArrayList(state.value.selected)
            range = TxtTocRangeSelection(names, state.value.selected, anchor); slideTo(id)
        }
    }
    fun slideTo(id: Long) { range?.let { val index = it.names.indexOf(id); if (index >= 0) setSelection(it.at(index)) } }
    fun endSlide() { range = null; saved.remove<ArrayList<Long>>(KEY + "selection.baseline") }
    fun cancelSlide() {
        range?.let { setSelection(it.initial) }
        endSlide()
    }
    fun move(from: Long, to: Long) {
        if (state.value.busy || state.value.query.isNotBlank() || from == to) return
        val rows = state.value.rules.toMutableList(); val start = rows.indexOfFirst { it.id == from }; val end = rows.indexOfFirst { it.id == to }
        if (start < 0 || end < 0) return
        if (baselineOrder == null) {
            baselineOrder = rows.map { it.id }
            saved[KEY + "order.baseline"] = ArrayList(checkNotNull(baselineOrder))
        }
        reorderCommitted = false
        saved[KEY + "order.committed"] = false
        rows.add(end, rows.removeAt(start)); previewOrder = rows.map { it.id }
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
            val byName = rows.associateBy { it.id }
            mutableState.value = state.value.copy(rules = order.mapNotNull(byName::get) + rows.filterNot { it.id in order })
        }
        previewOrder = null; baselineOrder = null; clearSavedOrder()
    }
    fun cancelGestures() { cancelSlide(); cancelReorder() }
    fun setEnabled(id: Long, enabled: Boolean) = operation { repository.setEnabled(listOf(id), enabled) }
    fun enableSelection(enabled: Boolean) { val names = state.value.selection.map { it.id }; if (names.isNotEmpty()) operation { repository.setEnabled(names, enabled) } }
    fun inputQuery(query: String) {
        if (query == state.value.query) return
        cancelGestures()
        saved[KEY + "query"] = query
        mutableState.value = state.value.copy(query = query)
        if (!picker) setSelection(emptySet())
    }
    fun choose(id: Long) {
        if (state.value.busy || state.value.pickerFinished || state.value.rules.none { it.id == id }) return
        pickerSelectionInitialized = true; saved[KEY + "picker.initialized"] = true
        saved[KEY + "picker.selected"] = id; mutableState.value = state.value.copy(selectedId = id)
    }
    fun confirmChoice() {
        if (state.value.pickerFinished) return
        val row = state.value.rules.firstOrNull { it.id == state.value.selectedId } ?: return
        emit(TxtTocManagementEffectKind.ReturnRegex, row.rule + io.legado.app.model.localBook.TextFile.spaceChars + row.replacement)
        saved[KEY + "picker.finished"] = true; mutableState.value = state.value.copy(pickerFinished = true)
    }
    fun requestDelete(ids: List<Long>) {
        val valid = ids.distinct().filter { id -> state.value.rules.any { it.id == id } }
        saved[KEY + "delete"] = ArrayList(valid); mutableState.value = state.value.copy(deleteIds = valid)
    }
    fun cancelDelete() { saved.remove<ArrayList<Long>>(KEY + "delete"); mutableState.value = state.value.copy(deleteIds = emptyList()) }
    fun confirmDelete() { val ids = state.value.deleteIds; cancelDelete(); if (ids.isNotEmpty()) operation { repository.delete(ids) } }
    fun deleteSelection() { requestDelete(state.value.selection.map { it.id }) }
    fun toEdge(id: Long, top: Boolean) = operation { repository.moveToEdge(listOf(id), top) }
    fun importDefault() = operation { repository.importDefault() }
    fun share() { val rules = state.value.selection; if (rules.isNotEmpty()) operation { emit(TxtTocManagementEffectKind.ShareFile, repository.shareFile(rules)) } }
    fun export() { val rules = state.value.selection; if (rules.isNotEmpty()) operation { emit(TxtTocManagementEffectKind.ExportJson, repository.json(rules)) } }
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
        operation { repository.rememberUrl(input); showOnline(false); emit(TxtTocManagementEffectKind.ImportText, input) }
    }
    fun exportFinished(url: String) {
        saved[KEY + "export"] = url; saved[KEY + "summary"] = ""; mutableState.value = state.value.copy(exportUrl = url, exportSummary = "")
        viewModelScope.launch { try {
            val summary = repository.exportSummary(url)
            if (state.value.exportUrl == url) { saved[KEY + "summary"] = summary; mutableState.value = state.value.copy(exportSummary = summary) }
        } catch (error: CancellationException) { throw error } catch (_: Exception) {} }
    }
    fun closeExport() { saved.remove<String>(KEY + "export"); mutableState.value = state.value.copy(exportUrl = null) }
    fun copyExport() { val url = state.value.exportUrl ?: return; emit(TxtTocManagementEffectKind.Clipboard, url); closeExport() }
    fun createPassphrase() { val url = state.value.exportUrl ?: return
        operation { val phrase = repository.passphrase(url); saved[KEY + "passphrase"] = phrase; closeExport(); mutableState.value = state.value.copy(passphrase = phrase) }
    }
    fun closePassphrase() { saved.remove<String>(KEY + "passphrase"); mutableState.value = state.value.copy(passphrase = null) }
    fun copyPassphrase() { val phrase = state.value.passphrase ?: return; emit(TxtTocManagementEffectKind.Clipboard, phrase); closePassphrase() }
    private fun emit(kind: TxtTocManagementEffectKind, value: String) {
        saved[KEY + "effect.kind"] = kind.name; saved[KEY + "effect.value"] = value
        mutableState.value = state.value.copy(effect = TxtTocManagementEffect(kind, value))
    }
    fun consumeEffect(): TxtTocManagementEffect? {
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
