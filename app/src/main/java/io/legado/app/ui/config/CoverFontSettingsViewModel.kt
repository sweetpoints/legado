package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class CoverFontSettingsState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val settings: CoverFontSettingsSnapshot? = null, val editing: CoverFontSize? = null, val number: Int = 100,
    val fontEvent: String? = null, val fontRetry: Boolean = false, val error: String? = null)
internal class CoverFontSettingsViewModel(private val repository: CoverFontSettingsRepository,
    private val drafts: CoverFontDraftRepository, private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("coverFontSession") ?: UUID.randomUUID().toString().also { saved["coverFontSession"] = it }
    private val mutable = MutableStateFlow(CoverFontSettingsState(editing = saved.get<String>("editing")?.let { name -> CoverFontSize.entries.find { it.name == name } },
        number = saved.get<Int>("number") ?: 100, fontEvent = saved.get<String>("fontEvent")))
    val state = mutable.asStateFlow()
    private var stopped = false; private var generation = 0; private var revision = 0L; private var initialized = false
    private var observer: Job? = null; private var operation: Job? = null
    private var draft = CoverFontDraft(); private var early: CoverFontInput? = null; private var applied = false
    private var failedAction: (suspend () -> Unit)? = null
    init { initialize() }
    private fun usable() = !stopped && !state.value.loading && !state.value.failed && !state.value.busy && state.value.settings != null
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun initialize() {
        observer?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        observer = viewModelScope.launch {
            try {
                if (!initialized) {
                    draft = drafts.open(session); currentCoroutineContext().ensureActive(); revision = maxOf(revision, draft.revision); initialized = true
                    if (draft.input != null) mutable.value = state.value.copy(fontRetry = true, error = "字体处理未完成，请重试")
                }
                repository.observe().collect { settings ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && token == generation) {
                        mutable.value = state.value.copy(loading = false, failed = false, settings = settings)
                        early?.takeIf { !state.value.busy }?.let { early = null; startFont(it) }
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun boolean(key: CoverFontSwitch, value: Boolean) = run { repository.boolean(key, value) }
    fun edit(key: CoverFontSize) {
        if (!usable() || state.value.settings?.customSizesEnabled != true) return
        val value = state.value.settings!!.sizes.getValue(key); saved["editing"] = key.name; saved["number"] = value
        mutable.value = state.value.copy(editing = key, number = value)
    }
    fun number(value: Int) {
        if (!usable() || state.value.editing == null) return
        val clamped = value.coerceIn(50, 200); saved["number"] = clamped; mutable.value = state.value.copy(number = clamped)
    }
    fun dismiss() { saved.remove<String>("editing"); saved.remove<Int>("number"); mutable.value = state.value.copy(editing = null) }
    fun confirm(default: Boolean = false) {
        val key = state.value.editing ?: return; if (!usable()) return
        val value = if (default) 100 else state.value.number
        run { repository.size(key, value); currentCoroutineContext().ensureActive(); if (!stopped) dismiss() }
    }
    fun fontPicker() {
        if (!usable() || state.value.fontEvent != null) return
        val id = UUID.randomUUID().toString(); saved["fontEvent"] = id; mutable.value = state.value.copy(fontEvent = id)
    }
    fun consumeFontEvent(id: String): Boolean {
        if (!usable() || state.value.fontEvent != id) return false
        saved.remove<String>("fontEvent"); mutable.value = state.value.copy(fontEvent = null); return true
    }
    /** Retains FontSelectDialog.CallBack's public path API; a callback can precede draft initialization. */
    fun selectFont(path: String) {
        if (stopped) return
        saved.remove<String>("fontEvent"); mutable.value = state.value.copy(fontEvent = null)
        val input = CoverFontInput(UUID.randomUUID().toString(), path)
        if (!usable()) early = input else startFont(input)
    }
    private fun startFont(input: CoverFontInput) = run {
        applied = false; draft = CoverFontDraft(input, nextRevision()); drafts.write(session, draft); currentCoroutineContext().ensureActive(); applyFont(input)
    }
    private suspend fun applyFont(input: CoverFontInput) {
        if (!applied) { repository.font(input.path); currentCoroutineContext().ensureActive(); applied = true }
        val cleared = CoverFontDraft(revision = nextRevision()); drafts.write(session, cleared); currentCoroutineContext().ensureActive(); draft = cleared
        if (!stopped) mutable.value = state.value.copy(fontRetry = false)
    }
    fun retry() {
        if (stopped || state.value.busy) return
        when {
            state.value.failed -> initialize()
            early != null && usable() -> startFont(early!!.also { early = null })
            draft.input != null && usable() -> { val input = draft.input!!; run { drafts.write(session, draft); currentCoroutineContext().ensureActive(); applyFont(input) } }
            else -> failedAction?.let { run(it) }
        }
    }
    private fun run(action: suspend () -> Unit) {
        if (!usable()) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { action(); currentCoroutineContext().ensureActive(); val value = repository.load(); currentCoroutineContext().ensureActive()
                if (!stopped) { failedAction = null; mutable.value = state.value.copy(settings = value) } }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) { failedAction = action; mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), fontRetry = draft.input != null || early != null) } }
            finally { if (!stopped && currentCoroutineContext().isActive) {
                mutable.value = state.value.copy(busy = false); early?.let { early = null; startFont(it) }
            } }
        }
    }
    fun stop() { if (!stopped) { stopped = true; generation++; observer?.cancel(); operation?.cancel() } }
    suspend fun release() { stop(); drafts.release(session) }
    override fun onCleared() { stop() }
}
