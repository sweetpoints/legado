package io.legado.app.ui.video.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.VideoSetting
import io.legado.app.data.preferences.VideoSettings
import io.legado.app.data.preferences.VideoSettingsRepository
import org.junit.Assert.*
import org.junit.Test

class VideoSettingsViewModelTest {
    private class Repository : VideoSettingsRepository {
        var current = VideoSettings()
        val toggles = mutableListOf<Pair<VideoSetting, Boolean>>()
        val speeds = mutableListOf<Int>()

        override fun load() = current

        override fun setEnabled(setting: VideoSetting, enabled: Boolean) {
            toggles += setting to enabled
        }

        override fun setPressSpeed(value: Int) {
            speeds += value
            current = current.copy(pressSpeed = value)
        }
    }

    @Test
    fun togglesWriteOnlyChangedFieldAndKeepFullScreenChoiceWhileAutoplayDisabled() {
        val repository = Repository()
        val model = VideoSettingsViewModel(repository, SavedStateHandle())
        model.setEnabled(VideoSetting.StartFull, true)
        model.setEnabled(VideoSetting.AutoPlay, false)
        model.setEnabled(VideoSetting.AutoPlay, false)
        model.setEnabled(VideoSetting.DefaultFloatWindow, true)
        model.setEnabled(VideoSetting.FullBottomProgress, false)
        assertFalse(model.state.value.settings.autoPlay)
        assertTrue(model.state.value.settings.startFull)
        assertEquals(
            listOf(
                VideoSetting.StartFull to true,
                VideoSetting.AutoPlay to false,
                VideoSetting.DefaultFloatWindow to true,
                VideoSetting.FullBottomProgress to false,
            ),
            repository.toggles,
        )
    }

    @Test
    fun speedDraftIsBoundedAndCancellationDoesNotPersist() {
        val repository = Repository()
        val model = VideoSettingsViewModel(repository, SavedStateHandle())
        model.openSpeedPicker()
        model.setSpeedDraft(100)
        assertEquals(60, model.state.value.speedDraft)
        model.setSpeedDraft(-1)
        assertEquals(5, model.state.value.speedDraft)
        model.cancelSpeedPicker()
        model.confirmSpeed()
        assertTrue(repository.speeds.isEmpty())
        assertEquals(30, model.state.value.settings.pressSpeed)
        model.openSpeedPicker()
        assertEquals(30, model.state.value.speedDraft)
    }

    @Test
    fun recreationRetainsUncommittedSpeedAndConfirmationPersistsOnce() {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = VideoSettingsViewModel(repository, handle)
        model.openSpeedPicker()
        model.setSpeedDraft(43)
        val restored =
            VideoSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertTrue(restored.state.value.speedPickerVisible)
        assertEquals(43, restored.state.value.speedDraft)
        assertTrue(repository.speeds.isEmpty())
        restored.confirmSpeed()
        restored.confirmSpeed()
        assertEquals(listOf(43), repository.speeds)
        assertFalse(restored.state.value.speedPickerVisible)
    }

    @Test
    fun defaultSpeedCommitsThirtyAndClosesPicker() {
        val repository = Repository().apply { current = current.copy(pressSpeed = 60) }
        val model = VideoSettingsViewModel(repository, SavedStateHandle())
        model.openSpeedPicker()
        model.setSpeedDraft(5)
        model.confirmSpeed(default = true)
        assertEquals(listOf(30), repository.speeds)
        assertEquals(30, model.state.value.settings.pressSpeed)
        assertFalse(model.state.value.speedPickerVisible)
    }
}
