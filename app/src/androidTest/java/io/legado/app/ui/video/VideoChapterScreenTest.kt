package io.legado.app.ui.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VideoChapterScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun choosingVolumeAndEpisodeUpdatesSelectionAndCatalogUsesExistingCallback() {
        var state by
            mutableStateOf(
                VideoChapterRailState(
                    volumes = listOf("Season 1", "Season 2"),
                    episodes = listOf("Episode 1", "Episode 2"),
                    selectedVolume = 0,
                    selectedEpisode = 0,
                )
            )
        var catalogRequests = 0

        compose.setContent {
            LegadoComposeTheme {
                VideoChapterScreen(
                    state = state,
                    onVolumeSelected = { state = state.copy(selectedVolume = it) },
                    onEpisodeSelected = { state = state.copy(selectedEpisode = it) },
                    onOpenCatalog = { catalogRequests += 1 },
                )
            }
        }

        compose.onNodeWithTag("video-volume-1").performClick()
        compose.onNodeWithTag("video-episode-1").performClick()
        compose.onNodeWithTag("video-open-catalog").performClick()

        compose.runOnIdle {
            assertEquals(1, state.selectedVolume)
            assertEquals(1, state.selectedEpisode)
            assertEquals(1, catalogRequests)
        }
    }

    @Test
    fun missingVolumeRailDoesNotHideEpisodesOrCatalog() {
        compose.setContent {
            LegadoComposeTheme {
                VideoChapterScreen(
                    state = VideoChapterRailState(episodes = listOf("Episode 1")),
                    onVolumeSelected = {},
                    onEpisodeSelected = {},
                    onOpenCatalog = {},
                )
            }
        }

        compose.onNodeWithTag("video-episode-0").assertExists()
        compose.onNodeWithTag("video-open-catalog").assertExists()
        compose.onNodeWithTag("video-volume-0").assertDoesNotExist()
    }
}
