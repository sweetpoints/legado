package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class BookImportAction { Code, ReplaceRules, Effective, Manual, SyncCode, Toast }
internal data class BookImportEffect(val id: Long, val action: BookImportAction, val key: String? = null,
    val ids: List<Long> = emptyList(), val text: String = "")
internal data class BookImportSearchLabels(val enabled: String, val disabled: String, val login: String, val noGroup: String,
    val enabledExplore: String, val disabledExplore: String)
internal data class BookImportUiState(val items: List<BookImportEntry> = emptyList(), val selected: Set<String> = emptySet(),
    val expanded: Set<String> = emptySet(), val query: String = "", val loading: Boolean = true,
    val busy: Boolean = false, val pendingRefresh: Boolean = false, val finished: Boolean = false,
    val error: String? = null, val preferences: BookImportPreferences = BookImportPreferences(),
    val automatic: Boolean = false, val manualIds: Map<String, List<Long>> = emptyMap(),
    val group: String? = null, val addGroup: Boolean = false, val groupOpen: Boolean = false,
    val groupDraft: String = "", val addGroupDraft: Boolean = false, val groups: List<String> = emptyList(),
    val effects: List<BookImportEffect> = emptyList()) {
    val useReplacement: Boolean get() = automatic || manualIds.isNotEmpty()
    val interactive: Boolean get() = items.isNotEmpty() && !loading && !busy && !pendingRefresh && !finished
    val selectCount: Int get() = selected.size
    val isSelectAll: Boolean get() = items.all { !it.canImport || it.key in selected }
}
internal fun visibleBookImportItems(state: BookImportUiState, labels: BookImportSearchLabels): List<BookImportEntry> =
    state.items.filter { source -> when (state.query) {
        labels.enabled -> source.enabled
        labels.disabled -> !source.enabled
        labels.login -> source.needsLogin
        labels.enabledExplore -> source.enabledExplore
        labels.disabledExplore -> !source.enabledExplore
        labels.noGroup -> source.sourceGroup.isNullOrBlank() || source.sourceGroup?.trim() == "未分组"
        else -> matchesSourceImportSearch(state.query, source.sourceName, source.sourceUrl, source.sourceGroup, source.sourceComment)
    } }

