package io.legado.app.ui.video.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.VideoSetting
import io.legado.app.data.preferences.VideoSettings
import io.legado.app.data.preferences.VideoSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoSettingsUiState(
    val settings: VideoSettings,
    val speedPickerVisible: Boolean = false,
    val speedDraft: Int = settings.pressSpeed,
)

class VideoSettingsViewModel(
    private val repository: VideoSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            repository.load().let {
                VideoSettingsUiState(
                    it,
                    savedState[VISIBLE] ?: false,
                    (savedState.get<Int>(DRAFT) ?: it.pressSpeed).coerceIn(5, 60),
                )
            }
        )
    val state = mutableState.asStateFlow()

    fun setEnabled(setting: VideoSetting, enabled: Boolean) {
        val old = state.value.settings
        val updated =
            when (setting) {
                VideoSetting.AutoPlay -> old.copy(autoPlay = enabled)
                VideoSetting.DefaultFloatWindow -> old.copy(defaultFloatWindow = enabled)
                VideoSetting.StartFull -> old.copy(startFull = enabled)
                VideoSetting.FullBottomProgress -> old.copy(fullBottomProgress = enabled)
            }
        if (updated == old) return
        repository.setEnabled(setting, enabled)
        mutableState.value = state.value.copy(settings = updated)
    }

    fun openSpeedPicker() {
        savedState[VISIBLE] = true
        savedState[DRAFT] = state.value.settings.pressSpeed
        mutableState.value =
            state.value.copy(
                speedPickerVisible = true,
                speedDraft = state.value.settings.pressSpeed,
            )
    }

    fun setSpeedDraft(value: Int) {
        if (!state.value.speedPickerVisible) return
        val bounded = value.coerceIn(5, 60)
        savedState[DRAFT] = bounded
        mutableState.value = state.value.copy(speedDraft = bounded)
    }

    fun cancelSpeedPicker() {
        savedState[VISIBLE] = false
        savedState.remove<Int>(DRAFT)
        mutableState.value = state.value.copy(speedPickerVisible = false)
    }

    fun confirmSpeed(default: Boolean = false) {
        if (!state.value.speedPickerVisible) return
        val speed = if (default) 30 else state.value.speedDraft
        repository.setPressSpeed(speed)
        mutableState.value =
            state.value.copy(settings = state.value.settings.copy(pressSpeed = speed))
        cancelSpeedPicker()
    }

    private companion object {
        const val VISIBLE = "video.speed.visible"
        const val DRAFT = "video.speed.draft"
    }
}
