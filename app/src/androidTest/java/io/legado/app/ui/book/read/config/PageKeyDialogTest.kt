package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PageKeyDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext.defaultSharedPreferences
    private var previous: String? = null
    private var next: String? = null

    @Before fun setUp() {
        previous = prefs.getString(PreferKey.prevKeys, null)
        next = prefs.getString(PreferKey.nextKeys, null)
        prefs.edit { putString(PreferKey.prevKeys, "19"); putString(PreferKey.nextKeys, "20") }
    }
    @After fun tearDown() {
        prefs.edit { putString(PreferKey.prevKeys, previous); putString(PreferKey.nextKeys, next) }
    }

    @Test fun hardwareKeysTargetCurrentFocusAndOnlyConfirmationSaves() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: PageKeyDialog
            scenario.onActivity { dialog = PageKeyDialog(it); dialog.show() }
            compose.onNodeWithTag("page-key-previous").performTextReplacement("19,")
            compose.onNodeWithTag("page-key-next").performClick()
            scenario.onActivity {
                assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
            }
            compose.onNodeWithTag("page-key-next").assertTextContains("20,25")
            assertEquals("20", prefs.getString(PreferKey.nextKeys, null))
            compose.onNodeWithTag("page-key-reset").performClick()
            assertEquals("19", prefs.getString(PreferKey.prevKeys, null))
            compose.onNodeWithTag("page-key-confirm").performClick()
            compose.waitForIdle()
            assertEquals("", prefs.getString(PreferKey.prevKeys, null))
            assertEquals("", prefs.getString(PreferKey.nextKeys, null))
            compose.onNodeWithTag("page-key-confirm").assertDoesNotExist()
        }
    }

    @Test fun dialogBundleRestoresUncommittedDraftAndFocusedFieldWithoutPersisting() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: PageKeyDialog
            lateinit var bundle: Bundle
            scenario.onActivity { dialog = PageKeyDialog(it); dialog.show() }
            compose.onNodeWithTag("page-key-previous").performTextReplacement("19,24")
            compose.onNodeWithTag("page-key-next").performTextReplacement("20,")
            scenario.onActivity { bundle = dialog.onSaveInstanceState(); dialog.dismiss() }
            scenario.onActivity { dialog = PageKeyDialog(it); dialog.onRestoreInstanceState(bundle) }
            compose.onNodeWithTag("page-key-previous").assertTextContains("19,24")
            compose.onNodeWithTag("page-key-next").assertTextContains("20,")
            scenario.onActivity {
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
            }
            compose.onNodeWithTag("page-key-next").assertTextContains("20,25")
            assertEquals("19", prefs.getString(PreferKey.prevKeys, null))
            assertEquals("20", prefs.getString(PreferKey.nextKeys, null))
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test fun sameInstanceShowsWithFreshLifecycleAndRetainsUncommittedDraft() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var dialog: PageKeyDialog
            lateinit var oldLifecycle: Lifecycle
            scenario.onActivity { dialog = PageKeyDialog(it); dialog.show(); oldLifecycle = dialog.lifecycle }
            compose.onNodeWithTag("page-key-previous").performTextReplacement("19,24")
            scenario.onActivity { dialog.dismiss(); assertEquals(Lifecycle.State.DESTROYED, oldLifecycle.currentState) }
            scenario.onActivity { dialog.show(); assertNotSame(oldLifecycle, dialog.lifecycle) }
            compose.onNodeWithTag("page-key-previous").assertTextContains("19,24")
            assertEquals("19", prefs.getString(PreferKey.prevKeys, null))
            scenario.onActivity { dialog.dismiss() }
        }
    }
}
