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

class VideoPlayerToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toolbarReflectsConditionalActionsAndDispatchesMenuSelections() {
        var state by
            mutableStateOf(
                VideoPlayerToolbarState(
                    title = "Video title",
                    customButtonVisible = true,
                    favoriteActionVisible = true,
                    isFavorite = true,
                    loginActionVisible = false,
                )
            )
        var backClicks = 0
        var customClicks = 0
        var favoriteClicks = 0
        var floatingClicks = 0
        var selectedAction: VideoPlayerToolbarAction? = null
        compose.setContent {
            LegadoComposeTheme {
                VideoPlayerToolbar(
                    state = state,
                    onBack = { backClicks++ },
                    onCustomButton = { customClicks++ },
                    onFavorite = { favoriteClicks++ },
                    onFloatingWindow = { floatingClicks++ },
                    onMenuExpandedChange = { expanded ->
                        state = state.copy(menuExpanded = expanded)
                    },
                    onMenuAction = { action -> selectedAction = action },
                )
            }
        }

        compose.onNodeWithTag("video-toolbar-back").performClick()
        compose.onNodeWithTag("video-toolbar-custom").performClick()
        compose.onNodeWithTag("video-toolbar-favorite").performClick()
        compose.onNodeWithTag("video-toolbar-floating").performClick()
        compose.onNodeWithTag("video-toolbar-more").performClick()
        compose.onNodeWithTag("video-menu-settings").assertExists().performClick()
        compose.onNodeWithTag("video-menu-login").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(customButtonVisible = false, favoriteActionVisible = false)
        }
        compose.onNodeWithTag("video-toolbar-custom").assertDoesNotExist()
        compose.onNodeWithTag("video-toolbar-favorite").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loginActionVisible = true) }
        compose.onNodeWithTag("video-toolbar-more").performClick()
        compose.onNodeWithTag("video-menu-login").assertExists()

        assertEquals(1, backClicks)
        assertEquals(1, customClicks)
        assertEquals(1, favoriteClicks)
        assertEquals(1, floatingClicks)
        assertEquals(VideoPlayerToolbarAction.Settings, selectedAction)
    }
}
