package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal enum class CoverDestination { Rules, Font }
internal data class CoverNavigation(val id: String, val destination: CoverDestination)
internal data class CoverPicker(val id: String, val key: CoverSettingImage)
internal data class CoverSettingsState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val settings: CoverSettingsSnapshot? = null, val popup: CoverSettingImage? = null, val picker: CoverPicker? = null,
    val navigation: CoverNavigation? = null, val imageRetry: Boolean = false, val error: String? = null)
internal class CoverSettingsViewModel(private val repository: CoverSettingsRepository, private val inputs: CoverImageInputRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("coverSession") ?: UUID.randomUUID().toString().also { saved["coverSession"] = it }
    private fun savedImage(key: String) = saved.get<String>(key)?.let { name -> CoverSettingImage.entries.find { it.name == name } }
    private val mutable = MutableStateFlow(CoverSettingsState(popup = savedImage("popup"),
        picker = saved.get<String>("picker")?.let { id -> savedImage("pickerKey")?.let { CoverPicker(id, it) } },
        navigation = saved.get<String>("navigation")?.let { id -> saved.get<String>("destination")?.let { name -> CoverDestination.entries.find { it.name == name }?.let { CoverNavigation(id, it) } } }))
    val state = mutable.asStateFlow()
    private var stopped = false; private var generation = 0; private var revision = 0L; private var initialized = false
    private var observer: Job? = null; private var operation: Job? = null
    private var draft = CoverImageDraft(); private var early: CoverImageInput? = null; private var applied = false
    private var failedAction: (suspend () -> Unit)? = null
    init { initialize() }
    private fun usable() = !stopped && !state.value.loading && !state.value.failed && !state.value.busy && state.value.settings != null
    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }
    private fun initialize() {
        observer?.cancel(); val token = ++generation; mutable.value = state.value.copy(loading = true, failed = false, error = null)
        observer = viewModelScope.launch {
            try {
                if (!initialized) {
                    draft = inputs.open(session); currentCoroutineContext().ensureActive(); revision = maxOf(revision, draft.revision); initialized = true
                    if (draft.input != null) mutable.value = state.value.copy(imageRetry = true, error = "图片处理未完成，请重试")
                }
                repository.observe().collect { settings ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && token == generation) {
                        mutable.value = state.value.copy(loading = false, failed = false, settings = settings)
                        early?.takeIf { !state.value.busy }?.let { early = null; startImage(it) }
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = state.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun boolean(key: CoverSettingSwitch, value: Boolean) {
        if (state.value.settings?.enabled(key) != true) return
        run { repository.boolean(key, value) }
    }
    fun destination(destination: CoverDestination) {
        if (!usable() || state.value.navigation != null) return
        val event = CoverNavigation(UUID.randomUUID().toString(), destination)
        saved["navigation"] = event.id; saved["destination"] = destination.name; mutable.value = state.value.copy(navigation = event)
    }
    fun consumeNavigation(id: String): Boolean {
        if (!usable() || state.value.navigation?.id != id) return false
        saved.remove<String>("navigation"); saved.remove<String>("destination"); mutable.value = state.value.copy(navigation = null); return true
    }
    fun imageAction(key: CoverSettingImage) {
        if (!usable()) return
        if (state.value.settings!!.images.getValue(key).isEmpty()) picker(key)
        else { saved["popup"] = key.name; mutable.value = state.value.copy(popup = key) }
    }
    fun dismissPopup() { saved.remove<String>("popup"); mutable.value = state.value.copy(popup = null) }
    fun picker(key: CoverSettingImage) {
        if (!usable() || state.value.picker != null) return
        dismissPopup(); val event = CoverPicker(UUID.randomUUID().toString(), key)
        saved["picker"] = event.id; saved["pickerKey"] = key.name; mutable.value = state.value.copy(picker = event)
    }
    fun consumePicker(id: String): Boolean {
        if (!usable() || state.value.picker?.id != id) return false
        saved.remove<String>("picker"); mutable.value = state.value.copy(picker = null); return true
    }
    fun pickedImage(uri: String?, resultKey: String? = null) {
        if (stopped) return
        val key = savedImage("pickerKey") ?: return
        if (resultKey != null && resultKey != key.key) return
        saved.remove<String>("pickerKey"); saved.remove<String>("picker"); mutable.value = state.value.copy(picker = null)
        if (uri == null) return
        val input = CoverImageInput(UUID.randomUUID().toString(), key, uri)
        if (!usable()) early = input else startImage(input)
    }
    private fun startImage(input: CoverImageInput) = run {
        applied = false; draft = CoverImageDraft(input, nextRevision()); inputs.write(session, draft); currentCoroutineContext().ensureActive(); applyImage(input)
    }
    private suspend fun applyImage(input: CoverImageInput) {
        if (!applied) { repository.image(input.key, input.uri); currentCoroutineContext().ensureActive(); applied = true }
        val cleared = CoverImageDraft(revision = nextRevision()); inputs.write(session, cleared); currentCoroutineContext().ensureActive(); draft = cleared
        if (!stopped) mutable.value = state.value.copy(imageRetry = false)
    }
    fun removeImage(key: CoverSettingImage) { dismissPopup(); run { repository.image(key, null) } }
    fun retry() {
        if (stopped || state.value.busy) return
        when {
            state.value.failed -> initialize()
            early != null && usable() -> startImage(early!!.also { early = null })
            draft.input != null && usable() -> { val input = draft.input!!; run { inputs.write(session, draft); currentCoroutineContext().ensureActive(); applyImage(input) } }
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
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) { failedAction = action; mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), imageRetry = draft.input != null || early != null) } }
            finally { if (!stopped && currentCoroutineContext().isActive) {
                mutable.value = state.value.copy(busy = false); early?.let { early = null; startImage(it) }
            } }
        }
    }
    fun stop() { if (!stopped) { stopped = true; generation++; observer?.cancel(); operation?.cancel() } }
    suspend fun release() { stop(); inputs.release(session) }
    override fun onCleared() { stop() }
}
