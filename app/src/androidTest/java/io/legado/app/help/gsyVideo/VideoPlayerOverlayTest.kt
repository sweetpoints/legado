package io.legado.app.help.gsyVideo

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class VideoPlayerOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun playerControlsRenderProgressAndDispatchPlaybackAndFullscreenActions() {
        var playbackClicks = 0
        var fullscreenClicks = 0
        var lockClicks = 0
        val state =
            VideoPlayerOverlayState(
                title = "Episode title",
                fullscreen = true,
                controlsVisible = true,
                playing = true,
                currentPosition = 15_000L,
                duration = 60_000L,
                progress = 0.25f,
            )
        compose.setContent {
            LegadoComposeTheme {
                VideoPlayerOverlay(
                    state = state,
                    actions = VideoPlayerActionControlsState(),
                    onBack = {},
                    onToggleFullscreen = { fullscreenClicks++ },
                    onToggleLock = { lockClicks++ },
                    onTogglePlayback = { playbackClicks++ },
                    onSeekStarted = {},
                    onSeekFinished = {},
                    onNext = {},
                    onToggleDanmaku = {},
                    onOpenEpisodes = {},
                    onOpenSpeed = {},
                )
            }
        }

        compose.onNodeWithTag("video-current-time").assertTextEquals("00:15")
        compose.onNodeWithTag("video-total-time").assertTextEquals("01:00")
        compose.onNodeWithTag("video-seek-slider").assertExists()
        compose.onNodeWithTag("video-playback-toggle").assertExists().performClick()
        compose.onNodeWithTag("video-fullscreen-toggle").performClick()
        compose.onNodeWithTag("video-lock-toggle").performClick()

        assert(playbackClicks == 1)
        assert(fullscreenClicks == 1)
        assert(lockClicks == 1)
    }
}