internal class BookImportViewModel(private val repository: BookImportRepository,
    private val requests: BookImportRequestRepository, private val saved: SavedStateHandle,
    private val source: String, private val labels: BookImportSearchLabels,
    private val reimportSourceUrl: String? = null) : ViewModel() {
    private val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(BookImportUiState(query = saved["query"] ?: "",
        finished = saved["finished"] ?: false, expanded = saved.get<ArrayList<String>>("expanded")?.toSet() ?: emptySet(),
        groupOpen = saved["groupOpen"] ?: false, groupDraft = saved["groupDraft"] ?: "",
        addGroupDraft = saved["addGroupDraft"] ?: false,
        effects = saved.get<String>("effects")?.let { GSON.fromJsonArray<BookImportEffect>(it).getOrNull() }.orEmpty()))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var groupJob: Job? = null
    private var queueJob: Job? = null
    private var queueTicket = 0L
    private var pendingRequest: Pair<String, BookImportRefreshRequest>? = null
    private var effectId = saved.get<Long>("effectId") ?: 0L
    private var manualSelections = mutableMapOf<String, Boolean>()
    init {
        saved.get<ArrayList<String>>("manualYes")?.forEach { manualSelections[it] = true }
        saved.get<ArrayList<String>>("manualNo")?.forEach { manualSelections[it] = false }
        if (state.value.finished) mutable.value = state.value.copy(loading = false)
        else load()
    }
    private fun snapshot(value: BookImportUiState = state.value) = BookImportSnapshot(value.items, value.automatic, value.manualIds)
    private fun fail(error: Exception) {
        if (!state.value.finished) mutable.value = state.value.copy(busy = false, loading = false,
            error = "ImportError:${error.localizedMessage}")
    }
    fun load() {
        if (state.value.finished || operation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (source.isEmpty()) { finish(); return@launch }
                val prefs = repository.preferences()
                val cached = repository.restore(session) ?: run {
                    val originals = repository.load(source)
                    BookImportSnapshot(repository.refresh(originals, prefs.automaticReplacement, emptyMap()),
                        prefs.automaticReplacement, emptyMap()).also { repository.stage(session, it) }
                }
                if (state.value.finished) return@launch
                if (cached.committed) {
                    if (cached.items.any { it.sourceUrl == reimportSourceUrl && it.canImport && (manualSelections[it.key] ?: (it.selectedByDefault || it.sourceUrl == reimportSourceUrl)) }) saved["readerPending"] = true
                    finish(); return@launch
                }
                mutable.value = state.value.copy(preferences = prefs, automatic = cached.automatic, manualIds = cached.manualIds,
                    group = saved.get<String>("group") ?: prefs.lastGroup.takeIf { prefs.rememberGroup },
                    addGroup = saved.get<Boolean>("addGroup") ?: (prefs.rememberGroup && prefs.lastGroupAdd))
                applyEntries(cached.items)
                saved.get<String>("pendingRequest")?.let { id ->
                    val request = requests.read(id)
                    if (request == null) saved.remove<String>("pendingRequest")
                    else pendingRequest = id to request
                }
                mutable.value = state.value.copy(loading = false, pendingRefresh = pendingRequest != null || queueJob?.isActive == true)
                if (state.value.groupOpen) loadGroups()
                drainRequest()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
        }
    }
    private fun applyEntries(items: List<BookImportEntry>) {
        val selected = items.filter { it.canImport && (manualSelections[it.key] ?: (it.selectedByDefault || it.sourceUrl == reimportSourceUrl)) }.mapTo(mutableSetOf()) { it.key }
        mutable.value = state.value.copy(items = items, selected = selected, expanded = state.value.expanded.intersect(items.map { it.key }.toSet()))
    }
    fun search(value: String) { saved["query"] = value; mutable.value = state.value.copy(query = value) }
    fun select(key: String, selected: Boolean) {
        if (!state.value.interactive || state.value.items.none { it.key == key && it.canImport }) return
        manualSelections[key] = selected; saveSelections()
        mutable.value = state.value.copy(selected = if (selected) state.value.selected + key else state.value.selected - key)
    }
    fun toggle(key: String) = select(key, key !in state.value.selected)
    private fun saveSelections() {
        saved["manualYes"] = ArrayList(manualSelections.filterValues { it }.keys)
        saved["manualNo"] = ArrayList(manualSelections.filterValues { !it }.keys)
    }
    fun selectVisible() {
        if (!state.value.interactive) return
        val visible = visibleBookImportItems(state.value, labels)
        val selected = !visible.all { !it.canImport || it.key in state.value.selected }
        visible.forEach { select(it.key, selected) }
    }
    fun selectStatus(status: BookImportStatus) {
        if (!state.value.interactive) return
        val visible = visibleBookImportItems(state.value, labels).filter { it.status == status }
        val shouldSelect = !visible.all { !it.canImport || it.key in state.value.selected }
        visible.forEach { select(it.key, shouldSelect) }
    }
    fun expand(key: String) {
        if (!state.value.interactive || state.value.items.none { it.key == key }) return
        val expanded = if (key in state.value.expanded) state.value.expanded - key else state.value.expanded + key
        saved["expanded"] = ArrayList(expanded); mutable.value = state.value.copy(expanded = expanded)
    }
    fun consume(id: Long) {
        mutable.value = state.value.copy(effects = state.value.effects.filterNot { it.id == id })
        saveEffects()
    }
    private fun saveEffects() { saved["effects"] = GSON.toJson(state.value.effects) }
    private fun effect(action: BookImportAction, key: String? = null, ids: List<Long> = emptyList(), text: String = "") {
        if (state.value.finished && action != BookImportAction.Toast) return
        effectId++; saved["effectId"] = effectId
        mutable.value = state.value.copy(effects = state.value.effects + BookImportEffect(effectId, action, key, ids.toList(), text))
        saveEffects()
    }
    fun code(key: String) { if (state.value.interactive && state.value.items.any { it.key == key }) effect(BookImportAction.Code, key) }
    fun replaceRules() { if (state.value.interactive) effect(BookImportAction.ReplaceRules) }
    fun effective(key: String? = null) {
        if (state.value.interactive) effect(BookImportAction.Effective, key, effectiveIds(key))
    }
    fun manual(key: String? = null) {
        if (state.value.interactive && !state.value.automatic) effect(BookImportAction.Manual, key, selectedManualIds(key))
    }
    fun effectiveIds(key: String? = null): List<Long> = if (!state.value.useReplacement) emptyList() else
        state.value.items.filter { key == null || it.key == key }.flatMap { it.effectiveRuleIds }.distinct()
    fun selectedManualIds(key: String? = null): List<Long> = if (key != null) state.value.manualIds[key].orEmpty() else
        state.value.items.map { state.value.manualIds[it.key].orEmpty().toSet() }.reduceOrNull { all, ids -> all.intersect(ids) }?.toList().orEmpty()
    fun alternate(key: String?): String? = state.value.items.find { it.key == key }?.replacedJson
    fun refresh(key: String? = null, code: String? = null, ids: List<Long>? = null,
        openManual: Boolean? = null, automatic: Boolean? = null) {
        if (state.value.finished) return
        val ticket = ++queueTicket
        mutable.value = state.value.copy(pendingRefresh = true)
        queueJob = viewModelScope.launch {
            try {
                val request = BookImportRefreshRequest(key, code, ids?.toList(), openManual, automatic)
                val id = requests.write(request)
                if (state.value.finished || ticket != queueTicket) { requests.remove(id); return@launch }
                saved.get<String>("pendingRequest")?.let { requests.remove(it) }
                saved["pendingRequest"] = id; pendingRequest = id to request
                queueJob = null
                drainRequest()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (ticket == queueTicket && !state.value.finished) {
                    queueJob = null
                    mutable.value = state.value.copy(pendingRefresh = pendingRequest != null,
                        error = "ImportError:${error.localizedMessage}")
                    drainRequest()
                }
            }
        }
    }
    private fun drainRequest() {
        if (state.value.loading || state.value.busy || state.value.finished) return
        val (id, request) = pendingRequest ?: run {
            if (queueJob?.isActive != true) mutable.value = state.value.copy(pendingRefresh = false)
            return
        }
        pendingRequest = null
        val previous = state.value
        val automatic = request.automatic ?: previous.automatic
        if (request.key != null && previous.items.none { it.key == request.key } ||
            automatic && (request.ids != null || request.openManual == true)) {
            clearRequest(id); return
        }
        mutable.value = previous.copy(busy = true, pendingRefresh = true, error = null)
        operation = viewModelScope.launch {
            var completed = false
            try {
                var originals = previous.items.map { BookImportOriginal(it.key, it.originalJson) }
                var invalidDraft = false
                if (request.key != null && request.code != null) {
                    val parsed = runCatching { repository.parseEdited(request.key, request.code) }
                    parsed.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    if (parsed.isSuccess) originals = originals.map { if (it.key == request.key) parsed.getOrThrow() else it }
                    else invalidDraft = true
                }
                if (invalidDraft && (request.openManual != null || request.ids != null)) {
                    effect(BookImportAction.Toast, text = "格式不对")
                } else {
                    val manual = previous.manualIds.toMutableMap()
                    request.ids?.let { ids ->
                        if (request.key == null) originals.forEach { manual[it.key] = ids } else manual[request.key] = ids
                    }
                    val entries = repository.refresh(originals, automatic, manual)
                    val next = BookImportSnapshot(entries, automatic, manual.toMap())
                    repository.stage(session, next)
                    val prefs = previous.preferences.copy(automaticReplacement = automatic)
                    try { repository.preferences(prefs) }
                    catch (error: Exception) { repository.stage(session, snapshot(previous)); throw error }
                    if (state.value.finished) return@launch
                    mutable.value = state.value.copy(automatic = automatic, manualIds = manual.toMap(), preferences = prefs)
                    applyEntries(entries)
                    if (request.openManual != null) effect(if (request.openManual) BookImportAction.Manual else BookImportAction.Effective,
                        request.key, if (request.openManual) selectedManualIds(request.key) else effectiveIds(request.key))
                }
                effect(BookImportAction.SyncCode, text = if (invalidDraft) "clear" else "")
                completed = true
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error); effect(BookImportAction.SyncCode); completed = true }
            finally {
                // Cancellation during recreation keeps the durable request for the next model.
                if (completed) {
                    if (saved.get<String>("pendingRequest") == id) saved.remove<String>("pendingRequest")
                    withContext(NonCancellable) { requests.remove(id) }
                    if (!state.value.finished) mutable.value = state.value.copy(busy = false,
                        pendingRefresh = pendingRequest != null || queueJob?.isActive == true)
                    drainRequest()
                }
            }
        }
    }
    private fun clearRequest(id: String) {
        if (saved.get<String>("pendingRequest") == id) saved.remove<String>("pendingRequest")
        mutable.value = state.value.copy(pendingRefresh = false)
        viewModelScope.launch { requests.remove(id) }
    }
    fun preferences(value: BookImportPreferences) {
        if (!state.value.interactive) return
        val previous = state.value
        mutable.value = previous.copy(busy = true)
        operation = viewModelScope.launch {
            try {
                repository.preferences(value)
                if (!state.value.finished) {
                    val reset = previous.preferences.rememberGroup && !value.rememberGroup
                    if (reset) { saved["group"] = ""; saved["addGroup"] = false }
                    mutable.value = state.value.copy(preferences = value, busy = false,
                        group = if (reset) null else state.value.group, addGroup = if (reset) false else state.value.addGroup)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            finally { if (!state.value.finished && !state.value.busy) drainRequest() }
        }
    }
    fun openGroup() {
        if (!state.value.interactive) return
        saved["groupOpen"] = true; saved["groupDraft"] = state.value.group.orEmpty(); saved["addGroupDraft"] = state.value.addGroup
        mutable.value = state.value.copy(groupOpen = true, groupDraft = state.value.group.orEmpty(), addGroupDraft = state.value.addGroup)
        loadGroups()
    }
    private fun loadGroups() {
        groupJob?.cancel(); groupJob = viewModelScope.launch {
            try { val groups = repository.groups(); if (!state.value.finished && state.value.groupOpen) mutable.value = state.value.copy(groups = groups) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!state.value.finished) mutable.value = state.value.copy(error = "ImportError:${error.localizedMessage}") }
        }
    }
    fun groupDraft(value: String) { if (!state.value.groupOpen || state.value.finished) return; saved["groupDraft"] = value; mutable.value = state.value.copy(groupDraft = value) }
    fun addGroupDraft(value: Boolean) { if (!state.value.groupOpen || state.value.finished) return; saved["addGroupDraft"] = value; mutable.value = state.value.copy(addGroupDraft = value) }
    fun closeGroup() { saved["groupOpen"] = false; mutable.value = state.value.copy(groupOpen = false); groupJob?.cancel() }
    fun acceptGroup() {
        if (!state.value.interactive || !state.value.groupOpen) return
        saved["group"] = state.value.groupDraft; saved["addGroup"] = state.value.addGroupDraft
        mutable.value = state.value.copy(group = state.value.groupDraft, addGroup = state.value.addGroupDraft)
        closeGroup()
        if (state.value.preferences.rememberGroup) preferences(state.value.preferences.copy(lastGroup = state.value.group, lastGroupAdd = state.value.addGroup))
    }
    fun confirm() {
        if (!state.value.interactive || state.value.groupOpen) return
        val value = state.value; mutable.value = value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                repository.insert(session, snapshot(value), value.selected, value.preferences, value.group, value.addGroup)
                if (value.items.any { it.sourceUrl == reimportSourceUrl && it.canImport && it.key in value.selected }) saved["readerPending"] = true
                finish()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            finally { if (!state.value.finished && !state.value.busy) drainRequest() }
        }
    }
    /** Reader globals belong to the Route/host. Only this durable flag is saved. */
    suspend fun consumeReaderSource(): String? {
        if (saved.get<Boolean>("readerPending") != true) return null
        val json = reimportSourceUrl?.let { repository.source(it) }
        saved["readerPending"] = false
        return json
    }
    private fun finish() {
        saved["finished"] = true; saved["effects"] = "[]"
        mutable.value = state.value.copy(finished = true, loading = false, busy = false, pendingRefresh = false, effects = emptyList())
    }
    fun cancel() {
        if (state.value.busy || state.value.pendingRefresh) return
        finish(); operation?.cancel(); groupJob?.cancel(); queueJob?.cancel()
    }
}
