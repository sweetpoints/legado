package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

private const val KEY = "autoTask.management."
enum class AutoTaskManagementAction { Edit, Debug, Login, ImportLocal, ImportDraft, Export, Help, Close, Clipboard }
data class AutoTaskManagementEffect(val id: Long, val action: AutoTaskManagementAction,
    val value: String? = null, val name: String? = null)
data class AutoTaskManagementState(val items: List<AutoTaskListItem> = emptyList(), val query: String = "",
    val selected: Set<String> = emptySet(), val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val deleteIds: List<String>? = null, val logId: String? = null,
    val cronIds: List<String>? = null, val cronDraft: String = "", val cronInvalid: Boolean = false,
    val online: Boolean = false, val onlineInput: String = "", val onlineLoading: Boolean = false,
    val history: List<String> = emptyList(), val exportNotice: AutoTaskExportNotice? = null,
    val effects: List<AutoTaskManagementEffect> = emptyList()) {
    val visible: List<AutoTaskListItem> get() = query.trim().let { query -> if (query.isEmpty()) items else items.filter { it.name.contains(query, true) } }
    val selection: List<AutoTaskListItem> get() = visible.filter { it.id in selected }
    val allSelected: Boolean get() = visible.isNotEmpty() && selection.size == visible.size
    val logItem: AutoTaskListItem? get() = items.firstOrNull { it.id == logId }
}
class AutoTaskManagementViewModel(private val repository: AutoTaskManagementRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val session = saved.get<String>(KEY + "session") ?: UUID.randomUUID().toString().also { saved[KEY + "session"] = it }
    private var revision = saved.get<Long>(KEY + "revision") ?: 0L
    private var sequence = saved.get<Long>(KEY + "sequence") ?: 0L
    private val mutable = MutableStateFlow(AutoTaskManagementState(
        query = saved[KEY + "query"] ?: "",
        selected = (saved.get<ArrayList<String>>(KEY + "slideBaseline") ?: saved.get<ArrayList<String>>(KEY + "selection")).orEmpty().toSet(),
        deleteIds = saved.get<ArrayList<String>>(KEY + "delete"), logId = saved[KEY + "log"],
        cronIds = saved.get<ArrayList<String>>(KEY + "cronIds"), cronDraft = saved[KEY + "cronDraft"] ?: "",
        online = saved[KEY + "online"] ?: false, onlineLoading = saved[KEY + "online"] ?: false,
        effects = saved.get<String>(KEY + "effects")?.let { GSON.fromJsonArray<AutoTaskManagementEffect>(it).getOrNull() }.orEmpty()))
    val state = mutable.asStateFlow()
    private var watch: Job? = null
    private var onlineJob: Job? = null
    private var rangeIds: List<String>? = null
    private var rangeInitial: Set<String> = emptySet()
    private var anchor = -1
    private var visitedStart = -1
    private var visitedEnd = -1
    private var command: Job? = null
    private var noticeJob: Job? = null
    private var noticeGeneration = 0
    private var stopped = false
    private val drafts = Channel<AutoTaskOnlineDraft>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (draft in drafts) try { repository.writeDraft(session, draft) } catch (error: Exception) { fail(error) }
    }
    init {
        saved.remove<ArrayList<String>>(KEY + "slideBaseline")
        saved[KEY + "selection"] = ArrayList(state.value.selected)
        observe()
        if (state.value.online) loadOnline(restore = true)
        saved.get<String>(KEY + "exportUrl")?.let(::exportReturned)
    }
    fun observe() {
        watch?.cancel(); mutable.value = state.value.copy(loading = true, error = null)
        watch = viewModelScope.launch {
            try { repository.observe().collect { rows ->
                val ids = rows.map { it.id }.toSet()
                if (rangeIds?.any { it !in ids } == true) cancelSlide()
                val selection = state.value.selected.intersect(ids)
                saved[KEY + "selection"] = ArrayList(selection)
                mutable.value = state.value.copy(items = rows, selected = selection, loading = false)
            } } catch (error: Exception) { fail(error); mutable.value = state.value.copy(loading = false) }
        }
    }
    fun query(value: String) {
        if (state.value.busy) return
        cancelSlide(); saved[KEY + "query"] = value; mutable.value = state.value.copy(query = value)
    }
    private fun selection(value: Set<String>) {
        val available = state.value.items.map { it.id }.toSet()
        val selected = value.intersect(available)
        saved[KEY + "selection"] = ArrayList(selected); mutable.value = state.value.copy(selected = selected)
    }
    fun select(id: String) { if (!state.value.busy) selection(if (id in state.value.selected) state.value.selected - id else state.value.selected + id) }
    fun selectAll() { if (!state.value.busy) selection(if (state.value.allSelected) state.value.selected - state.value.visible.map { it.id }.toSet() else state.value.selected + state.value.visible.map { it.id }) }
    fun invert() { if (!state.value.busy) selection(state.value.visible.map { it.id }.fold(state.value.selected) { ids, id -> if (id in ids) ids - id else ids + id }) }
    fun beginSlide(id: String) {
        if (state.value.busy) return
        val ids = state.value.visible.map { it.id }; val index = ids.indexOf(id)
        if (index < 0) return
        rangeInitial = state.value.selected; rangeIds = ids; anchor = index; visitedStart = index; visitedEnd = index
        saved[KEY + "slideBaseline"] = ArrayList(rangeInitial); slideTo(id)
    }
    fun slideTo(id: String) {
        val ids = rangeIds ?: return; val index = ids.indexOf(id); if (index < 0) return
        val first = minOf(index, anchor); val last = maxOf(index, anchor)
        visitedStart = minOf(visitedStart, first); visitedEnd = maxOf(visitedEnd, last)
        val originallySelected = ids[anchor] in rangeInitial
        selection(rangeInitial.toMutableSet().apply {
            for (position in visitedStart..visitedEnd) {
                val selected = if (position in first..last) !originallySelected else originallySelected
                if (selected) add(ids[position]) else remove(ids[position])
            }
        })
    }
    fun endSlide() { rangeIds = null; anchor = -1; saved.remove<ArrayList<String>>(KEY + "slideBaseline") }
    fun cancelSlide() { if (rangeIds != null) selection(rangeInitial); endSlide() }
    private fun effect(action: AutoTaskManagementAction, value: String? = null, name: String? = null) {
        if (stopped) return
        sequence++; saved[KEY + "sequence"] = sequence
        mutable.value = state.value.copy(effects = state.value.effects + AutoTaskManagementEffect(sequence, action, value, name))
        saved[KEY + "effects"] = GSON.toJson(state.value.effects)
    }
    fun consume(id: Long) { mutable.value = state.value.copy(effects = state.value.effects.filterNot { it.id == id }); saved[KEY + "effects"] = GSON.toJson(state.value.effects) }
    fun action(action: AutoTaskManagementAction, id: String? = null) {
        if (state.value.busy || action !in listOf(AutoTaskManagementAction.Edit, AutoTaskManagementAction.Debug,
                AutoTaskManagementAction.Login, AutoTaskManagementAction.ImportLocal, AutoTaskManagementAction.Help, AutoTaskManagementAction.Close)) return
        if (id == null && action in listOf(AutoTaskManagementAction.Debug, AutoTaskManagementAction.Login)) return
        if (action in listOf(AutoTaskManagementAction.Edit, AutoTaskManagementAction.Debug, AutoTaskManagementAction.Login) && id != null) {
            val row = state.value.items.firstOrNull { it.id == id } ?: return
            if (action == AutoTaskManagementAction.Login && !row.hasLogin) return
        }
        effect(action, id)
    }
    private fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        cancelSlide(); mutable.value = state.value.copy(busy = true, error = null)
        command = viewModelScope.launch {
            try { action() } catch (error: Exception) { fail(error) }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
    fun enabled(id: String, value: Boolean) { if (state.value.items.any { it.id == id }) operation { repository.enabled(listOf(id), value) } }
    fun selectedEnabled(value: Boolean) { val ids = state.value.selection.map { it.id }; if (ids.isNotEmpty()) operation { repository.enabled(ids, value) } }
    fun move(id: String, offset: Int) {
        val ids = state.value.visible.map { it.id }.toMutableList(); val index = ids.indexOf(id); val target = index + offset
        if (index < 0 || target !in ids.indices) return
        java.util.Collections.swap(ids, index, target); operation { repository.reorder(ids) }
    }
    fun askDelete(id: String? = null) {
        if (state.value.busy) return
        val ids = if (id == null) state.value.selection.map { it.id } else listOf(id).filter { target -> state.value.items.any { it.id == target } }
        if (ids.isEmpty()) return
        saved[KEY + "delete"] = ArrayList(ids); mutable.value = state.value.copy(deleteIds = ids)
    }
    fun closeDelete() { if (!state.value.busy) { saved[KEY + "delete"] = null; mutable.value = state.value.copy(deleteIds = null) } }
    fun delete() { val ids = state.value.deleteIds ?: return; operation { repository.delete(ids); saved[KEY + "delete"] = null; mutable.value = state.value.copy(deleteIds = null) } }
    fun showLog(id: String) { if (!state.value.busy && state.value.items.any { it.id == id }) { saved[KEY + "log"] = id; mutable.value = state.value.copy(logId = id) } }
    fun closeLog() { if (!state.value.busy) { saved[KEY + "log"] = null; mutable.value = state.value.copy(logId = null) } }
    fun clearLog() { val id = state.value.logId ?: return; operation { repository.clearLog(id); saved[KEY + "log"] = null; mutable.value = state.value.copy(logId = null) } }
    fun openCron() {
        if (state.value.busy) return
        val ids = state.value.selection.map { it.id }; if (ids.isEmpty()) return
        saved[KEY + "cronIds"] = ArrayList(ids); saved[KEY + "cronDraft"] = ""
        mutable.value = state.value.copy(cronIds = ids, cronDraft = "", cronInvalid = false)
    }
    fun cronDraft(value: String) { if (!state.value.busy) { saved[KEY + "cronDraft"] = value; mutable.value = state.value.copy(cronDraft = value, cronInvalid = false) } }
    fun closeCron() { if (!state.value.busy) { saved[KEY + "cronIds"] = null; mutable.value = state.value.copy(cronIds = null, cronInvalid = false) } }
    fun saveCron() {
        val ids = state.value.cronIds ?: return; val cron = state.value.cronDraft.trim()
        if (CronSchedule.parse(cron) == null) { mutable.value = state.value.copy(cronInvalid = true); return }
        operation { repository.cron(ids, cron); saved[KEY + "cronIds"] = null; mutable.value = state.value.copy(cronIds = null) }
    }
    fun openOnline() {
        if (state.value.busy || state.value.online) return
        saved[KEY + "online"] = true
        mutable.value = state.value.copy(online = true, onlineInput = "", onlineLoading = true, error = null)
        checkpoint(); loadOnline(restore = false)
    }
    private fun loadOnline(restore: Boolean) {
        onlineJob?.cancel(); val requestedRevision = revision
        onlineJob = viewModelScope.launch {
            try {
                val draft = if (restore) repository.readDraft(session) else null
                val history = repository.history()
                if (state.value.online) {
                    val text = if (requestedRevision == revision) draft?.text ?: state.value.onlineInput else state.value.onlineInput
                    revision = maxOf(revision, draft?.revision ?: 0); saved[KEY + "revision"] = revision
                    mutable.value = state.value.copy(onlineInput = text, history = history, onlineLoading = false)
                }
            } catch (error: Exception) { fail(error); mutable.value = state.value.copy(onlineLoading = false) }
        }
    }
    private fun checkpoint() { revision++; saved[KEY + "revision"] = revision; drafts.trySend(AutoTaskOnlineDraft(state.value.onlineInput, revision)) }
    fun onlineInput(value: String) { if (!state.value.busy) { mutable.value = state.value.copy(onlineInput = value); checkpoint() } }
    fun closeOnline() { if (!state.value.busy) { onlineJob?.cancel(); saved[KEY + "online"] = false; mutable.value = state.value.copy(online = false, onlineLoading = false) } }
    suspend fun flushDraft() { if (state.value.online) try { repository.writeDraft(session, AutoTaskOnlineDraft(state.value.onlineInput, revision)) } catch (error: Exception) { fail(error) } }
    fun importOnline() {
        if (!state.value.online || state.value.onlineLoading) return
        val input = state.value.onlineInput.trim()
        if (input.isEmpty()) { mutable.value = state.value.copy(error = "EmptyImport"); return }
        operation {
            repository.remember(input)
            repository.writeDraft(session, AutoTaskOnlineDraft(input, revision))
            saved[KEY + "online"] = false; mutable.value = state.value.copy(online = false)
            effect(AutoTaskManagementAction.ImportDraft, session)
        }
    }
    fun removeHistory(url: String) = operation { repository.removeHistory(url); mutable.value = state.value.copy(history = state.value.history.filterNot { it == url }) }
    suspend fun importText(session: String): String = repository.readDraft(session)?.text?.trim() ?: error("导入数据已丢失")
    fun export(selection: Boolean) {
        val ids = if (selection) state.value.selection.map { it.id } else null
        if (selection && ids.isNullOrEmpty()) return
        operation { val ticket = repository.export(ids); effect(AutoTaskManagementAction.Export, ticket.id, ticket.name) }
    }
    suspend fun exportText(effect: AutoTaskManagementEffect): String = repository.exportText(AutoTaskExportTicket(effect.value!!, effect.name!!))
    suspend fun releaseExport(effect: AutoTaskManagementEffect) = repository.releaseExport(AutoTaskExportTicket(effect.value!!, effect.name!!))
    fun exportReturned(url: String) {
        saved[KEY + "exportUrl"] = url
        val ticket = ++noticeGeneration; noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            try { val notice = repository.exportNotice(url)
                if (ticket == noticeGeneration) mutable.value = state.value.copy(exportNotice = notice)
            }
            catch (error: Exception) { fail(error) }
        }
    }
    fun closeExportNotice() { ++noticeGeneration; noticeJob?.cancel(); saved[KEY + "exportUrl"] = null; mutable.value = state.value.copy(exportNotice = null) }
    fun copyExport(passphrase: Boolean = false) {
        state.value.exportNotice?.let { notice -> (if (passphrase) notice.passphrase else notice.url)?.let { effect(AutoTaskManagementAction.Clipboard, it) } }
        if (!passphrase) closeExportNotice()
    }
    private fun fail(error: Exception) { if (error is CancellationException) throw error; mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") }
    internal fun stop() { stopped = true; watch?.cancel(); onlineJob?.cancel(); command?.cancel(); noticeJob?.cancel(); writer.cancel(); drafts.close() }
    override fun onCleared() { stop(); super.onCleared() }
}
