package io.legado.app.ui.book.import.local

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalImportComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun directoryAndShelfActionsStayDistinctFromSelectionAndImportProgress() {
        var clicked: String? = null
        var search = ""
        var all: Boolean? = null
        compose.setContent {
            LegadoComposeTheme {
                LocalImportScreen(
                    LocalImportUiState(
                        loading = false,
                        rows =
                            listOf(
                                LocalImportRow("directory", "Directory", true, 0, 0, false),
                                LocalImportRow(
                                    "shelf",
                                    "Already imported.epub",
                                    false,
                                    100,
                                    0,
                                    true,
                                ),
                                LocalImportRow("new", "New.mobi", false, 200, 0, false),
                            ),
                    ),
                    {},
                    { clicked = it },
                    { search = it },
                    {},
                    {},
                    {},
                    {},
                    { all = it },
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Directory").performClick()
        assertEquals("directory", clicked)
        compose.onNodeWithText("Already imported.epub").performClick()
        assertEquals("shelf", clicked)
        compose.onNodeWithTag("local-import-select-new").performClick()
        assertEquals("new", clicked)
        compose.onNodeWithTag("local-import-all").performClick()
        assertEquals(true, all)
        compose.onNodeWithTag("local-import-search").performTextInput("mobi")
        assertEquals("mobi", search)
        compose.onNodeWithTag("local-import-add").assertIsNotEnabled()
    }
}
