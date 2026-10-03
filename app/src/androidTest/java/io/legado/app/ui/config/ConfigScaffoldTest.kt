package io.legado.app.ui.config

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConfigScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queryEditsOnlyDraftAndImeDispatchesTrimmedSearchWithoutNavigating() {
        var searching by mutableStateOf(false)
        var query by mutableStateOf(TextFieldValue())
        val searches = mutableListOf<String>(); var backs = 0
        compose.setContent { LegadoComposeTheme {
            ConfigScaffold("Settings", searching, query, { query = it }, { searching = it }, { searches += it }, { backs++ }) { Text("Owned page") }
        } }
        compose.onNodeWithTag("config-title").assertTextEquals("Settings")
        compose.onNodeWithTag("config-open-search").performClick()
        compose.onNodeWithTag("config-search-input").performTextReplacement("  Automatic backup  ")
        assertTrue(searches.isEmpty())
        compose.onNodeWithTag("config-search-input").performImeAction()
        assertEquals(listOf("Automatic backup"), searches); assertEquals(0, backs)
        compose.onNodeWithText("Owned page").assertIsDisplayed()
    }

    @Test fun emptySearchDoesNotDispatchAndToolbarBackClosesSearchBeforeNavigating() {
        var searching by mutableStateOf(true)
        var query by mutableStateOf(TextFieldValue("   "))
        var searches = 0; var backs = 0
        compose.setContent { LegadoComposeTheme {
            ConfigScaffold("Settings", searching, query, { query = it }, { searching = it }, { searches++ }, { backs++ }) {}
        } }
        compose.onNodeWithTag("config-submit-search").performClick(); assertEquals(0, searches)
        compose.onNodeWithTag("config-back").performClick(); assertFalse(searching); assertEquals(0, backs)
        compose.onNodeWithTag("config-title").assertIsDisplayed()
        compose.onNodeWithTag("config-back").performClick(); assertEquals(1, backs)
    }

    @Test fun restoredDraftSelectionAndChangingPageTitleRemainOwnedByCaller() {
        var searching by mutableStateOf(true)
        var title by mutableStateOf("Backup")
        var query by mutableStateOf(TextFieldValue("restored query", TextRange(2, 7)))
        compose.setContent { LegadoComposeTheme {
            ConfigScaffold(title, searching, query, { query = it }, { searching = it }, {}, {}) {}
        } }
        compose.onNodeWithTag("config-search-input").assertTextEquals("restored query")
        compose.runOnIdle { assertEquals(TextRange(2, 7), query.selection); title = "Theme"; searching = false }
        compose.onNodeWithTag("config-title").assertTextEquals("Theme")
        compose.runOnIdle { searching = true }
        compose.onNodeWithTag("config-search-input").assertTextEquals("restored query")
    }
}
