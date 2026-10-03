package io.legado.app.ui.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class VideoBookHeaderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun titleAndAuthorRenderAndFollowStateChanges() {
        var state by mutableStateOf(VideoBookHeaderState("A very long video title", "Author"))
        compose.setContent { LegadoComposeTheme { VideoBookHeaderScreen(state) } }

        compose.onNodeWithTag("video-book-title").assertTextEquals("A very long video title")
        compose.onNodeWithTag("video-book-author").assertTextEquals("Author")

        compose.runOnIdle { state = VideoBookHeaderState("Next title", "") }
        compose.onNodeWithTag("video-book-title").assertTextEquals("Next title")
        compose.onNodeWithTag("video-book-author").assertDoesNotExist()
    }
}
