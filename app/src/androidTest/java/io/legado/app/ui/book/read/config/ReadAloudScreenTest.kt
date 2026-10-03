package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadAloudScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun playPauseLabelChangesAndFollowSystemDisablesRateControls() {
        var state by mutableStateOf(ReadAloudUiState(paused = true, followSystem = false))
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudScreen(
                    state,
                    Color.Black,
                    Color.White,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose
            .onNodeWithTag("read-aloud-play-pause")
            .assertContentDescriptionEquals(context.getString(R.string.audio_play))
        compose.onNodeWithTag("read-aloud-rate-value").assertExists()
        compose.runOnIdle { state = state.copy(paused = false, followSystem = true) }
        compose
            .onNodeWithTag("read-aloud-play-pause")
            .assertContentDescriptionEquals(context.getString(R.string.pause))
        compose.onNodeWithTag("read-aloud-rate-value").assertDoesNotExist()
        compose.onNodeWithTag("read-aloud-rate").assertIsNotEnabled()
        compose.onNodeWithTag("read-aloud-rate-plus").assertIsNotEnabled()
        compose.onNodeWithTag("read-aloud-rate-minus").assertIsNotEnabled()
    }

    @Test
    fun tooltipUsesTheCurrentDynamicPlayPauseLabel() {
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudScreen(
                    ReadAloudUiState(paused = false),
                    Color.Black,
                    Color.White,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose.onNodeWithTag("read-aloud-play-pause").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.pause)).assertExists()
    }

    @Test
    fun controlsEmitDistinctChapterParagraphAndNavigationActions() {
        val actions = mutableListOf<ReadAloudControl>()
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudScreen(
                    ReadAloudUiState(),
                    Color.White,
                    Color.Black,
                    { actions += it },
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        listOf(
                "previous-chapter",
                "next-chapter",
                "previous-paragraph",
                "next-paragraph",
                "play-pause",
                "stop",
                "engine",
                "sleep-timer",
                "catalog",
                "main-menu",
                "background",
                "settings",
            )
            .forEach {
                compose.onNodeWithTag("read-aloud-$it").performScrollTo().performClick()
            }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    ReadAloudControl.PreviousChapter,
                    ReadAloudControl.NextChapter,
                    ReadAloudControl.PreviousParagraph,
                    ReadAloudControl.NextParagraph,
                    ReadAloudControl.PlayPause,
                    ReadAloudControl.Stop,
                    ReadAloudControl.Engine,
                    ReadAloudControl.SleepTimer,
                    ReadAloudControl.Catalog,
                    ReadAloudControl.MainMenu,
                    ReadAloudControl.Background,
                    ReadAloudControl.Settings,
                ),
                actions,
            )
        }
    }

    @Test
    fun timerAndRateAccessibleEditsFinishAfterValueAndDefaultSaveIsSeparate() {
        var state by mutableStateOf(ReadAloudUiState(followSystem = false))
        val actions = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudScreen(
                    state,
                    Color.White,
                    Color.Black,
                    {},
                    {
                        state = state.copy(rate = it)
                        actions += "rate:$it"
                    },
                    { actions += "rate-finished" },
                    {},
                    {},
                    {
                        state = state.copy(timer = it)
                        actions += "timer:$it"
                    },
                    { actions += "timer-finished" },
                    { actions += "save-default" },
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose.onNodeWithTag("read-aloud-timer").performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(30f)
        }
        compose.onNodeWithTag("read-aloud-rate").performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(45f)
        }
        compose.onNodeWithTag("read-aloud-save-timer").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("timer:30", "timer-finished", "rate:45", "rate-finished", "save-default"),
                actions,
            )
        }
    }
}
