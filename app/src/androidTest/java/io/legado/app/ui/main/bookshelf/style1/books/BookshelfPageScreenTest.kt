package io.legado.app.ui.main.bookshelf.style1.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.BookshelfPageSettings
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfPageScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun state() =
        BookshelfPageUiState(
            BookshelfPageParameters(groupId = 8),
            loading = false,
            entries =
                (0..59).map {
                    BookshelfPageEntry(
                        "book$it",
                        "Title $it",
                        "Author",
                        "Reading",
                        "Latest",
                        null,
                        null,
                        0,
                        false,
                        false,
                        null,
                        null,
                    )
                },
        )

    private fun show(
        state: () -> BookshelfPageUiState,
        refresh: () -> Unit = {},
        open: (String) -> Unit = {},
        info: (String) -> Unit = {},
        scrolled: (Int) -> Unit = {},
        retry: () -> Unit = {},
    ) {
        compose.setContent {
            LegadoComposeTheme {
                BookshelfPageScreen(
                    state(),
                    refresh,
                    open,
                    info,
                    scrolled,
                    retry,
                    Modifier.width(360.dp).height(520.dp),
                ) { entry, modifier ->
                    Box(modifier.testTag("page-cover-${entry.key}"))
                }
            }
        }
    }

    @Test
    fun standardCompactAndGridLayoutsKeepBookActionsAndExpectedCoverSizes() {
        var state by mutableStateOf(state())
        val opens = mutableListOf<String>()
        val infos = mutableListOf<String>()
        show({ state }, open = { opens += it }, info = { infos += it })
        compose
            .onNodeWithTag("page-cover-book0", useUnmergedTree = true)
            .assertWidthIsEqualTo(72.dp)
            .assertHeightIsEqualTo(96.dp)
        compose.onNodeWithTag("shelf-book-book0").performClick()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(layout = 1)) }
        compose
            .onNodeWithTag("page-cover-book0", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(64.dp)
        compose.onNodeWithTag("shelf-book-book0").performTouchInput { longClick() }
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(layout = 3)) }
        compose.onNodeWithTag("bookshelf-page-grid").assertExists()
        val first = compose.onNodeWithTag("shelf-book-book0").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("shelf-book-book2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, third.top, 1f)
        compose.runOnIdle {
            assertEquals(listOf("book0"), opens)
            assertEquals(listOf("book0"), infos)
        }
    }

    @Test
    fun fastScrollerActuallyReachesLastBookInListAndGrid() {
        var state by
            mutableStateOf(state().copy(settings = BookshelfPageSettings(fastScroller = true)))
        show({ state })
        compose.onNodeWithTag("bookshelf-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            assertTrue(it(1f))
        }
        compose.onNodeWithTag("shelf-book-book59").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(layout = 3)) }
        compose.onNodeWithTag("bookshelf-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            assertTrue(it(1f))
        }
        compose.onNodeWithTag("shelf-book-book59").assertIsDisplayed()
    }

    @Test
    fun savedScrollSurvivesTemporaryEmptyLoadingBeforeDataReturns() {
        var state by mutableStateOf(state())
        val tester = StateRestorationTester(compose)
        tester.setContent {
            LegadoComposeTheme {
                BookshelfPageScreen(
                    state,
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.width(360.dp).height(520.dp),
                ) { _, modifier ->
                    Box(modifier)
                }
            }
        }
        compose
            .onNodeWithTag("bookshelf-page-list")
            .performScrollToNode(hasTestTag("shelf-book-book40"))
        compose.onNodeWithTag("shelf-book-book40").assertIsDisplayed()
        val loaded = state
        compose.runOnIdle { state = state.copy(entries = emptyList(), loading = true) }
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bookshelf-page-loading").assertExists()
        compose.onNodeWithTag("bookshelf-page-list").assertDoesNotExist()
        compose.runOnIdle { state = loaded }
        compose.onNodeWithTag("shelf-book-book40").assertIsDisplayed()
    }

    @Test
    fun pullRefreshRespectsGroupRefreshPreferenceAndEmptyBooks() {
        var state by
            mutableStateOf(
                state()
                    .copy(parameters = BookshelfPageParameters(groupId = 8, enableRefresh = false))
            )
        var refreshes = 0
        show({ state }, refresh = { refreshes++ })
        compose.onNodeWithTag("bookshelf-page-8").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(0, refreshes)
            state = state.copy(parameters = state.parameters.copy(enableRefresh = true))
        }
        compose.onNodeWithTag("bookshelf-page-8").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state = state.copy(entries = emptyList())
        }
        compose.onNodeWithTag("bookshelf-page-8").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun metadataUpdatesKeepVisiblePositionWithoutItemMovementAnimation() {
        var state by mutableStateOf(state())
        show({ state })
        compose
            .onNodeWithTag("bookshelf-page-list")
            .performScrollToNode(hasTestTag("shelf-book-book30"))
        val before = compose.onNodeWithTag("shelf-book-book30").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            state =
                state.copy(
                    entries =
                        state.entries.map {
                            if (it.key == "book30") it.copy(currentChapter = "New chapter") else it
                        }
                )
        }
        compose.onNodeWithTag("shelf-book-book30").assertTextContains("New chapter")
        val after = compose.onNodeWithTag("shelf-book-book30").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, .1f)
    }

    @Test
    fun gotoTopAfterLateLoadReachesFirstBookAndAcknowledgesExactRequest() {
        var state by mutableStateOf(state())
        val requests = mutableListOf<Int>()
        show({ state }, scrolled = { requests += it })
        compose
            .onNodeWithTag("bookshelf-page-list")
            .performScrollToNode(hasTestTag("shelf-book-book40"))
        compose.runOnIdle { state = state.copy(loading = true, scrollRequest = 7) }
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            state = state.copy(loading = false, settings = state.settings.copy(eInk = true))
        }
        compose.onNodeWithTag("shelf-book-book0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(7), requests) }
    }
}
