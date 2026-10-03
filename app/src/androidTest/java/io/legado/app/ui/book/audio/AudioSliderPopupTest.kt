package io.legado.app.ui.book.audio

import android.view.Gravity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.about.AboutActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioSliderPopupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualPopupReopensAfterDismissAndDisposesWhenActivityIsDestroyed() {
        lateinit var popup: SliderPopup
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                popup = SliderPopup(it, SliderPopup.SPEED)
                popup.showAtLocation(it.window.decorView, Gravity.CENTER, 0, 0)
            }
            compose.waitUntil {
                compose.onAllNodesWithTag("audio-slider-control").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity {
                assertTrue(popup.isFocusable)
                assertFalse(popup.isOutsideTouchable)
                assertTrue((popup.contentView as ComposeView).hasComposition)
                popup.dismiss()
                assertFalse((popup.contentView as ComposeView).hasComposition)
                popup.showAsDropDown(it.window.decorView, 0, 0, Gravity.TOP)
            }
            compose.waitUntil {
                compose.onAllNodesWithTag("audio-slider-control").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertFalse(popup.isShowing)
                assertFalse((popup.contentView as ComposeView).hasComposition)
            }
        }
    }
}
