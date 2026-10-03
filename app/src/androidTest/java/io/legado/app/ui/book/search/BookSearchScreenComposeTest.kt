package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchResult
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSearchScreenComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun result(): BookSearchResult =
        BookSearchResult.from(
            SearchBook(
                bookUrl = "synthetic-url",
                name = "Title",
                author = "Author",
                kind = (1..12).joinToString(",") { "Category $it" },
                intro = "Long description content ".repeat(50),
                latestChapterTitle = "Latest chapter",
            )
        )

    @Test
    fun wrappedLabelsAndThreeLineIntroKeepCoverVerticallyCenteredAndRowClickable() {
        val result = result()
        var clicks = 0
        compose.setContent {
            LegadoComposeTheme {
                Surface(Modifier.width(280.dp)) {
                    BookSearchResultRow(
                        result,
                        onShelf = true,
                        hasRead = false,
                        loadOnlyWifi = false,
                        onClick = { clicks++ },
                        cover = { modifier -> Box(modifier) },
                    )
                }
            }
        }
        val row =
            compose.onNodeWithTag("search-result-${result.id}").fetchSemanticsNode().boundsInRoot
        val cover =
            compose
                .onNodeWithTag("search-cover-${result.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val labels =
            compose
                .onNodeWithTag("search-labels-${result.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(labels.height > 40f)
        assertEquals(row.center.y, cover.center.y, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("search-intro-${result.id}", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(layouts)
            }
        assertEquals(3, layouts.single().lineCount)
        compose.onNodeWithTag("search-result-${result.id}").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun readingMarkerPrecedesTitleInRtlAndCoverRemainsAtLogicalStart() {
        val result = result()
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.width(300.dp)) {
                        BookSearchResultRow(
                            result,
                            onShelf = false,
                            hasRead = true,
                            loadOnlyWifi = false,
                            onClick = {},
                            cover = { modifier -> Box(modifier) },
                        )
                    }
                }
            }
        }
        val marker =
            compose
                .onNodeWithTag("search-read-marker-${result.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val title =
            compose
                .onNodeWithTag("search-title-${result.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val cover =
            compose
                .onNodeWithTag("search-cover-${result.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(marker.left >= title.right)
        assertTrue(cover.left >= marker.right)
    }

    @Test
    fun filterEditorReportsCompleteTextAndConfirmationThroughComposeControls() {
        val state =
            mutableStateOf(
                BookSearchUiState(loading = false, draft = BookSearchDraft(filterDraft = "old"))
            )
        var confirmed = ""
        compose.setContent {
            LegadoComposeTheme {
                BookSearchScreen(
                    state.value,
                    BookSearchActions(
                        filter = { text, selection ->
                            state.value =
                                state.value.copy(
                                    draft =
                                        state.value.draft.copy(
                                            filterDraft = text,
                                            filterSelection = selection,
                                        )
                                )
                        },
                        confirmFilter = { confirmed = state.value.draft.filterDraft.orEmpty() },
                    ),
                )
            }
        }
        compose.onNodeWithTag("search-filter-input").performTextReplacement(" term\nsecond ")
        compose.onNodeWithTag("search-filter-input").assertTextEquals(" term\nsecond ")
        compose.onNodeWithTag("search-filter-confirm").performClick()
        assertEquals(" term\nsecond ", confirmed)
    }

    @Test
    fun menuSelectedAndAvailableGroupsDispatchTheirExactNames() {
        var selected = ""
        compose.setContent {
            LegadoComposeTheme {
                BookSearchScreen(
                    BookSearchUiState(
                        loading = false,
                        draft = BookSearchDraft(scope = "Group A"),
                        groups = listOf("Group A", "Group B"),
                    ),
                    BookSearchActions(group = { selected = it }),
                )
            }
        }
        compose.onNodeWithTag("search-menu").performClick()
        compose.onNodeWithText("Group A").assertIsDisplayed()
        compose.onNodeWithText("Group B").performClick()
        assertEquals("Group B", selected)
    }
}
