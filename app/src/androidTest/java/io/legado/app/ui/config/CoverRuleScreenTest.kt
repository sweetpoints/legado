package io.legado.app.ui.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.CoverRuleDraft
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoverRuleScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editingAndButtonsEmitCallbacks() {
        var enabled: Boolean? = null
        var url: String? = null
        var rule: String? = null
        var saves = 0
        var deletes = 0
        var cancels = 0
        compose.setContent {
            LegadoComposeTheme {
                CoverRuleScreen(
                    CoverRuleUiState(CoverRuleDraft(false, "old", "old rule"), isLoading = false),
                    { enabled = it },
                    { url = it },
                    { rule = it },
                    { saves++ },
                    { deletes++ },
                    { cancels++ },
                    {},
                    Modifier.heightIn(max = 600.dp),
                )
            }
        }
        compose.onNodeWithTag("cover-rule-enabled").assertIsOff().performClick()
        compose.onNodeWithTag("cover-rule-search-url").performTextReplacement("new URL")
        compose.onNodeWithTag("cover-rule-expression").performTextReplacement("new rule")
        compose.onNodeWithTag("cover-rule-save").performClick()
        compose.onNodeWithTag("cover-rule-delete").performClick()
        compose.onNodeWithTag("cover-rule-cancel").performClick()
        compose.runOnIdle {
            assertEquals(true, enabled)
            assertEquals("new URL", url)
            assertEquals("new rule", rule)
            assertEquals(1, saves)
            assertEquals(1, deletes)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun loadingDisablesEditingAndPersistenceButAllowsCancel() {
        var cancels = 0
        compose.setContent {
            LegadoComposeTheme {
                CoverRuleScreen(
                    CoverRuleUiState(),
                    {},
                    {},
                    {},
                    {},
                    {},
                    { cancels++ },
                    {},
                    Modifier.heightIn(max = 600.dp),
                )
            }
        }
        compose.onNodeWithTag("cover-rule-search-url").assertIsNotEnabled()
        compose.onNodeWithTag("cover-rule-expression").assertIsNotEnabled()
        compose.onNodeWithTag("cover-rule-save").assertIsNotEnabled()
        compose.onNodeWithTag("cover-rule-delete").assertIsNotEnabled()
        compose.onNodeWithTag("cover-rule-progress").assertExists()
        compose.onNodeWithTag("cover-rule-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun validationAndErrorAppearAndRetryEmitsCallback() {
        var retries = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            LegadoComposeTheme {
                CoverRuleScreen(
                    CoverRuleUiState(
                        isLoading = false,
                        showValidation = true,
                        error = "read failed",
                    ),
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    { retries++ },
                    Modifier.heightIn(max = 600.dp),
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.cover_rule_required_fields))
            .assertExists()
        compose.onNodeWithText("read failed").assertExists()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
