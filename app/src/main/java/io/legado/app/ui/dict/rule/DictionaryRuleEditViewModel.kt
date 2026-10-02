package io.legado.app.ui.dict.rule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.DictRule
import io.legado.app.data.repository.DictionaryRuleRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFIX = "dictionary.editor."
enum class DictionaryRuleField { Name, UrlRule, ShowRule }
data class DictionaryRuleEditUiState(val name: String = "", val urlRule: String = "", val showRule: String = "",
    val selections: List<Int> = listOf(0, 0, 0), val focused: DictionaryRuleField? = null,
    val loading: Boolean = true, val saving: Boolean = false, val finished: Boolean = false,
    val confirmExit: Boolean = false, val loadFailed: Boolean = false, val error: String? = null) {
    operator fun get(field: DictionaryRuleField) = when (field) {
        DictionaryRuleField.Name -> name; DictionaryRuleField.UrlRule -> urlRule; DictionaryRuleField.ShowRule -> showRule
    }
}
data class DictionaryFullEditRequest(val field: DictionaryRuleField, val text: String, val cursor: Int)

class DictionaryRuleEditViewModel(private val repository: DictionaryRuleRepository, private val saved: SavedStateHandle,
    private val originalName: String?, private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private var loadJob: Job? = null
    private var editRevision = 0
    private val restored = saved.get<Boolean>(PREFIX + "loaded") == true
    private var original = if (restored && saved.get<Boolean>(PREFIX + "hasOriginal") == true) DictionaryRuleSnapshot(
        saved[PREFIX + "baseName"] ?: "", saved[PREFIX + "baseUrl"] ?: "", saved[PREFIX + "baseShow"] ?: "",
        saved[PREFIX + "enabled"] ?: true, saved[PREFIX + "sort"] ?: 0) else null
    private val mutableState = MutableStateFlow(DictionaryRuleEditUiState(
        name = saved[PREFIX + "Name"] ?: "", urlRule = saved[PREFIX + "UrlRule"] ?: "", showRule = saved[PREFIX + "ShowRule"] ?: "",
        selections = DictionaryRuleField.entries.map { saved.get<Int>(PREFIX + "cursor." + it.name) ?: 0 },
        focused = saved.get<String>(PREFIX + "focused")?.let(DictionaryRuleField::valueOf),
        loading = !restored && originalName != null, finished = saved[PREFIX + "finished"] ?: false,
        confirmExit = saved[PREFIX + "confirmExit"] ?: false))
    val state = mutableState.asStateFlow()
    init { if (!restored && originalName != null) load() else if (!restored) markLoaded(null) }
    private fun markLoaded(rule: DictionaryRuleSnapshot?) {
        original = rule
        saved[PREFIX + "hasOriginal"] = rule != null
        saved[PREFIX + "baseName"] = rule?.name ?: ""
        saved[PREFIX + "baseUrl"] = rule?.urlRule ?: ""
        saved[PREFIX + "baseShow"] = rule?.showRule ?: ""
        saved[PREFIX + "enabled"] = rule?.enabled ?: true
        saved[PREFIX + "sort"] = rule?.sortNumber ?: 0
        saved[PREFIX + "loaded"] = true
    }
    fun load() {
        if (state.value.finished || state.value.saving || loadJob?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, loadFailed = false, error = null)
        loadJob = viewModelScope.launch {
            try {
                val rule = originalName?.let { repository.load(it) }
                coroutineContext.ensureActive()
                if (state.value.finished) return@launch
                markLoaded(rule)
                DictionaryRuleField.entries.forEach { field ->
                    if (!saved.contains(PREFIX + field.name)) {
                        val value = when (field) { DictionaryRuleField.Name -> rule?.name; DictionaryRuleField.UrlRule -> rule?.urlRule; DictionaryRuleField.ShowRule -> rule?.showRule } ?: ""
                        setInput(field, value, 0)
                    }
                }
                mutableState.value = state.value.copy(loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun setInput(field: DictionaryRuleField, value: String, cursor: Int) {
        if (state.value.finished || state.value.saving) return
        editRevision++
        saved[PREFIX + field.name] = value
        val position = cursor.coerceIn(0, value.length)
        saved[PREFIX + "cursor." + field.name] = position
        val selections = state.value.selections.mapIndexed { index, old -> if (index == field.ordinal) position else old }
        mutableState.value = when (field) {
            DictionaryRuleField.Name -> state.value.copy(name = value, selections = selections)
            DictionaryRuleField.UrlRule -> state.value.copy(urlRule = value, selections = selections)
            DictionaryRuleField.ShowRule -> state.value.copy(showRule = value, selections = selections)
        }
    }
    fun focus(field: DictionaryRuleField) {
        saved[PREFIX + "focused"] = field.name
        mutableState.value = state.value.copy(focused = field)
    }
    fun fullEditRequest(): DictionaryFullEditRequest? {
        if (state.value.finished || state.value.saving || saved.contains(PREFIX + "pendingField")) return null
        val field = state.value.focused ?: return null
        saved[PREFIX + "pendingField"] = field.name
        return DictionaryFullEditRequest(field, state.value[field], state.value.selections[field.ordinal])
    }
    fun fullEditResult(text: String?, cursor: Int?): Boolean {
        val field = saved.get<String>(PREFIX + "pendingField")?.let(DictionaryRuleField::valueOf) ?: return false
        saved.remove<String>(PREFIX + "pendingField")
        if (state.value.finished || state.value.saving) return true
        if (text != null) setInput(field, text, cursor ?: 0)
        focus(field)
        return true
    }
    fun fullEditCancelled() { saved.remove<String>(PREFIX + "pendingField") }
    fun currentRule(): DictionaryRuleSnapshot = (original ?: DictionaryRuleSnapshot()).copy(
        name = state.value.name, urlRule = state.value.urlRule, showRule = state.value.showRule)
    fun copyJson(): String = GSON.toJson(currentRule().entity())
    fun paste(text: String?) {
        if (state.value.saving || state.value.finished) return
        if (text.isNullOrBlank()) { mutableState.value = state.value.copy(error = "剪贴板没有内容"); return }
        val revision = editRevision
        viewModelScope.launch {
            try {
                val rule = withContext(parseDispatcher) { DictionaryRuleSnapshot.from(GSON.fromJsonObject<DictRule>(text).getOrThrow()) }
                coroutineContext.ensureActive()
                if (revision != editRevision || state.value.finished || state.value.saving) return@launch
                DictionaryRuleField.entries.forEach { field ->
                    setInput(field, when (field) { DictionaryRuleField.Name -> rule.name; DictionaryRuleField.UrlRule -> rule.urlRule; DictionaryRuleField.ShowRule -> rule.showRule }, 0)
                }
                mutableState.value = state.value.copy(error = null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (revision == editRevision && !state.value.finished && !state.value.saving)
                    mutableState.value = state.value.copy(error = "格式不对")
            }
        }
    }
    fun save() {
        if (state.value.loading || state.value.loadFailed || state.value.saving || state.value.finished) return
        val rule = currentRule()
        mutableState.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                repository.save(original?.name, rule)
                coroutineContext.ensureActive()
                markLoaded(rule)
                mutableState.value = state.value.copy(saving = false)
                finish()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(saving = false, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun requestClose() {
        if (state.value.saving || state.value.finished) return
        val rule = currentRule()
        val same = rule.name == (original?.name ?: "") && rule.urlRule == (original?.urlRule ?: "") && rule.showRule == (original?.showRule ?: "")
        if (same) finish() else {
            saved[PREFIX + "confirmExit"] = true; mutableState.value = state.value.copy(confirmExit = true)
        }
    }
    fun keepEditing() { saved[PREFIX + "confirmExit"] = false; mutableState.value = state.value.copy(confirmExit = false) }
    fun discard() { if (!state.value.saving) finish() }
    private fun finish() {
        fullEditCancelled()
        saved[PREFIX + "finished"] = true; saved[PREFIX + "confirmExit"] = false
        mutableState.value = state.value.copy(finished = true, confirmExit = false)
    }
}
