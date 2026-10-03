package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.MangaColorFilterRepository
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MangaColorFilterScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun numericInputAndFineControlsEmitChannelValues() {
        val changes = mutableListOf<Pair<MangaColorChannel, Int>>()
        compose.setContent {
            LegadoComposeTheme {
                MangaColorFilterScreen(
                    MangaColorFilterUiState(MangaColorFilterValues(red = 10), loading = false),
                    { channel, value -> changes += channel to value },
                    {},
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
        compose.onNodeWithTag("manga-filter-red-plus").performClick()
        compose.onNodeWithTag("manga-filter-red-minus").performClick()
        compose.onNodeWithTag("manga-filter-brightness-value").performTextReplacement("100")
        compose
            .onNodeWithTag("manga-filter-alpha-value")
            .performScrollTo()
            .performTextReplacement("999")
        compose.runOnIdle {
            assertEquals(
                listOf(
                    MangaColorChannel.RED to 11,
                    MangaColorChannel.RED to 9,
                    MangaColorChannel.BRIGHTNESS to 100,
                    MangaColorChannel.ALPHA to 255,
                ),
                changes,
            )
        }
    }

    @Test
    fun routeDeliversIndependentReaderCopiesForEachEdit() {
        lateinit var model: MangaColorFilterViewModel
        val callbacks = mutableListOf<MangaColorFilterConfig>()
        compose.runOnIdle {
            model =
                MangaColorFilterViewModel(
                    object : MangaColorFilterRepository {
                        override suspend fun load() = MangaColorFilterValues(red = 12)

                        override fun save(values: MangaColorFilterValues) = Unit
                    },
                    SavedStateHandle(),
                )
        }
        compose.setContent {
            LegadoComposeTheme {
                MangaColorFilterRoute(
                    model,
                    {
                        callbacks += it
                        it.r = 222
                    },
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
        compose.runOnIdle { model.change(MangaColorChannel.RED, 45) }
        compose.waitUntil { callbacks.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(45, model.state.value.values.red)
            model.change(MangaColorChannel.BLUE, 67)
        }
        compose.waitUntil { callbacks.size >= 2 }
        compose.runOnIdle {
            assertNotSame(callbacks[0], callbacks[1])
            assertEquals(45, model.state.value.values.red)
            assertEquals(67, model.state.value.values.blue)
        }
    }

    @Test
    fun endpointsDisableSteppingAndFinishedDisablesEditing() {
        var state by
            mutableStateOf(
                MangaColorFilterUiState(MangaColorFilterValues(red = 255), loading = false)
            )
        compose.setContent {
            LegadoComposeTheme {
                MangaColorFilterScreen(state, { _, _ -> }, {}, Modifier.heightIn(max = 650.dp))
            }
        }
        compose.onNodeWithTag("manga-filter-brightness-minus").assertIsNotEnabled()
        compose.onNodeWithTag("manga-filter-red-plus").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(finished = true) }
        compose.onNodeWithTag("manga-filter-red-value").assertIsNotEnabled()
        compose.onNodeWithTag("manga-filter-red-slider").assertIsNotEnabled()
    }

    @Test
    fun loadingAndFailureExposeRetry() {
        var retries = 0
        var state by mutableStateOf(MangaColorFilterUiState())
        compose.setContent {
            LegadoComposeTheme {
                MangaColorFilterScreen(
                    state,
                    { _, _ -> },
                    { retries++ },
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
        compose.onNodeWithTag("manga-filter-loading").assertExists()
        compose.runOnIdle { state = state.copy(loading = false, error = "failed") }
        compose.onNodeWithTag("manga-filter-loading").assertDoesNotExist()
        compose.onNodeWithTag("manga-filter-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
