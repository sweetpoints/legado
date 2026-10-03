package io.legado.app.ui.widget.dialog

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import io.legado.app.R
import io.legado.app.ui.about.AboutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class WaitDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun textChangesBeforeAndAfterShowRetainTheFluentApi() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: WaitDialog
            scenario.onActivity {
                dialog = WaitDialog(it)
                assertSame(dialog, dialog.setText("Preparing wait fixture"))
                dialog.show()
            }
            compose.onNodeWithTag("wait-dialog-message").assertTextEquals("Preparing wait fixture")
            scenario.onActivity { dialog.setText("Updated wait fixture") }
            compose.onNodeWithTag("wait-dialog-message").assertTextEquals("Updated wait fixture")
            var localized = ""
            scenario.onActivity {
                localized = it.getString(R.string.loading)
                assertSame(dialog, dialog.setText(R.string.loading))
            }
            compose.onNodeWithTag("wait-dialog-message").assertTextEquals(localized)
            scenario.onActivity { dialog.dismiss() }
            compose.onNodeWithTag("wait-dialog-content").assertDoesNotExist()
        }
    }

    @Test
    fun dismissDestroysTheOldLifecycleAndTheSameDialogCanBeShownAgain() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: WaitDialog
            lateinit var previousLifecycle: Lifecycle
            scenario.onActivity {
                dialog = WaitDialog(it).setText("First wait fixture")
                dialog.show()
                previousLifecycle = dialog.lifecycle
                assertEquals(Lifecycle.State.RESUMED, previousLifecycle.currentState)
            }
            compose.onNodeWithTag("wait-dialog-message").assertTextEquals("First wait fixture")
            scenario.onActivity {
                dialog.dismiss()
                assertEquals(Lifecycle.State.DESTROYED, previousLifecycle.currentState)
            }
            compose.onNodeWithTag("wait-dialog-content").assertDoesNotExist()
            scenario.onActivity {
                dialog.setText("Second wait fixture").show()
                assertNotSame(previousLifecycle, dialog.lifecycle)
                assertEquals(Lifecycle.State.RESUMED, dialog.lifecycle.currentState)
            }
            compose.onNodeWithTag("wait-dialog-message").assertTextEquals("Second wait fixture")
            scenario.onActivity { dialog.dismiss() }
        }
    }
}
