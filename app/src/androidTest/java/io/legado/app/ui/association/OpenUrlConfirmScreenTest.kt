package io.legado.app.ui.association

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OpenUrlConfirmScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cancelDoesNotOpenTheUri() {
        var opens = 0
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                OpenUrlConfirmScreen(OpenUrlConfirmUiState("legado://target"),
                    { opens++ }, { closes++ }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("open-url-cancel").performClick()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, closes) }
        compose.onNodeWithTag("open-url-confirm").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun deletingASourceRequiresAnExplicitConfirmation() {
        val state = mutableStateOf(OpenUrlConfirmUiState("legado://target", sourceName = "My source"))
        var deletes = 0
        compose.setContent {
            LegadoComposeTheme {
                OpenUrlConfirmScreen(state.value, {}, {}, {},
                    { state.value = state.value.copy(showDeleteConfirmation = true) },
                    { deletes++; state.value = state.value.copy(showDeleteConfirmation = false) },
                    { state.value = state.value.copy(showDeleteConfirmation = false) })
            }
        }
        compose.onNodeWithTag("open-url-menu").performClick()
        compose.onNodeWithText(context.getString(R.string.delete_source)).performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithTag("open-url-menu").performClick()
        compose.onNodeWithText(context.getString(R.string.delete_source)).performClick()
        compose.onNodeWithTag("open-url-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun busyStateDisablesDuplicateSourceAndOpenActions() {
        compose.setContent {
            LegadoComposeTheme {
                OpenUrlConfirmScreen(OpenUrlConfirmUiState("legado://target", isWorking = true),
                    {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("open-url-menu").assertIsNotEnabled()
        compose.onNodeWithTag("open-url-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("open-url-progress").assertExists()
    }

    @Test fun sourceFailureIsVisibleAndSourceActionsCanBeRetried() {
        var disables = 0
        compose.setContent {
            LegadoComposeTheme {
                OpenUrlConfirmScreen(OpenUrlConfirmUiState("legado://target", error = "database unavailable"),
                    {}, {}, { disables++ }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("open-url-error").assertExists()
        compose.onNodeWithTag("open-url-menu").performClick()
        compose.onNodeWithText(context.getString(R.string.disable_source)).performClick()
        compose.runOnIdle { assertEquals(1, disables) }
    }
}
