package io.legado.app.ui.book.audio.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.legado.app.data.repository.AudioSkipCreditsDraft
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class AudioSkipCreditsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun scopeButtonsAndStepControlsUpdateDistinctChannelsWithinOriginalRange() {
        var state by
            mutableStateOf(
                AudioSkipCreditsState(
                    loading = false,
                    draft = AudioSkipCreditsDraft(false, 0, 180, 30, 40),
                )
            )
        val scopes = mutableListOf<Boolean>()
        compose.setContent {
            LegadoComposeTheme {
                AudioSkipCreditsScreen(
                    state,
                    {
                        scopes += it
                        state = state.copy(draft = state.draft!!.scope(it))
                    },
                    { state = state.copy(draft = state.draft!!.opening(it)) },
                    { state = state.copy(draft = state.draft!!.closing(it)) },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("audio-skip-opening-minus").assertIsNotEnabled()
        compose.onNodeWithTag("audio-skip-closing-plus").assertIsNotEnabled()
        compose.onNodeWithTag("audio-skip-opening-plus").performClick()
        compose.onNodeWithTag("audio-skip-opening-value").assertTextEquals("1")
        compose.onNodeWithTag("audio-skip-closing-minus").performClick()
        compose.onNodeWithTag("audio-skip-closing-value").assertTextEquals("179")
        compose.onNodeWithTag("audio-skip-global").performClick().assertIsSelected()
        compose.onNodeWithTag("audio-skip-opening-value").assertTextEquals("30")
        compose.onNodeWithTag("audio-skip-book").performClick().assertIsSelected()
        compose.onNodeWithTag("audio-skip-closing-value").assertTextEquals("40")
        assertEquals(listOf(true, false), scopes)
    }

    @Test
    fun sliderCommitsOnlyWhenTrackingStopsAndRawOverMaximumIsNotWrittenByRendering() {
        val values = mutableListOf<Int>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LegadoComposeTheme {
                AudioSkipCreditsScreen(
                    AudioSkipCreditsState(
                        loading = false,
                        draft = AudioSkipCreditsDraft(false, 900, 500, 1, 2),
                    ),
                    {},
                    { values += it },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("audio-skip-opening-value").assertTextEquals("180")
        assertTrue(values.isEmpty())
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(values.isEmpty())
        compose.onNodeWithTag("audio-skip-opening-slider").performTouchInput {
            down(centerRight)
            moveTo(center)
        }
        assertTrue(values.isEmpty())
        compose.onNodeWithTag("audio-skip-opening-slider").performTouchInput { up() }
        assertEquals(1, values.size)
        assertTrue(values.single() in 1..179)
    }

    @Test
    fun retryAndCloseAreAccessibleAndSavingDisablesMutableControls() {
        var state by
            mutableStateOf(
                AudioSkipCreditsState(
                    loading = false,
                    draft = AudioSkipCreditsDraft(false, 10, 20, 30, 40),
                    error = "save failed",
                )
            )
        var retries = 0
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                AudioSkipCreditsScreen(state, {}, {}, {}, { retries++ }, { closes++ })
            }
        }
        compose.onNodeWithTag("audio-skip-error").assertTextEquals("save failed")
        compose.onNodeWithTag("audio-skip-retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("audio-skip-close").performClick()
        assertEquals(1, closes)
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithTag("audio-skip-book").assertIsNotEnabled()
        compose.onNodeWithTag("audio-skip-opening-plus").assertIsNotEnabled()
        compose.onNodeWithTag("audio-skip-close").assertIsNotEnabled()
    }
}
