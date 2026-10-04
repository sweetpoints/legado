package io.legado.app.ui.rss.read

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RssReaderScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun actions(action: (RssReaderAction) -> Unit = {}) =
        RssReaderActions({}, {}, action, {})

    @Test
    fun ordinaryChromeExposesRefreshShareAndConditionalFavorite() {
        var selected: RssReaderAction? = null
        compose.setContent {
            MaterialTheme {
                RssReaderScreen(
                    RssReaderState(loaded = true, article = true, title = "Owned article"),
                    false,
                    actions { selected = it },
                    browser = { Text("Browser document") },
                )
            }
        }
        compose.onNodeWithTag("rss-reader-refresh").performClick()
        compose.runOnIdle { assertEquals(RssReaderAction.Refresh, selected) }
        compose.onNodeWithTag("rss-reader-favorite").performClick()
        compose.runOnIdle { assertEquals(RssReaderAction.Favorite, selected) }
        compose.onNodeWithTag("rss-reader-share").performClick()
        compose.runOnIdle { assertEquals(RssReaderAction.Share, selected) }
        compose.onNodeWithText("Owned article").assertIsDisplayed()
    }

    @Test
    fun loadingPreventsActionsAndNonArticleHasNoFavorite() {
        compose.setContent {
            MaterialTheme { RssReaderScreen(RssReaderState(), false, actions(), browser = {}) }
        }
        compose.onNodeWithTag("rss-reader-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("rss-reader-share").assertIsNotEnabled()
        compose.onNodeWithTag("rss-reader-more").assertIsNotEnabled()
        compose.onNodeWithTag("rss-reader-favorite").assertDoesNotExist()
    }

    @Test
    fun overflowKeepsSpeechWithoutIconAndFiltersLogin() {
        var selected: RssReaderAction? = null
        compose.setContent {
            MaterialTheme {
                RssReaderScreen(
                    RssReaderState(loaded = true, menu = true, canLogin = false),
                    false,
                    actions { selected = it },
                    browser = {},
                )
            }
        }
        compose.onNodeWithTag("rss-reader-login").assertDoesNotExist()
        compose.onNodeWithTag("rss-reader-speech").assertExists().performClick()
        compose.runOnIdle { assertEquals(RssReaderAction.Speech, selected) }
        // A text-only dropdown has no child with an icon content description.
        compose
            .onNodeWithTag("rss-reader-speech", useUnmergedTree = true)
            .onChildren()
            .filter(hasContentDescription("Read aloud"))
            .assertCountEquals(0)
    }

    @Test
    fun fullscreenKeepsBrowserCompositionWhileProvidingVideoSlot() {
        var fullscreen by mutableStateOf(false)
        var creations = 0
        var releases = 0
        compose.setContent {
            MaterialTheme {
                RssReaderScreen(
                    RssReaderState(loaded = true),
                    fullscreen,
                    actions(),
                    browser = {
                        DisposableEffect(Unit) {
                            creations++
                            onDispose { releases++ }
                        }
                        Text("Owned browser")
                    },
                    customVideo = { Text("Video") },
                )
            }
        }
        compose.runOnIdle { fullscreen = true }
        compose.onNodeWithTag("rss-reader-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("rss-reader-back").assertDoesNotExist()
        compose.onNodeWithTag("rss-reader-more").assertDoesNotExist()
        compose.runOnIdle {
            fullscreen = false
            assertEquals(1, creations)
            assertEquals(0, releases)
        }
        compose.onNodeWithTag("rss-reader-fullscreen").assertDoesNotExist()
    }

    @Test
    fun progressAndFailureRetryHaveRealInteractiveBehavior() {
        var retried = 0
        compose.setContent {
            MaterialTheme {
                RssReaderScreen(
                    RssReaderState(progress = 50, error = "Owned failure"),
                    false,
                    RssReaderActions({}, {}, {}, { retried++ }),
                    browser = {},
                )
            }
        }
        compose.onNodeWithTag("rss-reader-progress").assertIsDisplayed()
        compose.onNodeWithText("Owned failure").assertIsDisplayed()
        compose.onNodeWithTag("rss-reader-retry").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }
}
