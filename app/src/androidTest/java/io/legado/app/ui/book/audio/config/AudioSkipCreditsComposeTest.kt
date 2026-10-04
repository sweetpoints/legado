package io.legado.app.ui.book.audio.config

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.data.repository.AudioSkipCreditsDraft
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.testutil.saveSemantics
import android.graphics.Bitmap
import java.io.File
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
        val slider = compose.onNodeWithTag("audio-skip-opening-slider")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        try {
            // Slider's accessibility bounds expand past its visual track. Begin inside the
            // actual control instead of centerRight, which can be outside its pointer region.
            slider.performTouchInput {
                down(Offset(width * .8f, center.y))
                moveTo(Offset(width * .6f, center.y), delayMillis = 32)
                moveTo(Offset(width * .4f, center.y), delayMillis = 32)
            }
            compose.waitForIdle()
            val displayed = compose.onNodeWithTag("audio-skip-opening-value")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.singleOrNull()?.text?.toIntOrNull()
            assertTrue("The real slider changed during tracking: $displayed", displayed != null && displayed in 1..179)
            assertTrue(values.isEmpty())
            slider.performTouchInput { up() }
            compose.waitForIdle()
            assertEquals(1, values.size)
            assertEquals(displayed, values.single())
            assertTrue(values.single() in 1..179)
        } catch (error: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            compose.saveSemantics(context, "audio-skip-tracking-failure")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(context.getExternalFilesDir("ui-regression"), "audio-skip-tracking-failure.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
            throw error
        }
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
