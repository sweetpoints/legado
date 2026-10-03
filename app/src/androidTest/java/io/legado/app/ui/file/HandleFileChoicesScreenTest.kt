package io.legado.app.ui.file

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.legado.app.data.repository.HandleFileChoice
import io.legado.app.data.repository.HandleFileInput
import io.legado.app.data.repository.HandleFilePending
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HandleFileChoicesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun choicesRetainExactCustomTitleAndValue() {
        val chosen = mutableListOf<Pair<Int, String?>>()
        compose.setContent {
            LegadoComposeTheme {
                HandleFileChoicesScreen(
                    HandleFileChoicesState(
                        loaded = true,
                        input =
                            HandleFileInput(
                                mode = HandleFileContract.FILE,
                                otherActions = listOf(HandleFileChoice(" content://exact ", 42)),
                            ),
                    ),
                    { action, title -> chosen += action to title },
                    { _, _, _ -> },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("handle-file-choice-2").performClick()
        assertEquals(listOf(42 to " content://exact "), chosen)
    }

    @Test
    fun manualFieldPreservesWhitespaceAndReportsSelection() {
        var latest = ""
        compose.setContent {
            LegadoComposeTheme {
                HandleFileChoicesScreen(
                    HandleFileChoicesState(
                        loaded = true,
                        input = HandleFileInput(),
                        phase = "Manual",
                        pending = HandleFilePending(112, "nonce"),
                    ),
                    { _, _ -> },
                    { text, _, _ -> latest = text },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("handle-file-manual-input").performTextInput(" /storage/path ")
        compose.onNodeWithTag("handle-file-manual-input").assertTextContains(" /storage/path ")
        assertEquals(" /storage/path ", latest)
    }

    @Test
    fun acceptedWorkDisablesManualConfirmation() {
        compose.setContent {
            LegadoComposeTheme {
                HandleFileChoicesScreen(
                    HandleFileChoicesState(
                        loaded = true,
                        input = HandleFileInput(),
                        phase = "Manual",
                        busy = true,
                    ),
                    { _, _ -> },
                    { _, _, _ -> },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("handle-file-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("handle-file-manual-input").assertIsNotEnabled()
    }
}
