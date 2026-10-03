package io.legado.app.ui.config

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BackgroundBlurScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun discreteSliderIncludesDisabledZeroAndBothRangeLimits() {
        var radius = -1
        compose.setContent {
            LegadoComposeTheme {
                BackgroundBlurScreen(
                    BackgroundBlurState(radius = 8, loading = false),
                    { radius = it },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("blur-radius").assertTextEquals("8")
        compose.onNodeWithTag("blur-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(0f)
        }
        assertEquals(0, radius)
        compose.onNodeWithTag("blur-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(25f)
        }
        assertEquals(25, radius)
    }

    @Test
    fun failedLoadOffersRetryAndCancelButBlocksConfirmAndSlider() {
        var retries = 0
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme {
                BackgroundBlurScreen(
                    BackgroundBlurState(loading = false, error = "Disk error"),
                    {},
                    {},
                    { cancelled++ },
                    { retries++ },
                )
            }
        }
        compose.onNodeWithTag("blur-slider").assertIsNotEnabled()
        compose.onNodeWithTag("blur-save").assertIsNotEnabled()
        compose.onNodeWithTag("blur-retry").performClick()
        compose.onNodeWithTag("blur-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancelled)
        }
    }
}
