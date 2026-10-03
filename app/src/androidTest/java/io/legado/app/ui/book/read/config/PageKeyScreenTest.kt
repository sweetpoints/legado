package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.legado.app.data.preferences.PageKeyValues
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PageKeyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun textEditingResetAndConfirmEmitCallbacks() {
        val edits = mutableListOf<Pair<PageKeyField, String>>()
        var resets = 0
        var confirms = 0
        compose.setContent {
            LegadoComposeTheme {
                PageKeyScreen(
                    PageKeyUiState(PageKeyValues("19", "20")),
                    Color.White,
                    { field, value -> edits += field to value },
                    { _, _ -> },
                    { resets++ },
                    { confirms++ },
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
        compose.onNodeWithTag("page-key-previous").performTextReplacement("19,24")
        compose.onNodeWithTag("page-key-next").performTextReplacement("20,25")
        compose.onNodeWithTag("page-key-reset").performClick()
        compose.onNodeWithTag("page-key-confirm").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(PageKeyField.PREVIOUS to "19,24", PageKeyField.NEXT to "20,25"),
                edits,
            )
            assertEquals(1, resets)
            assertEquals(1, confirms)
        }
    }

    @Test
    fun finishedDisablesEditingAndButtons() {
        compose.setContent {
            LegadoComposeTheme {
                PageKeyScreen(
                    PageKeyUiState(finished = true),
                    Color.Black,
                    { _, _ -> },
                    { _, _ -> },
                    {},
                    {},
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
        compose.onNodeWithTag("page-key-previous").assertIsNotEnabled()
        compose.onNodeWithTag("page-key-next").assertIsNotEnabled()
        compose.onNodeWithTag("page-key-reset").assertIsNotEnabled()
        compose.onNodeWithTag("page-key-confirm").assertIsNotEnabled()
    }
}
