package io.legado.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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
