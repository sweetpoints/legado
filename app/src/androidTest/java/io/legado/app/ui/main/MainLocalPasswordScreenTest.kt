package io.legado.app.ui.main

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainLocalPasswordScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun confirmationPassesTheEditedDraftOnlyAfterTheUserAccepts() {
        val accepted = mutableListOf<String>()
        var skipped = 0
        compose.setContent {
            LegadoComposeTheme {
                MainLocalPasswordScreen(accepted::add, { skipped++ }, {})
            }
        }
        compose.onNodeWithTag("main-local-password-input").performTextReplacement("synthetic draft")
        compose.runOnIdle { assertTrue(accepted.isEmpty()) }
        compose.onNodeWithTag("main-local-password-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf("synthetic draft"), accepted)
            assertEquals(0, skipped)
        }
    }

    @Test
    fun cancelSkipsThePasswordInsteadOfSavingTheTypedDraft() {
        val accepted = mutableListOf<String>()
        var skipped = 0
        var dismissed = 0
        compose.setContent {
            LegadoComposeTheme {
                MainLocalPasswordScreen(accepted::add, { skipped++ }, { dismissed++ })
            }
        }
        compose.onNodeWithTag("main-local-password-input").performTextReplacement("synthetic draft")
        compose.onNodeWithTag("main-local-password-skip").performClick()
        compose.runOnIdle {
            assertTrue(accepted.isEmpty())
            assertEquals(1, skipped)
            assertEquals(0, dismissed)
        }
    }
}
