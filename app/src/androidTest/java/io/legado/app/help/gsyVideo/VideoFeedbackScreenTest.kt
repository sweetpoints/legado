package io.legado.app.help.gsyVideo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test

class VideoFeedbackScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun gesturesReplaceFeedbackAndClampOnlyVisualProgress() {
        var feedback by mutableStateOf(VideoFeedbackState("音量 120%", 1.2f))
        compose.setContent { LegadoComposeTheme { VideoFeedbackScreen(feedback) } }
        compose.onNodeWithText("音量 120%").assertExists()
        compose
            .onNodeWithTag("video-feedback-progress")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(1f, 0f..1f),
                )
            )
            .assertExists()
        compose.runOnIdle { feedback = VideoFeedbackState("亮度 0%", -.2f) }
        compose.onNodeWithText("亮度 0%").assertExists()
        compose.onNodeWithText("音量 120%").assertDoesNotExist()
        compose
            .onNodeWithTag("video-feedback-progress")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f),
                )
            )
            .assertExists()
    }
}
