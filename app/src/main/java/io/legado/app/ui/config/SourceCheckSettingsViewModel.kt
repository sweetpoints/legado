package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SourceCheckOption { Comment, Domain, Search, Discovery, Info, Category, Content }
enum class SourceCheckTimeoutIssue { Empty, Invalid }
data class SourceCheckSettingsState(val loading: Boolean = true, val settings: SourceCheckSettings? = null,
    val seconds: String = "", val infoEnabled: Boolean = true, val categoryEnabled: Boolean = true,
    val contentEnabled: Boolean = true, val saving: Boolean = false, val finished: Boolean = false,
    val timeoutIssue: SourceCheckTimeoutIssue? = null, val error: String? = null)
class SourceCheckSettingsViewModel(private val repository: SourceCheckSettingsRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(SourceCheckSettingsState()); val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    init { load() }
    fun load() {
        if (loadJob?.isActive == true || state.value.saving) return
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val loaded = repository.load(); currentCoroutineContext().ensureActive()
                val restored = if (saved.get<Boolean>("draft") == true) loaded.copy(
                    comment = saved["Comment"] ?: loaded.comment, domain = saved["Domain"] ?: loaded.domain,
                    search = saved["Search"] ?: loaded.search, discovery = saved["Discovery"] ?: loaded.discovery,
                    info = saved["Info"] ?: loaded.info, category = saved["Category"] ?: loaded.category,
                    content = saved["Content"] ?: loaded.content) else loaded
                mutable.value = SourceCheckSettingsState(loading = false, settings = restored,
                    seconds = saved["seconds"] ?: (loaded.timeout / 1000).toString(),
                    infoEnabled = saved["infoEnabled"] ?: true,
                    categoryEnabled = saved["categoryEnabled"] ?: restored.info,
                    contentEnabled = saved["contentEnabled"] ?: restored.category,
                    finished = saved.get<Boolean>("finished") == true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(loading = false, error = error.localizedMessage ?: "Error") }
        }
    }
    private fun remember() {
        val value = state.value; val settings = value.settings ?: return
        saved["draft"] = true; saved["seconds"] = value.seconds
        saved["Comment"] = settings.comment; saved["Domain"] = settings.domain; saved["Search"] = settings.search
        saved["Discovery"] = settings.discovery; saved["Info"] = settings.info; saved["Category"] = settings.category; saved["Content"] = settings.content
        saved["infoEnabled"] = value.infoEnabled; saved["categoryEnabled"] = value.categoryEnabled; saved["contentEnabled"] = value.contentEnabled
    }
    fun seconds(text: String) {
        if (state.value.saving || state.value.finished || state.value.settings == null) return
        if (text.length > 19 || text.any { it !in '0'..'9' }) return
        mutable.value = state.value.copy(seconds = text, timeoutIssue = null); remember()
    }
    fun toggle(option: SourceCheckOption) {
        val value = state.value; var settings = value.settings ?: return
        if (value.saving || value.finished) return
        var infoEnabled = value.infoEnabled; var categoryEnabled = value.categoryEnabled; var contentEnabled = value.contentEnabled
        fun disableInfo() { settings = settings.copy(info = false, category = false, content = false); infoEnabled = false; categoryEnabled = false; contentEnabled = false }
        when (option) {
            SourceCheckOption.Comment -> settings = settings.copy(comment = !settings.comment)
            SourceCheckOption.Domain -> { settings = settings.copy(domain = !settings.domain)
                if (!settings.search && !settings.discovery && !settings.domain) settings = settings.copy(search = true) }
            SourceCheckOption.Search, SourceCheckOption.Discovery -> {
                settings = if (option == SourceCheckOption.Search) settings.copy(search = !settings.search) else settings.copy(discovery = !settings.discovery)
                if (!settings.search && !settings.discovery) {
                    disableInfo()
                    if (!settings.domain) settings = if (option == SourceCheckOption.Search) settings.copy(discovery = true) else settings.copy(search = true)
                } else infoEnabled = true
            }
            SourceCheckOption.Info -> { if (!infoEnabled) return
                settings = settings.copy(info = !settings.info)
                if (!settings.info) { settings = settings.copy(category = false, content = false); categoryEnabled = false; contentEnabled = false } else categoryEnabled = true }
            SourceCheckOption.Category -> { if (!categoryEnabled) return
                settings = settings.copy(category = !settings.category)
                if (!settings.category) { settings = settings.copy(content = false); contentEnabled = false } else contentEnabled = true }
            SourceCheckOption.Content -> { if (!contentEnabled) return; settings = settings.copy(content = !settings.content) }
        }
        mutable.value = value.copy(settings = settings, infoEnabled = infoEnabled, categoryEnabled = categoryEnabled, contentEnabled = contentEnabled)
        remember()
    }
    fun save() {
        val value = state.value; val settings = value.settings ?: return
        if (value.saving || value.finished) return
        val seconds = value.seconds.toLongOrNull()
        val issue = when { value.seconds.isBlank() -> SourceCheckTimeoutIssue.Empty
            seconds == null || seconds <= 0 || seconds > Long.MAX_VALUE / 1000 -> SourceCheckTimeoutIssue.Invalid
            else -> null }
        if (issue != null) { mutable.value = value.copy(timeoutIssue = issue); return }
        mutable.value = value.copy(saving = true, error = null)
        viewModelScope.launch {
            try { repository.save(settings.copy(timeout = seconds!! * 1000)); currentCoroutineContext().ensureActive()
                saved["finished"] = true; mutable.value = state.value.copy(saving = false, finished = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(saving = false, error = error.localizedMessage ?: "Error") }
        }
    }
    fun delivered() { saved["finished"] = false; mutable.value = state.value.copy(finished = false) }
    fun stop() { viewModelScope.cancel() }
}
