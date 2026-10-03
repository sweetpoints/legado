package io.legado.app.ui.book.read.config

import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.MoreReaderSetting
import io.legado.app.data.preferences.MoreReaderSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MoreReaderSettingsUiState(val values: Map<String, String>)

class MoreReaderSettingsViewModel(private val repository: MoreReaderSettingsRepository) :
    ViewModel() {
    private val mutableState = MutableStateFlow(MoreReaderSettingsUiState(repository.load()))
    val state = mutableState.asStateFlow()

    fun refresh() {
        mutableState.value = MoreReaderSettingsUiState(repository.load())
    }

    fun change(setting: MoreReaderSetting, value: String) {
        if (setting is MoreReaderSetting.Action || state.value.values[setting.key] == value) return
        repository.save(setting, value)
        refresh()
    }

    fun toggle(setting: MoreReaderSetting.Toggle, checked: Boolean) =
        change(setting, checked.toString())

    fun choose(setting: MoreReaderSetting.Choice, value: String) = change(setting, value)

    fun setSpeed(setting: MoreReaderSetting.SeekBar, value: Int) = change(setting, value.toString())
}
