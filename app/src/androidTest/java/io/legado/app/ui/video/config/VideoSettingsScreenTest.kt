package io.legado.app.ui.video.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.VideoSetting
import io.legado.app.data.preferences.VideoSettings
import io.legado.app.data.preferences.VideoSettingsRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fullScreenOptionTracksAutoplayFromFirstComposition() {
        var state by mutableStateOf(VideoSettingsUiState(VideoSettings(autoPlay = false)))
        compose.setContent {
            LegadoComposeTheme {
                VideoSettingsScreen(state, { _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("video-setting-StartFull").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(autoPlay = true)) }
        compose.onNodeWithTag("video-setting-StartFull").assertExists()
    }

    @Test
    fun speedPickerCancellationAndConfirmationUseSeparateDraft() {
        val writes = mutableListOf<Int>()
        lateinit var model: VideoSettingsViewModel
        compose.runOnIdle {
            model =
                VideoSettingsViewModel(
                    object : VideoSettingsRepository {
                        override fun load() = VideoSettings()

                        override fun setEnabled(setting: VideoSetting, enabled: Boolean) = Unit

                        override fun setPressSpeed(value: Int) {
                            writes += value
                        }
                    },
                    SavedStateHandle(),
                )
        }
        compose.setContent { LegadoComposeTheme { VideoSettingsRoute(model) } }
        compose.onNodeWithTag("video-speed-open").performClick()
        compose.onNodeWithTag("video-speed-slider").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(45f)
        }
        compose.onNodeWithTag("video-speed-cancel").performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithTag("video-speed-open").performClick()
        compose.onNodeWithTag("video-speed-slider").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(50f)
        }
        compose.onNodeWithTag("video-speed-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(50), writes) }
        compose.onNodeWithTag("video-speed-slider").assertDoesNotExist()
    }
}
