package io.legado.app.ui.main

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun contentStaysBelowTheStatusBarInAnEdgeToEdgeWindow() {
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            LegadoComposeTheme {
                MainScreen(MainUiState(), 0, emptyMap(), false, {}) {
                    Box(
                        Modifier.fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                                )
                            )
                            .testTag("main-content")
                    )
                }
            }
        }
        compose.runOnUiThread {
            var activityContext = view.context
            while (activityContext is ContextWrapper && activityContext !is ComponentActivity) {
                activityContext = activityContext.baseContext
            }
            (activityContext as ComponentActivity).enableEdgeToEdge()
        }
        compose.waitForIdle()
        val safe =
            view.rootWindowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
        val top = safe.top
        assertTrue("The fixture must expose a real status bar inset", top > 0)
        val bounds = compose.onNodeWithTag("main-content").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Content must consume the current safe top inset exactly once",
            top.toFloat(),
            bounds.top,
            1f,
        )
        assertEquals("Respect the current left safe inset", safe.left.toFloat(), bounds.left, 1f)
        assertEquals(
            "Respect the current right safe inset",
            (view.width - safe.right).toFloat(),
            bounds.right,
            1f,
        )
    }

    @Test
    fun selectingATabEmitsItsStableDestination() {
        var clicked: MainDestination? = null
        val state = mutableStateOf(MainUiState())
        compose.setContent {
            LegadoComposeTheme {
                MainScreen(
                    state.value,
                    0,
                    emptyMap(),
                    false,
                    {
                        clicked = it
                        state.value = state.value.copy(selectedDestination = it)
                    },
                ) {
                    Box {}
                }
            }
        }
        for (title in listOf(R.string.bookshelf, R.string.discovery, R.string.rss, R.string.my)) {
            compose.onNodeWithText(context.getString(title)).assertIsDisplayed()
        }
        for ((destination, title) in
            listOf(
                MainDestination.My to R.string.my,
                MainDestination.Rss to R.string.rss,
                MainDestination.Explore to R.string.discovery,
                MainDestination.Bookshelf to R.string.bookshelf,
            )) {
            compose
                .onNodeWithContentDescription(context.getString(title))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .performClick()
                .assertIsSelected()
            compose.onNodeWithText(context.getString(title)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(destination, clicked) }
        }
    }

    @Test
    fun offscreenPagerInputCannotStealTheSelectedTab() {
        val state = mutableStateOf(MainUiState())
        val inputFocus = FocusRequester()
        val select: (MainDestination) -> Unit = { destination ->
            state.value = state.value.copy(selectedDestination = destination)
        }
        compose.setContent {
            LegadoComposeTheme {
                MainScreen(state.value, 0, emptyMap(), false, select) {
                    MainDestinationPager(state.value, select) { destination, _ ->
                        if (destination == MainDestination.Explore) {
                            OutlinedTextField("", {}, Modifier.focusRequester(inputFocus))
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertFalse("An offscreen input must not acquire focus", inputFocus.requestFocus())
        }
        compose.onNodeWithContentDescription(context.getString(R.string.discovery)).performClick()
        compose.runOnIdle { assertTrue(inputFocus.requestFocus()) }
        compose.onNodeWithContentDescription(context.getString(R.string.my)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(MainDestination.My, state.value.selectedDestination)
            assertFalse("The previous page must no longer acquire focus", inputFocus.requestFocus())
        }
        compose.onNodeWithContentDescription(context.getString(R.string.my)).assertIsSelected()
    }

    @Test
    fun hidingTabsPreservesMySelectionAndUpdatesBadge() {
        val state = mutableStateOf(MainUiState(selectedDestination = MainDestination.My))
        val badge = mutableStateOf(3)
        compose.setContent {
            LegadoComposeTheme {
                MainScreen(state.value, badge.value, emptyMap(), false, {}) { Box {} }
            }
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.bookshelf))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3"))
        compose.runOnIdle {
            state.value = state.value.withVisibleDestinations(false, false)
            badge.value = 0
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.discovery))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.rss)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.my)).assertIsSelected()
        compose
            .onNodeWithContentDescription(context.getString(R.string.bookshelf))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
