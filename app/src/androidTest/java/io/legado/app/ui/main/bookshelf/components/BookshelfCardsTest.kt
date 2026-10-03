package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfCardsTest {
    @get:Rule val compose = createComposeRule()
    private val model =
        BookshelfBookCardModel(
            "book",
            "Title",
            "Author",
            "Reading",
            "Latest",
            12,
            true,
            readProgress = .5f,
            latestUpdateLabel = "one hour",
        )

    @Test
    fun listAndCompactExposeAllMetadataAndClickLongClickContracts() {
        var opens = 0
        var infos = 0
        compose.setContent {
            LegadoComposeTheme {
                Column {
                    listOf(BookshelfCardLayout.List, BookshelfCardLayout.Compact).forEachIndexed {
                        index,
                        layout ->
                        BookshelfBookCard(
                            model.copy(key = "book$index"),
                            layout,
                            { opens++ },
                            { infos++ },
                            Modifier.width(360.dp),
                        ) {
                            Box(it.testTag("cover-$index"))
                        }
                    }
                }
            }
        }
        compose
            .onNodeWithTag("shelf-book-book0")
            .assertTextContains("Title")
            .assertTextContains("Author")
            .assertTextContains("Reading")
            .assertTextContains("Latest")
            .assertTextContains("one hour")
            .performClick()
        compose.onNodeWithTag("shelf-book-book1").assertTextContains("50%").performTouchInput {
            longClick()
        }
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, infos)
        }
    }

    @Test
    fun updatingReplacesUnreadAndCompletedRefreshRestoresBadge() {
        var card by mutableStateOf(model.copy(updating = true))
        compose.setContent {
            LegadoComposeTheme {
                BookshelfBookCard(card, BookshelfCardLayout.Grid, {}, {}, Modifier.width(140.dp)) {
                    Box(it)
                }
            }
        }
        compose.onNodeWithTag("shelf-updating-book", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("shelf-unread-book", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { card = card.copy(updating = false) }
        compose.onNodeWithTag("shelf-updating-book", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shelf-unread-book", useUnmergedTree = true).assertExists()
    }

    @Test
    fun gridTitlesSupportBelowHiddenOverlayAndProgressHasNoPercentText() {
        var title by mutableStateOf(BookshelfGridTitle.Below)
        compose.setContent {
            LegadoComposeTheme {
                BookshelfBookCard(
                    model,
                    BookshelfCardLayout.Grid,
                    {},
                    {},
                    Modifier.width(140.dp),
                    title,
                ) {
                    Box(it)
                }
            }
        }
        compose.onNodeWithTag("shelf-name-book", useUnmergedTree = true).assertTextEquals("Title")
        compose.onNodeWithTag("shelf-percent-book", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shelf-progress-book", useUnmergedTree = true).assertExists()
        compose.runOnIdle { title = BookshelfGridTitle.Hidden }
        compose.onNodeWithTag("shelf-name-book", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { title = BookshelfGridTitle.Overlay }
        compose.onNodeWithTag("shelf-name-book", useUnmergedTree = true).assertTextEquals("Title")
    }

    @Test
    fun groupCoverUsesDistinctOpenAndEditActionsWithoutBookBadges() {
        var opens = 0
        var edits = 0
        compose.setContent {
            LegadoComposeTheme {
                BookshelfGroupCard(
                    BookshelfGroupCardModel(8, "Favorites"),
                    BookshelfCardLayout.Grid,
                    { opens++ },
                    { edits++ },
                    Modifier.width(140.dp),
                ) {
                    Box(it.testTag("group-cover"))
                }
            }
        }
        compose.onNodeWithTag("shelf-group-8").assertTextContains("Favorites").performClick()
        compose.onNodeWithTag("shelf-group-8").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, edits)
        }
    }

    @Test
    fun hiddenHeaderHasNoContentAndRecentSupportsReadingAndInfo() {
        var header by mutableStateOf(BookshelfHeaderModel())
        var opens = 0
        var infos = 0
        compose.setContent {
            LegadoComposeTheme { BookshelfHeader(header, { opens++ }, { infos++ }) }
        }
        compose.onNodeWithTag("shelf-stats", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shelf-continue").assertDoesNotExist()
        compose.runOnIdle {
            header =
                BookshelfHeaderModel(10 to 3, model.copy(currentChapter = "", readProgress = null))
        }
        compose.onNodeWithTag("shelf-stats", useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag("shelf-continue")
            .assertTextContains("Title")
            .assertTextContains("0%")
            .performClick()
        compose.onNodeWithTag("shelf-continue").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, infos)
        }
    }
}
