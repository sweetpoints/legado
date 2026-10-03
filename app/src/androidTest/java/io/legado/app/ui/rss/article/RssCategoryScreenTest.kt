package io.legado.app.ui.rss.article

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssCategoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private var state by
        mutableStateOf(
            RssCategoryState(
                loaded = true,
                sourceName = "Source",
                request = RssCategoryRequest("source"),
                tabs = List(25) { RssCategoryTab(it, "Category $it", "url-$it") },
                canSearch = true,
            )
        )
    private var landscape by mutableStateOf(false)
    private var submitted: String? = null

    private fun show() {
        compose.setContent {
            LegadoComposeTheme {
                RssCategoryScreen(
                    state,
                    landscape,
                    RssCategoryActions(
                        {},
                        { state = state.copy(selected = it) },
                        { state = state.copy(menuOpen = it) },
                        { state = state.copy(searchOpen = it) },
                        { text, start, end ->
                            state =
                                state.copy(draft = text, selectionStart = start, selectionEnd = end)
                        },
                        { submitted = state.draft },
                        {},
                        {},
                        {},
                        {},
                        {},
                    ),
                ) { index, active ->
                    Text(
                        "Page $index",
                        Modifier.testTag(
                            if (active) "active-page-$index" else "hidden-page-$index"
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun manyCategoriesUseThreeRowsPortraitAndTwoRowsLandscapeAndRevealSelectedTab() {
        show()
        compose.onNodeWithTag("rss-category-tab-row-2").assertExists()
        compose.runOnIdle { state = state.copy(selected = 24) }
        compose.onNodeWithTag("rss-category-tab-24").assertIsDisplayed()
        compose.onNodeWithTag("active-page-24").assertIsDisplayed()
        compose.runOnIdle { landscape = true }
        compose.onNodeWithTag("rss-category-tab-row-2").assertDoesNotExist()
        compose.onNodeWithTag("rss-category-tab-row-1").assertExists()
        compose.onNodeWithTag("rss-category-tab-24").assertIsDisplayed()
    }

    @Test
    fun clickingTabAndSwipingPagerKeepSelectionAndVisiblePageTogether() {
        state = state.copy(tabs = state.tabs.take(3))
        show()
        compose.onNodeWithTag("rss-category-tab-1").performClick()
        compose.onNodeWithTag("active-page-1").assertIsDisplayed()
        compose.onNodeWithTag("rss-category-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2, state.selected)
        compose.onNodeWithTag("active-page-2").assertIsDisplayed()
    }

    @Test
    fun searchUsesExactDraftIncludingEmptyQueryAndRestoresSelection() {
        state =
            state.copy(
                draft = "Exact query",
                selectionStart = 2,
                selectionEnd = 7,
                searchOpen = true,
            )
        show()
        compose.onNodeWithTag("rss-category-query").assertTextContains("Exact query")
        compose.onNodeWithTag("rss-category-query").performTextReplacement("")
        compose.onNodeWithTag("rss-category-submit").performClick()
        assertEquals("", submitted)
        assertFalse(state.searchOpen)
    }

    @Test
    fun singleSearchCategoryHidesTabsAndUsesExactQueryForTitle() {
        state =
            state.copy(
                tabs = listOf(RssCategoryTab(0, "搜索", "url")),
                request = RssCategoryRequest("source", query = "Exact"),
            )
        show()
        compose.onNodeWithTag("rss-category-tab-row-0").assertDoesNotExist()
        compose.onNodeWithText("Exact").assertIsDisplayed()
    }
}
