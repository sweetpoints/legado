package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.ReadAloudControlsNumber
import io.legado.app.data.preferences.ReadAloudControlsSettings
import io.legado.app.data.preferences.ReadAloudControlsSettingsRepository
import io.legado.app.data.preferences.ReadAloudControlsToggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadAloudControlsAction { Reveal, ResetPosition }
data class ReadAloudControlsSettingsUiState(val settings: ReadAloudControlsSettings, val action: ReadAloudControlsAction? = null)

class ReadAloudControlsSettingsViewModel(private val repository: ReadAloudControlsSettingsRepository,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val drafts = ReadAloudControlsNumber.entries.mapNotNull { number ->
        savedState.get<Int>("controls.draft.${number.name}")?.let { number to it.coerceIn(number.minimum, number.maximum) }
    }.toMap().toMutableMap()
    private val mutableState = MutableStateFlow(ReadAloudControlsSettingsUiState(overlay(repository.load()),
        savedState.get<String>("controls.action")?.let { value -> ReadAloudControlsAction.entries.find { it.name == value } }))
    val state = mutableState.asStateFlow()
    private var observation: AutoCloseable? = null
    private var generation = 0
    fun startObserving() {
        if (observation != null) return
        refresh()
        val version = ++generation
        observation = repository.observe { if (version == generation) refresh() }
    }
    fun stopObserving() { generation++; observation?.close(); observation = null }
    private fun overlay(settings: ReadAloudControlsSettings) = settings.copy(numbers = settings.numbers + drafts)
    private fun refresh() { mutableState.value = state.value.copy(settings = overlay(repository.load())) }
    fun setToggle(setting: ReadAloudControlsToggle, enabled: Boolean) {
        if (state.value.settings[setting] == enabled) return
        repository.setToggle(setting, enabled)
        refresh()
        if (setting == ReadAloudControlsToggle.Realtime) requestAction(ReadAloudControlsAction.Reveal)
    }
    fun drag(setting: ReadAloudControlsNumber, value: Int) {
        val next = value.coerceIn(setting.minimum, setting.maximum)
        drafts[setting] = next
        savedState["controls.draft.${setting.name}"] = next
        mutableState.value = state.value.copy(settings = state.value.settings.copy(numbers = state.value.settings.numbers + (setting to next)))
    }
    fun finish(setting: ReadAloudControlsNumber) {
        val value = drafts.remove(setting) ?: return
        savedState["controls.draft.${setting.name}"] = null as Int?
        if (repository.load()[setting] != value) repository.setNumber(setting, value)
        refresh()
    }
    fun step(setting: ReadAloudControlsNumber, direction: Int) {
        drag(setting, state.value.settings[setting] + direction.coerceIn(-1, 1) * setting.increment)
        finish(setting)
    }
    fun flush() { ReadAloudControlsNumber.entries.forEach(::finish) }
    fun requestAction(action: ReadAloudControlsAction) {
        if (state.value.action == action ||
            state.value.action == ReadAloudControlsAction.ResetPosition && action == ReadAloudControlsAction.Reveal) return
        mutableState.value = state.value.copy(action = action)
        savedState["controls.action"] = action.name
    }
    fun actionHandled(action: ReadAloudControlsAction) {
        if (state.value.action != action) return
        mutableState.value = state.value.copy(action = null)
        savedState["controls.action"] = null as String?
    }
    override fun onCleared() { stopObserving(); super.onCleared() }
}
