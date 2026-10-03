package io.legado.app.help.gsyVideo

import android.view.WindowManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.about.AboutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoPlaybackDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun networkWindowCancellationDoesNotStartPlayback() {
        var confirmations = 0
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                VideoNetworkDialog(activity) { confirmations += 1 }.show()
            }
            compose.onNodeWithTag("video-network-cancel").performClick()
            compose.runOnIdle { assertEquals(0, confirmations) }
            scenario.onActivity { activity ->
                VideoNetworkDialog(activity) { confirmations += 1 }.show()
            }
            compose.onNodeWithTag("video-network-confirm").performClick()
            compose.runOnIdle { assertEquals(1, confirmations) }
        }
    }

    @Test
    fun gestureWindowCannotConsumeMediaTouchOrFocus() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = VideoFeedbackDialog(activity)
                dialog.feedback = VideoFeedbackState("快进 01:00 / 02:00", .5f)
                dialog.showOver(activity.window.decorView)
                assertTrue(dialog.isShowing)
                val flags = requireNotNull(dialog.window).attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                dialog.dismiss()
                assertFalse(dialog.isShowing)
            }
        }
    }
}
