package io.legado.app.ui.video

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class VideoBookIntroScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun plainIntroRendersTheOriginalTextInCompose() {
        compose.setContent {
            LegadoComposeTheme {
                VideoBookIntroScreen(
                    state = VideoBookIntroState(rawIntro = "Video description"),
                    onAction = {},
                    onLink = {},
                    onImage = {},
                )
            }
        }

        compose.onNodeWithTag("video-book-intro-text").assertTextEquals("Video description")
    }
}
