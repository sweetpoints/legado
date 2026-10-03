package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.MangaEpaperPreferences
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MangaEpaperScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thresholdSliderExposesFullRangeAndEmitsIntegerEdits() {
        var threshold: Int? = null
        compose.setContent {
            LegadoComposeTheme {
                MangaEpaperScreen(MangaEpaperUiState(91, false), { threshold = it }, {})
            }
        }
        compose
            .onNodeWithTag("manga-epaper-slider")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(91f, 0f..255f, 254),
                )
            )
        compose.onNodeWithTag("manga-epaper-slider").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(220f)
        }
        compose.runOnIdle { assertEquals(220, threshold) }
    }

    @Test
    fun stepButtonsUpdateThresholdAndRespectEndpoints() {
        val threshold = mutableIntStateOf(0)
        compose.setContent {
            LegadoComposeTheme {
                MangaEpaperScreen(
                    MangaEpaperUiState(threshold.intValue, false),
                    { threshold.intValue = it },
                    {},
                )
            }
        }
        compose.onNodeWithTag("manga-epaper-minus").assertIsNotEnabled()
        compose.onNodeWithTag("manga-epaper-plus").performClick()
        compose.runOnIdle { assertEquals(1, threshold.intValue) }
        compose.onNodeWithTag("manga-epaper-minus").performClick()
        compose.runOnIdle {
            assertEquals(0, threshold.intValue)
            threshold.intValue = 255
        }
        compose.onNodeWithTag("manga-epaper-plus").assertIsNotEnabled()
        compose.onNodeWithTag("manga-epaper-minus").performClick()
        compose.runOnIdle { assertEquals(254, threshold.intValue) }
    }

    @Test
    fun loadingAndReadFailureOfferRetry() {
        var retries = 0
        compose.setContent {
            LegadoComposeTheme {
                MangaEpaperScreen(
                    MangaEpaperUiState(error = "read failed"),
                    {},
                    { retries++ },
                    Modifier.heightIn(max = 120.dp),
                )
            }
        }
        compose.onNodeWithTag("manga-epaper-loading").assertExists()
        compose.onNodeWithText("read failed").assertExists()
        compose.onNodeWithTag("manga-epaper-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun routeUpdatesReaderLiveAndPersistsOnlyOnRealDismiss() {
        val observed = mutableListOf<Int>()
        val saved = mutableListOf<Int>()
        lateinit var model: MangaEpaperViewModel
        compose.runOnIdle {
            model =
                MangaEpaperViewModel(
                    object : MangaEpaperPreferences {
                        override suspend fun loadThreshold() = 91

                        override fun saveThreshold(threshold: Int) {
                            saved += threshold
                        }
                    },
                    SavedStateHandle(),
                )
        }
        compose.setContent { LegadoComposeTheme { MangaEpaperRoute(model, { observed += it }) } }
        compose.waitUntil { observed.lastOrNull() == 91 }
        compose.onNodeWithTag("manga-epaper-slider").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(201f)
        }
        compose.waitUntil { observed.lastOrNull() == 201 }
        compose.runOnIdle {
            assertTrue(saved.isEmpty())
            model.onDismiss(true)
            assertTrue(saved.isEmpty())
            model.onDismiss(false)
            assertEquals(listOf(201), saved)
        }
    }
}
