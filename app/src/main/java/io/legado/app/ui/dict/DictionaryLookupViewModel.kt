package io.legado.app.ui.dict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.DictionaryImageData
import io.legado.app.data.repository.DictionaryLookupRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY = "dictionary.lookup."
data class DictionaryLookupUiState(val word: String = "", val rules: List<DictionaryRuleSnapshot> = emptyList(),
    val selected: String? = null, val loading: Boolean = true, val error: String? = null,
    val document: DictionaryResultDocument? = null, val invalidWord: Boolean = false)
class DictionaryLookupViewModel(private val repository: DictionaryLookupRepository, private val saved: SavedStateHandle,
    word: String?, private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private var queryJob: Job? = null
    private var rulesJob: Job? = null
    private var generation = 0L
    private var rulesGeneration = 0L
    private val mutableState = MutableStateFlow(DictionaryLookupUiState(
        word = saved[KEY + "word"] ?: word.orEmpty(), selected = saved[KEY + "selected"],
        invalidWord = (saved.get<String>(KEY + "word") ?: word).isNullOrEmpty()))
    val state = mutableState.asStateFlow()
    init { saved[KEY + "word"] = state.value.word; if (!state.value.invalidWord) loadRules() }
    fun loadRules() {
        rulesJob?.cancel(); queryJob?.cancel(); generation++
        val load = ++rulesGeneration
        mutableState.value = state.value.copy(loading = true, error = null)
        rulesJob = viewModelScope.launch {
            try {
                val rules = repository.rules()
                if (load != rulesGeneration) return@launch
                val selected = rules.firstOrNull { it.name == state.value.selected } ?: rules.firstOrNull()
                mutableState.value = state.value.copy(rules = rules, selected = selected?.name, loading = false)
                if (selected != null) query(selected, restore = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (load == rulesGeneration) mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun select(name: String) {
        if (name == state.value.selected) return
        state.value.rules.firstOrNull { it.name == name }?.let { query(it, restore = false) }
    }
    fun retry() { state.value.rules.firstOrNull { it.name == state.value.selected }?.let { query(it, restore = false) } ?: loadRules() }
    private fun query(rule: DictionaryRuleSnapshot, restore: Boolean) {
        queryJob?.cancel()
        val request = ++generation
        saved[KEY + "selected"] = rule.name
        mutableState.value = state.value.copy(selected = rule.name, loading = true, error = null, document = null)
        queryJob = viewModelScope.launch {
            try {
                val matches = restore && saved.get<String>(KEY + "result.name") == rule.name &&
                    saved.get<String>(KEY + "result.url") == rule.urlRule && saved.get<String>(KEY + "result.show") == rule.showRule
                val cached = if (matches) saved.get<String>(KEY + "result.content") else null
                val content = cached ?: repository.search(rule, state.value.word)
                val document = withContext(parsingDispatcher) { dictionaryResultDocument(content) }
                if (request != generation) return@launch
                saved[KEY + "result.name"] = rule.name; saved[KEY + "result.url"] = rule.urlRule; saved[KEY + "result.show"] = rule.showRule
                // Avoid putting arbitrarily large server responses into an Android saved-state Bundle.
                if (content.length <= 65_536) saved[KEY + "result.content"] = content else saved.remove<String>(KEY + "result.content")
                mutableState.value = state.value.copy(loading = false, error = null, document = document)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == generation) mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR", document = null)
            }
        }
    }
    fun action(url: String): Boolean {
        val action = state.value.document?.action(url) ?: return false
        if (action.script.isBlank()) return true
        val rule = state.value.rules.firstOrNull { it.name == state.value.selected } ?: return false
        viewModelScope.launch { repository.click(rule, action.name, action.script) }
        return true
    }
    suspend fun image(source: String): DictionaryImageData = repository.image(source)
}
