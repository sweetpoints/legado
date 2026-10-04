package io.legado.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import io.legado.app.R
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
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
                    Box(Modifier.fillMaxSize().testTag("main-content"))
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
        val top = view.rootWindowInsets
            .getInsets(android.view.WindowInsets.Type.statusBars()).top
        assertTrue("The fixture must expose a real status bar inset", top > 0)
        val bounds = compose.onNodeWithTag("main-content").fetchSemanticsNode().boundsInRoot
        assertTrue("Content starts at ${bounds.top}, status bar ends at $top", bounds.top >= top)
    }

    @Test
    fun selectingATabEmitsItsStableDestination() {
        var clicked: MainDestination? = null
        val state = mutableStateOf(MainUiState())
        compose.setContent {
            LegadoComposeTheme {
                MainScreen(state.value, 0, emptyMap(), false, {
                    clicked = it
                    state.value = state.value.copy(selectedDestination = it)
                }) { Box {} }
            }
        }
        for ((destination, title) in listOf(
            MainDestination.My to R.string.my,
            MainDestination.Rss to R.string.rss,
            MainDestination.Explore to R.string.discovery,
            MainDestination.Bookshelf to R.string.bookshelf,
        )) {
            compose.onNodeWithContentDescription(context.getString(title)).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(destination, clicked) }
        }
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
        compose.onNodeWithText("3").assertExists()
        compose.runOnIdle {
            state.value = state.value.withVisibleDestinations(false, false)
            badge.value = 0
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.discovery))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.rss)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.my)).assertIsSelected()
        compose.onNodeWithText("3").assertDoesNotExist()
    }
}
