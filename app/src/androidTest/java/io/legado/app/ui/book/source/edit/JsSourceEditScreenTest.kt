package io.legado.app.ui.book.source.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class JsSourceEditScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failureOffersRetryAndCancel() {
        var retries = 0
        var cancellations = 0
        compose.setContent {
            LegadoComposeTheme {
                JsSourceEditScreen(
                    state = JsSourceEditState(loaded = true, error = "parse failed"),
                    onRetry = { retries++ },
                    onCancel = { cancellations++ },
                )
            }
        }
        compose.onNodeWithTag("js-source-error").assertTextEquals("parse failed")
        compose.onNodeWithTag("js-source-retry").performClick()
        compose.onNodeWithTag("js-source-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun acceptedOperationDisablesCancelAndShowsProgress() {
        compose.setContent {
            LegadoComposeTheme {
                JsSourceEditScreen(
                    state = JsSourceEditState(busy = true),
                    onRetry = {},
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithTag("js-source-working").assertIsDisplayed()
        compose.onNodeWithTag("js-source-cancel").assertIsNotEnabled()
    }
}
