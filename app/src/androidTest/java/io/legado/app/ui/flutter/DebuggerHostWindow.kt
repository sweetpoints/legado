package io.legado.app.ui.flutter

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** Avoids Espresso's IME result receiver when a recreated Flutter window has no active IME. */
internal fun <A : Activity> hideDebuggerIme(scenario: ActivityScenario<A>, driver: FlutterUiTestDriver) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var root: View
    scenario.onActivity { root = it.window.decorView }
    val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    driver.await("debugger window attached and focused") {
        var ready = false
        instrumentation.runOnMainSync {
            ready = root.isAttachedToWindow && root.hasWindowFocus() && ViewCompat.getRootWindowInsets(root) != null
        }
        ready
    }
    instrumentation.runOnMainSync {
        ViewCompat.setWindowInsetsAnimationCallback(root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations.add(animation)
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) { animations.remove(animation) }
            })
        if (ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            requireNotNull(ViewCompat.getWindowInsetsController(root)).hide(WindowInsetsCompat.Type.ime())
        }
    }
    try {
        driver.await("debugger IME hidden and viewport animation complete") {
            var hidden = false
            instrumentation.runOnMainSync {
                hidden = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == false && animations.isEmpty()
            }
            hidden
        }
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("Debugger window frame did not commit", committed.await(5, TimeUnit.SECONDS))
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
    } finally {
        instrumentation.runOnMainSync { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
    }
}
