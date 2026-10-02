package io.legado.app.ui.highlight.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.help.HighlightStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal enum class HighlightRuleEditorEvent { Saved, Close, Missing, Invalid, Style }
internal data class HighlightRuleColorDraft(val channel: Int, val color: Int, val withAlpha: Boolean)
internal data class HighlightRuleEditorState(val draft: HighlightRuleEditorDraft? = null, val loading: Boolean = true,
    val saving: Boolean = false, val error: String? = null, val finished: Boolean = false,
    val event: HighlightRuleEditorEvent? = null, val color: HighlightRuleColorDraft? = null)
internal class HighlightRuleEditorViewModel(private val repository: HighlightRuleEditorRepository,
    private val saved: SavedStateHandle, private val id: Long, private val seed: String?) : ViewModel() {
    val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(HighlightRuleEditorState(finished = saved["finished"] ?: false,
        event = saved.get<String>("event")?.let { runCatching { HighlightRuleEditorEvent.valueOf(it) }.getOrNull() },
        color = saved.get<Int>("colorChannel")?.let { HighlightRuleColorDraft(it, saved["colorValue"] ?: 0, saved["colorAlpha"] ?: false) }))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var writer: Job? = null
    private var generation = 0L
    init { load() }
    private fun load() {
        val token = ++generation; job?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val draft = repository.initial(session, id, seed)
                if (token != generation) return@launch
                val complete = draft.savedRuleId != null
                mutable.value = state.value.copy(draft = draft, loading = false, finished = state.value.finished || complete)
                if (complete && saved.get<Boolean>("delivered") != true) event(HighlightRuleEditorEvent.Saved)
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: MissingHighlightRuleException) { if (token == generation) { mutable.value = state.value.copy(loading = false); event(HighlightRuleEditorEvent.Missing) } }
            catch (error: Exception) { if (token == generation) mutable.value = state.value.copy(loading = false, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun retry() { if (!state.value.saving && !state.value.finished) {
        if (state.value.draft == null) load() else flush()
    } }
    fun change(update: (HighlightRuleDraft) -> HighlightRuleDraft) {
        val value = state.value
        if (value.loading || value.saving || value.finished) return
        val draft = value.draft ?: return
        val rule = update(draft.rule)
        if (rule == draft.rule) return
        mutable.value = value.copy(draft = draft.copy(rule = rule, revision = draft.revision + 1), error = null)
        scheduleWrite()
    }
    fun style(style: HighlightStyle) = change { it.copy(style = style.normalized()) }
    private fun scheduleWrite() {
        writer?.cancel()
        writer = viewModelScope.launch { delay(150); writeCurrent() }
    }
    fun flush() { if (state.value.saving || state.value.finished) return
        writer?.cancel(); writer = viewModelScope.launch { writeCurrent() }
    }
    private suspend fun writeCurrent() {
        val draft = state.value.draft ?: return
        try { repository.draft(session, draft)
            if (state.value.draft?.revision == draft.revision) mutable.value = state.value.copy(error = null)
        } catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { if (state.value.draft?.revision == draft.revision && !state.value.finished)
            mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
    }
    fun save() {
        val value = state.value
        if (value.loading || value.saving || value.finished) return
        val draft = value.draft ?: return
        writer?.cancel(); mutable.value = value.copy(saving = true, error = null)
        job = viewModelScope.launch {
            try { val completed = repository.save(session, draft)
                saved["finished"] = true
                mutable.value = state.value.copy(draft = completed, saving = false, finished = true)
                event(HighlightRuleEditorEvent.Saved)
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: InvalidHighlightRuleException) { mutable.value = state.value.copy(saving = false); event(HighlightRuleEditorEvent.Invalid) }
            catch (error: Exception) { mutable.value = state.value.copy(saving = false, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun cancel() {
        if (state.value.saving || state.value.finished) return
        generation++; job?.cancel(); writer?.cancel()
        saved["finished"] = true; mutable.value = state.value.copy(finished = true, loading = false, color = null)
        event(HighlightRuleEditorEvent.Close)
    }
    fun openStyle() { if (!state.value.loading && !state.value.saving && !state.value.finished) event(HighlightRuleEditorEvent.Style) }
    fun color(channel: Int, color: Int, withAlpha: Boolean) {
        if (state.value.loading || state.value.saving || state.value.finished) return
        saved["colorChannel"] = channel; saved["colorValue"] = color; saved["colorAlpha"] = withAlpha
        mutable.value = state.value.copy(color = HighlightRuleColorDraft(channel, color, withAlpha))
    }
    fun closeColor() {
        saved.remove<Int>("colorChannel"); saved.remove<Int>("colorValue"); saved.remove<Boolean>("colorAlpha")
        mutable.value = state.value.copy(color = null)
    }
    private fun event(value: HighlightRuleEditorEvent) { saved["event"] = value.name; mutable.value = state.value.copy(event = value) }
    fun consume(value: HighlightRuleEditorEvent) {
        if (state.value.event != value) return
        if (value == HighlightRuleEditorEvent.Saved) saved["delivered"] = true
        saved.remove<String>("event"); mutable.value = state.value.copy(event = null)
    }
}
