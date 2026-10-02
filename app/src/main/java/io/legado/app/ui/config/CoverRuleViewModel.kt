package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.CoverRuleDraft
import io.legado.app.data.repository.CoverRuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoverRuleUiState(
    val draft: CoverRuleDraft = CoverRuleDraft(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val showValidation: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val isBusy get() = isLoading || isSaving
}

class CoverRuleViewModel(
    private val repository: CoverRuleRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val restored = savedState.get<Boolean>("cover.initialized") == true
    private val mutableState = MutableStateFlow(CoverRuleUiState(
        draft = if (restored) CoverRuleDraft(
            savedState["cover.enabled"] ?: true,
            savedState["cover.searchUrl"] ?: "",
            savedState["cover.rule"] ?: "",
        ) else CoverRuleDraft(),
        isLoading = !restored,
    ))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var edited = restored

    init { if (!restored) load() }

    fun load() {
        if (state.value.finished || state.value.isSaving || loadJob?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val draft = repository.load()
                coroutineContext.ensureActive()
                if (!edited) {
                    persist(draft)
                    mutableState.update { it.copy(draft = draft) }
                }
                mutableState.update { it.copy(isLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isLoading = false, error = error.localizedMessage ?: error.toString()) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }
    fun setSearchUrl(searchUrl: String) = edit { it.copy(searchUrl = searchUrl) }
    fun setCoverRule(coverRule: String) = edit { it.copy(coverRule = coverRule) }

    private fun edit(change: (CoverRuleDraft) -> CoverRuleDraft) {
        if (state.value.isSaving || state.value.finished) return
        edited = true
        val draft = change(state.value.draft)
        persist(draft)
        mutableState.update { it.copy(draft = draft, error = null) }
    }

    private fun persist(draft: CoverRuleDraft) {
        savedState["cover.enabled"] = draft.enabled
        savedState["cover.searchUrl"] = draft.searchUrl
        savedState["cover.rule"] = draft.coverRule
        savedState["cover.initialized"] = true
    }

    fun save() {
        val current = state.value
        if (current.isBusy || current.finished) return
        if (current.draft.searchUrl.isBlank() || current.draft.coverRule.isBlank()) {
            mutableState.update { it.copy(showValidation = true) }
            return
        }
        mutate { repository.save(current.draft) }
    }

    fun delete() {
        if (state.value.isBusy || state.value.finished) return
        mutate { repository.delete() }
    }

    private fun mutate(operation: suspend () -> Unit) {
        mutableState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                operation()
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isSaving = false, finished = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isSaving = false, error = error.localizedMessage ?: error.toString()) }
            }
        }
    }

    fun cancel() {
        if (state.value.isSaving) return
        loadJob?.cancel()
        mutableState.update { it.copy(isLoading = false, finished = true) }
    }
}
