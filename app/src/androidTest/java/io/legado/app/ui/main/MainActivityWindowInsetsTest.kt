package io.legado.app.ui.main

import android.graphics.Color
import android.view.View
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.theme.contrastingForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityWindowInsetsTest {
    @Test
    fun realMainStatusIconsFollowTheComposeSurfaceRatherThanTheOldToolbarColor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val controller =
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(
                    "Status icons must remain readable on the Material3 page surface",
                    contrastingForeground(activity.backgroundColor) == Color.BLACK,
                    controller.isAppearanceLightStatusBars,
                )
            }
        }
    }

    @Test
    fun realMainWindowLeavesInsetsToItsComposeContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val content = decor.findViewById<View>(android.R.id.content)
                assertTrue("The actual Activity content must be laid out", content.width > 0)
                val position = IntArray(2)
                content.getLocationInWindow(position)
                assertEquals("The window must not pre-pad the content horizontally", 0, position[0])
                assertEquals("The window must not pre-pad the content vertically", 0, position[1])
                assertEquals(
                    "Compose must receive the full window width",
                    decor.width,
                    content.width,
                )
                assertEquals(
                    "Compose must receive the full window height",
                    decor.height,
                    content.height,
                )
            }
        }
    }
}
