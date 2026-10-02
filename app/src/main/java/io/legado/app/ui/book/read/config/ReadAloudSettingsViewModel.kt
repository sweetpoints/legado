package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.ReadAloudPreferences
import io.legado.app.data.preferences.ReadAloudSettingsRepository
import io.legado.app.data.preferences.ReadAloudSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ReadAloudSettingsDestination { Controls, Engine, SystemTts }
data class ReadAloudSettingsUiState(
    val preferences: ReadAloudPreferences,
    val engineName: String? = null,
    val showStartPicker: Boolean = false,
    val navigation: ReadAloudSettingsDestination? = null,
    val error: String? = null,
)

class ReadAloudSettingsViewModel(private val repository: ReadAloudSettingsRepository,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(ReadAloudSettingsUiState(repository.load(),
        showStartPicker = savedState["aloud.startPicker"] ?: false,
        navigation = savedState.get<String>("aloud.navigation")?.let { value -> ReadAloudSettingsDestination.entries.find { it.name == value } }))
    val state = mutableState.asStateFlow()
    private var observation: AutoCloseable? = null
    private var observing = false
    private var observationGeneration = 0
    private var engineJob: Job? = null

    fun startObserving() {
        if (observing) return
        // Normalize legacy preference values before subscribing, so initialization has no playback side effects.
        refreshPreferences()
        observing = true
        val generation = ++observationGeneration
        observation = repository.observeChanges { key ->
            if (!observing || observationGeneration != generation) return@observeChanges
            refreshPreferences()
            if (key == PreferKey.ttsEngine) refreshEngine()
            if (key in listOf(PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio) && repository.playbackRunning())
                repository.notifyPlaybackConfigurationChanged()
        }
        refreshEngine()
    }
    fun stopObserving() {
        observing = false
        observationGeneration++
        observation?.close()
        observation = null
    }
    private fun refreshPreferences() { mutableState.value = state.value.copy(preferences = repository.load()) }
    fun refreshEngine() {
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            try {
                val name = repository.engineName()
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(engineName = name, error = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(error = error.localizedMessage ?: error.toString())
            }
        }
    }
    fun setSwitch(setting: ReadAloudSwitch, enabled: Boolean) {
        if (setting == ReadAloudSwitch.PauseDuringCalls && !state.value.preferences[ReadAloudSwitch.IgnoreAudioFocus]) return
        if (state.value.preferences[setting] == enabled) return
        repository.setSwitch(setting, enabled)
        refreshPreferences()
    }
    fun openStartPicker() { mutableState.value = state.value.copy(showStartPicker = true); savedState["aloud.startPicker"] = true }
    fun dismissStartPicker() { mutableState.value = state.value.copy(showStartPicker = false); savedState["aloud.startPicker"] = false }
    fun setStart(mode: String) {
        if (mode !in listOf("page", "sentence")) return
        if (mode != state.value.preferences.start) repository.setStart(mode)
        refreshPreferences()
        dismissStartPicker()
    }
    fun navigate(destination: ReadAloudSettingsDestination) {
        if (state.value.navigation != null) return
        mutableState.value = state.value.copy(navigation = destination)
        savedState["aloud.navigation"] = destination.name
    }
    fun navigated(destination: ReadAloudSettingsDestination) {
        if (state.value.navigation != destination) return
        mutableState.value = state.value.copy(navigation = null)
        savedState["aloud.navigation"] = null as String?
    }
    override fun onCleared() { stopObserving(); super.onCleared() }
}
