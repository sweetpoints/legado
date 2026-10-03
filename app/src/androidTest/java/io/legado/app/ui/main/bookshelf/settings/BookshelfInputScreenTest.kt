package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfInputScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun importInputSurvivesRestoreAndHasFileCancelAndConfirmActions() {
        var state by mutableStateOf(BookshelfInputState("draft"))
        var confirms = 0
        var cancels = 0
        var files = 0
        val tester = StateRestorationTester(compose)
        tester.setContent {
            LegadoComposeTheme {
                BookshelfInputScreen(
                    state,
                    1,
                    "",
                    { text, start, end -> state = BookshelfInputState(text, start, end) },
                    { confirms++ },
                    { cancels++ },
                    { files++ },
                )
            }
        }
        compose
            .onNodeWithTag("shelf-input-text")
            .performTextReplacement("https://books.example/list.json")
        tester.emulateSavedInstanceStateRestore()
        compose
            .onNodeWithTag("shelf-input-text")
            .assertTextContains("https://books.example/list.json")
        compose.onNodeWithTag("shelf-input-file").performClick()
        compose.onNodeWithTag("shelf-input-confirm").performClick()
        compose.onNodeWithTag("shelf-input-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, files)
            assertEquals(1, confirms)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun exportSummaryAndActualPathRemainVisibleWithoutImportActions() {
        compose.setContent {
            LegadoComposeTheme {
                BookshelfInputScreen(
                    BookshelfInputState("https://result.example/bookshelf.json"),
                    2,
                    "Upload summary",
                    { _, _, _ -> },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Upload summary").assertIsDisplayed()
        compose
            .onNodeWithTag("shelf-input-text")
            .assertTextContains("https://result.example/bookshelf.json")
        compose.onNodeWithTag("shelf-input-file").assertDoesNotExist()
        compose.onNodeWithTag("shelf-input-cancel").assertDoesNotExist()
    }

    @Test
    fun progressDisplaysCurrentCountAndOffersCancellation() {
        var count by mutableIntStateOf(0)
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme { BookshelfAddProgressScreen(count) { cancelled++ } }
        }
        compose.onNodeWithTag("shelf-add-progress").assertTextContains("0", substring = true)
        compose.runOnIdle { count = 5 }
        compose.onNodeWithTag("shelf-add-progress").assertTextContains("5", substring = true)
        compose.onNodeWithTag("shelf-add-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }
}
