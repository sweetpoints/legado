package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.*
import io.legado.app.model.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal enum class ThemeSettingsPopup { Launcher, Elevation, Font, Color, DayBackground, NightBackground, SaveDay, SaveNight }
internal enum class ThemeSettingsDestination { ThemeList, Welcome, Cover, BottomSkin, BlurDay, BlurNight, ImageDay, ImageNight }
internal data class ThemeSettingsEvent(val id: String, val destination: ThemeSettingsDestination)
internal data class ThemeSettingsState(val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false,
    val settings: ThemeSettingsSnapshot? = null, val popup: ThemeSettingsPopup? = null, val number: Int = 0,
    val colorKey: ThemeColor? = null, val color: Int = 0, val name: String = "", val event: ThemeSettingsEvent? = null,
    val error: String? = null, val problem: ThemeSettingsProblem? = null, val downloaded: Boolean = false)

internal class ThemeSettingsViewModel(private val repository: ThemeSettingsRepository, private val names: ThemeNameDraftRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    val session = saved.get<String>("themeSession") ?: UUID.randomUUID().toString().also { saved["themeSession"] = it }
    private val mutable = MutableStateFlow(ThemeSettingsState(popup = saved.get<String>("popup")?.let { runCatching { ThemeSettingsPopup.valueOf(it) }.getOrNull() },
        number = saved.get<Int>("number") ?: 0, colorKey = saved.get<String>("colorKey")?.let { runCatching { ThemeColor.valueOf(it) }.getOrNull() },
        color = saved.get<Int>("color") ?: 0, event = saved.get<String>("event")?.let { id -> saved.get<String>("destination")?.let { runCatching { ThemeSettingsEvent(id, ThemeSettingsDestination.valueOf(it)) }.getOrNull() } }))
    val state = mutable.asStateFlow()
    private var initializedName = false
    private var nameDraft = ThemeNameDraft()
    private var stopped = false
    private var generation = 0
    private var observer: Job? = null
    private var operation: Job? = null
    private var imageWaiting: Job? = null
    private val nameGate = Mutex()
    private val drafts = MutableStateFlow<ThemeNameDraft?>(null)
    private val writer = viewModelScope.launch {
        drafts.filterNotNull().collect { value ->
            try { nameGate.withLock { names.write(session, value) }; currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && nameDraft.revision == value.revision) failure(error) }
        }
    }
    init { initialize() }
    private fun initialize() {
        observer?.cancel(); val token = ++generation
        mutable.value = mutable.value.copy(loading = true, failed = false, error = null, problem = null)
        observer = viewModelScope.launch {
            try {
                if (!initializedName) {
                    val disk = names.open(session); currentCoroutineContext().ensureActive()
                    nameDraft = disk; initializedName = true; mutable.value = mutable.value.copy(name = disk.value)
                }
                repository.observe().collect { value ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && token == generation) mutable.value = mutable.value.copy(loading = false, failed = false, settings = value)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && token == generation) mutable.value = mutable.value.copy(loading = false, failed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun retry() { if (!stopped && !state.value.busy) initialize() }
    private fun usable() = !stopped && !state.value.loading && !state.value.failed && !state.value.busy && state.value.settings != null
    fun popup(value: ThemeSettingsPopup, key: ThemeColor? = null) {
        if (!usable() || state.value.event != null) return
        val settings = state.value.settings!!
        val number = when (value) { ThemeSettingsPopup.Elevation -> settings.elevation.coerceIn(0, 32); ThemeSettingsPopup.Font -> settings.fontPicker; else -> 0 }
        val color = key?.let { settings.colors[it] } ?: 0
        saved["popup"] = value.name; saved["number"] = number; saved["colorKey"] = key?.name; saved["color"] = color
        mutable.value = state.value.copy(popup = value, number = number, colorKey = key, color = color, error = null, problem = null)
        if (value == ThemeSettingsPopup.SaveDay || value == ThemeSettingsPopup.SaveNight) name("")
    }
    fun dismissPopup() { saved.remove<String>("popup"); mutable.value = state.value.copy(popup = null, problem = null) }
    fun number(value: Int) {
        val range = if (state.value.popup == ThemeSettingsPopup.Font) 8..16 else 0..32
        val number = value.coerceIn(range); saved["number"] = number; mutable.value = state.value.copy(number = number)
    }
    fun color(value: Int) { val color = value or 0xff000000.toInt(); saved["color"] = color; mutable.value = state.value.copy(color = color, problem = null) }
    fun name(value: String) {
        if (!initializedName || stopped) return
        nameDraft = ThemeNameDraft(value, maxOf(System.nanoTime(), nameDraft.revision + 1)); drafts.value = nameDraft
        mutable.value = state.value.copy(name = value)
    }
    fun boolean(key: ThemeSwitch, value: Boolean) = run { repository.boolean(key, value) }
    fun toggleNight() = run { repository.toggleNight() }
    fun confirm(default: Boolean = false) {
        when (state.value.popup) {
            ThemeSettingsPopup.Font -> { val value = if (default) null else state.value.number; run(close = true) { repository.font(value) } }
            ThemeSettingsPopup.Elevation -> { val value = if (default) null else state.value.number; run(close = true) { repository.elevation(value) } }
            ThemeSettingsPopup.Color -> state.value.colorKey?.let { key -> val color = state.value.color
                if (key.background && !validThemeBackground(key.night, color)) failure(ThemeSettingsException(if (key.night) ThemeSettingsProblem.NightTooLight else ThemeSettingsProblem.DayTooDark))
                else run(close = true) { repository.color(key, color) }
            }
            ThemeSettingsPopup.SaveDay, ThemeSettingsPopup.SaveNight -> { val night = state.value.popup == ThemeSettingsPopup.SaveNight; val name = state.value.name
                run(close = true) { flush(); repository.saveTheme(night, name) } }
            else -> Unit
        }
    }
    fun launcher(value: String) = run(close = true) { repository.launcher(value) }
    fun destination(value: ThemeSettingsDestination) {
        if (!usable() || state.value.event != null) return
        val event = ThemeSettingsEvent(UUID.randomUUID().toString(), value)
        saved["event"] = event.id; saved["destination"] = value.name
        dismissPopup(); mutable.value = state.value.copy(event = event)
    }
    fun consumeEvent(id: String): Boolean {
        if (state.value.event?.id != id || stopped || state.value.loading || state.value.failed) return false
        saved.remove<String>("event"); saved.remove<String>("destination"); mutable.value = state.value.copy(event = null); return true
    }
    fun removeBackground(night: Boolean) = run(close = true) { repository.image(night, null) }
    fun imagePicker(night: Boolean) { saved["imagePickerNight"] = night }
    fun pickedImage(requestCode: Int, uri: String?) {
        val pending = saved.remove<Boolean>("imagePickerNight") ?: return
        if (requestCode != 0 && requestCode != if (pending) 122 else 121) return
        uri?.let { background(pending, it) }
    }
    fun background(night: Boolean, uri: String) {
        if (stopped) return
        imageWaiting?.cancel()
        imageWaiting = viewModelScope.launch {
            state.first { usable() }; currentCoroutineContext().ensureActive()
            this@ThemeSettingsViewModel.run { repository.image(night, uri); currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(downloaded = uri.startsWith("http:", true) || uri.startsWith("https:", true)) }
        }
    }
    fun blurFinished(night: Boolean) = run { repository.refreshTheme(night) }
    fun clearMessage() { mutable.value = state.value.copy(error = null, problem = null, downloaded = false) }
    private fun run(close: Boolean = false, action: suspend () -> Unit) {
        if (!usable()) return
        // The accepted popup is consumed before applying preferences can recreate its Activity.
        if (close) dismissPopup()
        mutable.value = state.value.copy(busy = true, error = null, problem = null, downloaded = false)
        operation = viewModelScope.launch {
            try { action(); currentCoroutineContext().ensureActive(); val settings = repository.load(); currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(settings = settings) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) failure(error) }
            finally { if (currentCoroutineContext().isActive && !stopped) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun failure(error: Exception) { mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), problem = (error as? ThemeSettingsException)?.problem) }
    suspend fun flush() { if (initializedName && !stopped) nameGate.withLock { names.write(session, nameDraft) } }
    fun stop() { if (!stopped) { stopped = true; generation++; observer?.cancel(); operation?.cancel(); imageWaiting?.cancel(); writer.cancel() } }
    suspend fun release() { stop(); names.release(session) }
    override fun onCleared() { stop() }
}
