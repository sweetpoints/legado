package io.legado.app.help.gsyVideo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class VideoPlayerActionControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun actionsReflectPlayerStateAndCallOriginalPlayerCallbacks() {
        var state by
            mutableStateOf(
                VideoPlayerActionControlsState(
                    episodeControlsVisible = true,
                    danmakuVisible = true,
                    danmakuEnabled = true,
                    selectedSpeed = 1.5f,
                )
            )
        var nextClicks = 0
        var danmakuClicks = 0
        var episodeClicks = 0
        var speedClicks = 0
        compose.setContent {
            LegadoComposeTheme {
                VideoPlayerActionControls(
                    state = state,
                    onNext = { nextClicks++ },
                    onToggleDanmaku = { danmakuClicks++ },
                    onOpenEpisodes = { episodeClicks++ },
                    onOpenSpeed = { speedClicks++ },
                )
            }
        }

        compose.onNodeWithTag("video-next-episode").assertExists().performClick()
        compose.onNodeWithTag("video-toggle-danmaku").assertExists().performClick()
        compose.onNodeWithTag("video-open-episodes").assertExists().performClick()
        compose.onNodeWithTag("video-open-speed").assertTextEquals("1.5X").performClick()
        compose.runOnIdle {
            state = state.copy(episodeControlsVisible = false, danmakuEnabled = false)
        }
        compose.onNodeWithTag("video-next-episode").assertDoesNotExist()
        compose.onNodeWithTag("video-open-episodes").assertDoesNotExist()
        compose.onNodeWithTag("video-open-speed").assertDoesNotExist()

        assert(nextClicks == 1)
        assert(danmakuClicks == 1)
        assert(episodeClicks == 1)
        assert(speedClicks == 1)
    }
}
