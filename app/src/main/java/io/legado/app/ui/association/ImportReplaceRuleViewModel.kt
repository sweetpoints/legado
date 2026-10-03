package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ReplaceRuleImportItem
import io.legado.app.data.repository.ReplaceRuleImportRepository
import io.legado.app.data.repository.ReplaceRuleImportSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ImportReplaceRuleCode(val key: String, val json: String)
data class ImportReplaceRuleState(val items: List<ReplaceRuleImportItem> = emptyList(),
    val selected: Set<String> = emptySet(), val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val finished: Boolean = false, val code: ImportReplaceRuleCode? = null,
    val group: String = "", val addGroup: Boolean = false, val groupOpen: Boolean = false,
    val groupDraft: String = "", val addGroupDraft: Boolean = false, val groups: List<String> = emptyList()) {
    val isSelectAll: Boolean get() = items.all { it.key in selected }
    val selectCount: Int get() = selected.size
}

class ImportReplaceRuleViewModel(private val repository: ReplaceRuleImportRepository,
    private val saved: SavedStateHandle, private val source: String, private val preparedSession: String? = null) : ViewModel() {
    private val session = saved.get<String>("session") ?: (preparedSession?.also { require(UUID.fromString(it).toString() == it) } ?: UUID.randomUUID().toString()).also { saved["session"] = it }
    private val mutable = MutableStateFlow(ImportReplaceRuleState(finished = saved["finished"] ?: false,
        group = saved["group"] ?: "", addGroup = saved["addGroup"] ?: false,
        groupOpen = saved["groupOpen"] ?: false, groupDraft = saved["groupDraft"] ?: "",
        addGroupDraft = saved["addGroupDraft"] ?: false))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var groupsJob: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var released = false
    init {
        if (state.value.finished) mutable.value = state.value.copy(loading = false)
        else { load(); if (state.value.groupOpen) loadGroups() }
    }
    private fun failure(error: Exception) {
        if (!state.value.finished) mutable.value = state.value.copy(loading = false, busy = false,
            error = "ImportError:${error.localizedMessage}")
    }
    fun load() {
        if (state.value.finished || operation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (source.isEmpty() && preparedSession == null) { cancel(); return@launch }
                val restored = repository.restore(session)
                currentCoroutineContext().ensureActive()
                val staged = restored ?: if (preparedSession != null) error("Prepared import session is missing") else
                    ReplaceRuleImportSession(repository.read(source)).also { repository.stage(session, it.items) }
                currentCoroutineContext().ensureActive()
                if (state.value.finished) return@launch
                if (staged.committed) { finish(); return@launch }
                val keys = staged.items.mapTo(mutableSetOf()) { it.key }
                val selected = saved.get<ArrayList<String>>("selected")?.toSet()?.intersect(keys)
                    ?: staged.items.filter { it.selectedByDefault }.mapTo(mutableSetOf()) { it.key }
                saved["selected"] = ArrayList(selected)
                mutable.value = state.value.copy(items = staged.items, selected = selected, loading = false,
                    code = staged.items.find { it.key == saved.get<String>("codeKey") }?.let { ImportReplaceRuleCode(it.key, it.json) })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    fun toggle(key: String) {
        val value = state.value
        if (value.loading || value.busy || value.finished || value.items.none { it.key == key }) return
        select(if (key in value.selected) value.selected - key else value.selected + key)
    }
    private fun select(keys: Set<String>) { saved["selected"] = ArrayList(keys); mutable.value = state.value.copy(selected = keys) }
    fun toggleAll() {
        val value = state.value
        if (!value.loading && !value.busy && !value.finished)
            select(if (value.isSelectAll) emptySet() else value.items.mapTo(mutableSetOf()) { it.key })
    }
    fun openCode(key: String) {
        val value = state.value
        if (value.busy || value.finished) return
        value.items.find { it.key == key }?.let { saved["codeKey"] = key; mutable.value = value.copy(code = ImportReplaceRuleCode(key, it.json)) }
    }
    fun consumeCode(key: String) { if (state.value.code?.key == key) { saved.remove<String>("codeKey"); mutable.value = state.value.copy(code = null) } }
    fun edit(code: String, key: String?) {
        if (key == null || state.value.finished || state.value.busy || state.value.items.none { it.key == key }) return
        saved.remove<String>("codeKey")
        mutable.value = state.value.copy(busy = true, error = null, code = null)
        operation = viewModelScope.launch {
            try {
                val item = repository.edit(key, code)
                val items = state.value.items.map { if (it.key == key) item else it }
                repository.stage(session, items)
                if (!state.value.finished) mutable.value = state.value.copy(items = items, busy = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    fun openGroup() {
        if (state.value.busy || state.value.finished) return
        saved["groupOpen"] = true; saved["groupDraft"] = ""; saved["addGroupDraft"] = false
        mutable.value = state.value.copy(groupOpen = true, groupDraft = "", addGroupDraft = false)
        loadGroups()
    }
    private fun loadGroups() {
        groupsJob?.cancel()
        groupsJob = viewModelScope.launch {
            try {
                val groups = repository.groups()
                if (!state.value.finished && state.value.groupOpen) mutable.value = state.value.copy(groups = groups)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!state.value.finished) mutable.value = state.value.copy(error = "ImportError:${error.localizedMessage}") }
        }
    }
    fun groupDraft(value: String) {
        if (!state.value.groupOpen || state.value.finished) return
        saved["groupDraft"] = value; mutable.value = state.value.copy(groupDraft = value)
    }
    fun addGroupDraft(value: Boolean) {
        if (!state.value.groupOpen || state.value.finished) return
        saved["addGroupDraft"] = value; mutable.value = state.value.copy(addGroupDraft = value)
    }
    fun closeGroup() { saved["groupOpen"] = false; mutable.value = state.value.copy(groupOpen = false); groupsJob?.cancel() }
    fun acceptGroup() {
        if (!state.value.groupOpen || state.value.finished) return
        saved["group"] = state.value.groupDraft; saved["addGroup"] = state.value.addGroupDraft
        mutable.value = state.value.copy(group = state.value.groupDraft, addGroup = state.value.addGroupDraft)
        closeGroup()
    }
    fun confirm() {
        val value = state.value
        if (value.loading || value.busy || value.finished || value.groupOpen || value.error != null && value.items.isEmpty()) return
        mutable.value = value.copy(busy = true, error = null, code = null)
        operation = viewModelScope.launch {
            try { repository.insert(session, value.items, value.selected, value.group, value.addGroup); finish() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    private fun finish() { saved.remove<String>("codeKey"); saved["finished"] = true; mutable.value = state.value.copy(loading = false, busy = false, finished = true, code = null) }
    fun cancel() { if (!state.value.busy) { closeGroup(); finish(); operation?.cancel(); releasePrepared() } }
    private fun releasePrepared() {
        if (preparedSession == null || released) return
        released = true
        val job = operation
        cleanup.launch { job?.join(); runCatching { repository.release(session) } }
    }
    override fun onCleared() {
        operation?.cancel(); groupsJob?.cancel(); releasePrepared(); super.onCleared()
    }
}
