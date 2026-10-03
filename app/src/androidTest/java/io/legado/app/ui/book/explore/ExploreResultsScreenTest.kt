package io.legado.app.ui.book.explore

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.ExploreResultsCategory
import io.legado.app.data.repository.ExploreResultsCheckpoint
import io.legado.app.data.repository.ExploreResultsRequest
import io.legado.app.data.repository.ExploreResultsRow
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExploreResultsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val request = ExploreResultsRequest("source", "Results", "full query")
    private val state =
        mutableStateOf(
            ExploreResultsState(
                loaded = true,
                checkpoint = ExploreResultsCheckpoint(request, rows = listOf(row("a"))),
            )
        )

    private fun actions(
        category: (ExploreResultsCategory) -> Unit = {},
        detail: (String) -> Unit = {},
        confirmAdd: () -> Unit = {},
        cancelAdd: () -> Unit = {},
        next: () -> Unit = {},
        previous: () -> Unit = {},
        page: (Int) -> Unit = {},
    ) =
        ExploreResultsActions(
            {},
            {},
            category,
            next,
            previous,
            {},
            {},
            page,
            {},
            {},
            {},
            cancelAdd,
            confirmAdd,
            detail,
        )

    private fun show(actions: ExploreResultsActions = actions()) {
        compose.setContent {
            LegadoComposeTheme {
                ExploreResultsScreen(
                    state.value,
                    actions,
                    cover = { _, _ ->
                        Spacer(Modifier.width(72.dp).height(96.dp))
                    },
                )
            }
        }
    }

    @Test
    fun rowDisplaysExactProjectionAndClickUsesStableKey() {
        val keys = mutableListOf<String>()
        state.value = state.value.copy(membership = setOf("Name a-Writer"))
        show(actions(detail = keys::add))
        compose.onNodeWithTag("explore-book-a").assertTextContains("Name a", substring = true)
        compose.onNodeWithTag("explore-in-shelf-a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("explore-book-a").performClick()
        assertEquals(listOf("a"), keys)
    }

    @Test
    fun balancedCategoryRowsPreserveSelectionAndOffscreenItemsAreReachable() {
        val categories = (0..21).map { ExploreResultsCategory("Category $it", "url-$it") }
        state.value =
            state.value.copy(
                showCategories = true,
                checkpoint =
                    state.value.checkpoint!!.copy(
                        categories = categories,
                        selectedCategory = categories[17],
                    ),
            )
        val selected = mutableListOf<ExploreResultsCategory>()
        show(actions(category = selected::add))
        (0..2).forEach { compose.onNodeWithTag("explore-category-row-$it").assertIsDisplayed() }
        compose
            .onNodeWithTag("explore-category-row-2")
            .performScrollToNode(hasTestTag("explore-category-17"))
        compose.onNodeWithTag("explore-category-17").assertIsSelected().performClick()
        assertEquals(listOf(categories[17]), selected)
    }

    @Test
    fun addConfirmationBusyDisablesBothButtonsAndBack() {
        state.value =
            state.value.copy(
                adding = true,
                checkpoint = state.value.checkpoint!!.copy(addRows = listOf(row("a"))),
            )
        show()
        compose.onNodeWithTag("explore-results-confirm-add").assertIsNotEnabled()
        compose.onNodeWithTag("explore-results-cancel-add").assertIsNotEnabled()
        compose.onNodeWithTag("explore-results-back").assertIsNotEnabled()
    }

    @Test
    fun explicitPreviousAndNextActionsRetainSeparateCallbacks() {
        state.value =
            state.value.copy(
                checkpoint = state.value.checkpoint!!.copy(firstPage = 4, hasMore = false)
            )
        val calls = mutableListOf<String>()
        show(actions(next = { calls += "next" }, previous = { calls += "previous" }))
        compose.onNodeWithTag("explore-results-previous").performClick()
        compose.onNodeWithTag("explore-results-next").performClick()
        assertEquals(listOf("previous", "next"), calls)
    }

    @Test
    fun pageInputUsesBoundedNumberAndKeepsConfirmDisabledForEmptyInput() {
        state.value = state.value.copy(pagePicker = 3)
        val pages = mutableListOf<Int>()
        show(actions(page = pages::add))
        compose.onNodeWithTag("explore-results-page-input").performTextReplacement("999")
        assertEquals(listOf(999), pages)
        compose.onNodeWithTag("explore-results-page-input").performTextReplacement("")
        compose.onNodeWithTag("explore-results-confirm-page").assertIsNotEnabled()
    }

    private fun row(id: String) =
        ExploreResultsRow(
            id,
            "url-$id",
            "Name $id",
            "Writer",
            "source",
            null,
            " Intro ",
            "Newest chapter",
            listOf("Kind"),
            "{}",
        )
}
