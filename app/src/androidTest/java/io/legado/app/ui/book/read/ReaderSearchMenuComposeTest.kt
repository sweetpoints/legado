package io.legado.app.ui.book.read

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSearchMenuComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyResultsDisableNavigationButKeepReaderActionsAvailable() {
        val controller =
            ReaderSearchMenuController().apply {
                show()
                chapter("Chapter")
            }
        var results = 0
        var main = 0
        var exit = 0
        compose.setContent {
            LegadoComposeTheme {
                ReaderSearchMenuRoute(
                    controller,
                    Color.White,
                    Color.Black,
                    { _, _ -> },
                    {},
                    {},
                    { results++ },
                    { main++ },
                    { exit++ },
                )
            }
        }
        compose.onNodeWithTag("reader-search-previous").assertIsNotEnabled()
        compose.onNodeWithTag("reader-search-next").assertIsNotEnabled()
        compose.onNodeWithTag("reader-search-left").assertDoesNotExist()
        compose.onNodeWithTag("reader-search-results").performClick()
        compose.onNodeWithTag("reader-search-main").performClick()
        compose.onNodeWithTag("reader-search-exit").performClick()
        assertEquals(1, results)
        assertEquals(1, main)
        assertEquals(1, exit)
        compose.onNodeWithTag("reader-search-info").assertTextContains("Chapter", substring = true)
    }

    @Test
    fun floatingAndPanelArrowsNavigateTheSameBoundedResultSnapshot() {
        val controller =
            ReaderSearchMenuController().apply {
                results(listOf(SearchResult(query = "one"), SearchResult(query = "two")))
                show()
            }
        val visited = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                ReaderSearchMenuRoute(
                    controller,
                    Color.White,
                    Color.Black,
                    { _, _ -> },
                    {},
                    { controller.navigate(it)?.let { item -> visited += item.second } },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("reader-search-right").performClick()
        compose.onNodeWithTag("reader-search-next").performClick()
        compose.onNodeWithTag("reader-search-previous").performClick()
        compose.onNodeWithTag("reader-search-left").performClick()
        assertEquals(listOf(0, 1, 0, 0), visited)
    }

    @Test
    fun animatedExitCompletesOnceAndRetainsFloatingNavigationUntilReaderHidesHost() {
        val controller =
            ReaderSearchMenuController().apply {
                results(listOf(SearchResult()))
                show()
            }
        var completed = 0
        compose.setContent {
            LegadoComposeTheme {
                ReaderSearchMenuRoute(
                    controller,
                    Color.White,
                    Color.Black,
                    { visible, id -> if (!visible && controller.hidden(id)) completed++ },
                    { controller.hide() },
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("reader-search-panel").assertIsDisplayed()
        compose.onNodeWithTag("reader-search-dismiss").performClick()
        compose.waitUntil { !controller.state.value.panelVisible }
        assertEquals(1, completed)
        compose.onNodeWithTag("reader-search-panel").assertDoesNotExist()
        compose.onNodeWithTag("reader-search-left").assertIsDisplayed()
        compose.runOnIdle { controller.show() }
        compose.onNodeWithTag("reader-search-panel").assertIsDisplayed()
        assertEquals(1, completed)
    }

    @Test
    fun completedAnimationWaitsForResumedHostBeforeDispatchingAndDoesNotReplay() {
        val controller = ReaderSearchMenuController().apply { show() }
        lateinit var owner: Owner
        var shown = 0
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ReaderSearchMenuRoute(
                        controller,
                        Color.White,
                        Color.Black,
                        { visible, _ -> if (visible) shown++ },
                        {},
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("reader-search-panel").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, shown) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { shown == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(1, shown) }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }
}
