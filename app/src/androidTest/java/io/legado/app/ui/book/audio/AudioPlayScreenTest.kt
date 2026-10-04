package io.legado.app.ui.book.audio

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioPlayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun playbackAndChapterButtonsDispatchOnlyUserActions() {
        val actions = mutableListOf<AudioPlayAction>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            LegadoComposeTheme {
                AudioPlayScreen(
                    AudioPlayUiState(ready = true, chapterCount = 2),
                    {},
                    {},
                    actions::add,
                    {},
                    {},
                    {},
                )
            }
        }
        compose.runOnIdle { assertEquals(emptyList<AudioPlayAction>(), actions) }
        compose
            .onNodeWithContentDescription(context.getString(R.string.previous))
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.audio_play)).performClick()
        compose.onNodeWithText(context.getString(R.string.chapter_list)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(AudioPlayAction.Play, AudioPlayAction.Chapters), actions)
        }
    }

    @Test
    fun lyricClickSeeksAndKeepsChapterControls() {
        var seek = -1
        compose.setContent {
            LegadoComposeTheme {
                AudioPlayScreen(
                    AudioPlayUiState(ready = true, lyrics = listOf(AudioLyric(1200, "Tap lyric"))),
                    {},
                    {},
                    {},
                    {},
                    {},
                    { seek = it },
                )
            }
        }
        compose.onNodeWithText("Tap lyric").performClick()
        compose.runOnIdle { assertEquals(1200, seek) }
    }
}
