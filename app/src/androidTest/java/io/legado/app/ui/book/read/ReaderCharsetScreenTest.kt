package io.legado.app.ui.book.read

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderCharsetScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun customCharsetDraftSurvivesRestorationAndConfirmationUsesEditedValue() {
        val restoration = StateRestorationTester(compose)
        var confirmed: String? = null
        restoration.setContent {
            LegadoComposeTheme { ReaderCharsetScreen("UTF-8", { confirmed = it }, {}) }
        }
        compose.onNodeWithTag("reader-charset").performTextReplacement("UTF-32")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reader-charset").assertTextContains("UTF-32")
        compose.onNodeWithTag("reader-charset-confirm").performClick()
        compose.runOnIdle { assertEquals("UTF-32", confirmed) }
    }
}
