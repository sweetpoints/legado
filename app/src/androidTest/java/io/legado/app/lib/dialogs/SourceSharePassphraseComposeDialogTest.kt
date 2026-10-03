package io.legado.app.lib.dialogs

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.closeSoftKeyboard
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressBack
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.getClipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SourceSharePassphraseComposeDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun copyUsesGeneratedPassphraseAndClosesAfterTheFieldChanges() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: SourceSharePassphraseComposeDialog
            scenario.onActivity {
                dialog = SourceSharePassphraseComposeDialog(it, "generated phrase")
                dialog.show()
            }

            compose
                .onNodeWithTag("source-share-passphrase-input")
                .assertTextEquals("generated phrase")
                .performTextReplacement("edited phrase")
            compose.onNodeWithTag("source-share-passphrase-input").assertTextEquals("edited phrase")
            compose.onNodeWithTag("source-share-passphrase-copy").performClick()

            scenario.onActivity { activity ->
                assertEquals("generated phrase", activity.getClipText())
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test
    fun backDismissesTheDialogAfterTheKeyboardIsClosed() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: SourceSharePassphraseComposeDialog
            scenario.onActivity {
                dialog = SourceSharePassphraseComposeDialog(it, "generated phrase")
                dialog.show()
            }

            compose.onNodeWithTag("source-share-passphrase-input").closeSoftKeyboard()
            compose.pressBack()

            scenario.onActivity { assertFalse(dialog.isShowing) }
        }
    }
}
