package io.legado.app.help.gsyVideo

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class FloatingPlayerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun floatingWindowControlsDispatchCloseFullscreenAndPlayback() {
        var closeClicks = 0
        var fullscreenClicks = 0
        var playbackClicks = 0
        compose.setContent {
            LegadoComposeTheme {
                FloatingPlayerControls(
                    state = FloatingPlayerControlsState(playing = true, progress = 0.4f),
                    onClose = { closeClicks++ },
                    onFullscreen = { fullscreenClicks++ },
                    onPlayback = { playbackClicks++ },
                )
            }
        }

        compose.onNodeWithTag("floating-video-progress").assertExists()
        compose.onNodeWithTag("floating-video-close").performClick()
        compose.onNodeWithTag("floating-video-fullscreen").performClick()
        compose.onNodeWithTag("floating-video-playback").performClick()

        assert(closeClicks == 1)
        assert(fullscreenClicks == 1)
        assert(playbackClicks == 1)
    }
}
