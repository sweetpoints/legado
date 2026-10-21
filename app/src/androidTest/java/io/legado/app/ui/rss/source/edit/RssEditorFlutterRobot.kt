package io.legado.app.ui.rss.source.edit

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.repository.RssSourceEditorField
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Real Android input and native draft acknowledgements; no synthetic Flutter channel. */
internal class RssEditorFlutterRobot : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val driver = FlutterUiTestDriver(instrumentation)
    var scenario: ActivityScenario<RssSourceEditActivity>? = null
    private var ime: ImeBarrier? = null
    private var owner: RssSourceEditActivity? = null

    fun launch(key: String? = null, forResult: Boolean = false) {
        driver.enableAccessibility()
        val intent = Intent(context, RssSourceEditActivity::class.java).apply {
            key?.let { putExtra("sourceUrl", it) }
        }
        scenario = if (forResult) ActivityScenario.launchActivityForResult(intent)
            else ActivityScenario.launch(intent)
        loaded()
    }
    fun loaded() {
        driver.await("RSS native draft ready") {
            var ready = false
            scenario!!.onActivity { ready = it.viewModel.state.value.loaded }
            ready
        }
        scenario!!.onActivity {
            owner = it
            assertTrue("Normal RSS editor renders a real FlutterFragment",
                it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment })
        }
        ime?.close()
        ime = ImeBarrier()
    }
    fun selectTab(tab: Int) {
        reveal("rss-tab-$tab", "rss-tabs", backwards = tab == 0)
        driver.click("rss-tab-$tab")
        driver.await("RSS native tab acknowledgement") {
            var selected = false
            scenario!!.onActivity { selected = it.viewModel.state.value.tab == tab }
            selected
        }
        driver.awaitIdentifier("rss-fields-$tab")
    }
    fun field(field: RssSourceEditorField) {
        selectTab(field.tab)
        reveal("rss-nav-${field.key}", "rss-navigation", backwards = field == RssSourceEditorField.entries.first { it.tab == field.tab })
        driver.click("rss-nav-${field.key}")
        driver.awaitIdentifier("rss-field-${field.key}")
    }
    fun input(field: RssSourceEditorField, text: String) {
        field(field)
        val deadline = SystemClock.uptimeMillis() + 30_000
        driver.setText("rss-field-${field.key}", text) { ime!!.settled(true) }
        var accepted = false
        while (SystemClock.uptimeMillis() < deadline) {
            scenario!!.onActivity { accepted = it.viewModel.state.value.draft[field].text == text }
            if (accepted) break
            SystemClock.sleep(50)
        }
        assertTrue("Private RSS draft did not acknowledge ${field.key}", accepted)
        hideIme()
    }
    fun hideIme() { closeSoftKeyboard(); ime!!.settled(false) }
    fun select(field: RssSourceEditorField, start: Int, end: Int) {
        field(field)
        driver.click("rss-field-${field.key}")
        ime!!.settled(true)
        val node = requireNotNull(driver.node("rss-field-${field.key}"))
        try {
            assertTrue(node.isFocused && node.isEditable)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }))
        } finally { node.recycle() }
        driver.await("RSS private draft selection ${field.key}") {
            var matches = false
            scenario!!.onActivity {
                val value = it.viewModel.state.value.draft[field]
                matches = value.start == start && value.end == end
            }
            matches
        }
        hideIme()
    }
    fun menu(action: String) {
        driver.click("rss-menu")
        reveal("rss-menu-$action", "rss-menu-list")
        driver.click("rss-menu-$action")
    }
    fun finished() = driver.await("RSS durable save/discard finishes its own Activity") {
        scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED
    }
    fun recreate() {
        ime?.close(); ime = null
        scenario!!.recreate()
        loaded()
    }
    @Suppress("DEPRECATION")
    fun reveal(target: String, container: String, backwards: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            driver.node(target)?.let { it.recycle(); return }
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            (if (container == "rss-menu-list") popupScroller() else driver.node(container))?.let(pending::add)
            var scrolled = false
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (!scrolled && node.isVisibleToUser && node.isScrollable &&
                    node.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolled = true
                if (!scrolled) for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                node.recycle()
            }
            if (scrolled) {
                assertTrue("RSS scroll bound for $target", ++scrolls <= 60)
                SystemClock.sleep(100)
            } else SystemClock.sleep(50)
        }
        fail("Missing rendered RSS control $target in $container")
    }
    @Suppress("DEPRECATION")
    private fun popupScroller(): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.viewIdResourceName?.startsWith("rss-menu-") == true) {
                var ancestor = node.parent
                node.recycle()
                pending.forEach { it.recycle() }
                while (ancestor != null) {
                    if (ancestor.isScrollable && ancestor.isVisibleToUser) return ancestor
                    val parent = ancestor.parent
                    ancestor.recycle(); ancestor = parent
                }
                return null
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
            node.recycle()
        }
        return null
    }
    override fun close() {
        ime?.close(); ime = null
        // Only this scenario's retained owner is discarded. Never enumerate other editors,
        // and do the lifecycle check and discard in the same main-thread turn.
        instrumentation.runOnMainSync {
            owner?.takeUnless { it.isFinishing || it.isDestroyed }?.viewModel?.discard()
        }
        scenario?.close(); scenario = null; owner = null
        driver.close()
    }
    private inner class ImeBarrier : AutoCloseable {
        private val running = mutableSetOf<WindowInsetsAnimationCompat>()
        private lateinit var root: View
        init {
            scenario!!.onActivity {
                root = it.window.decorView
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) running.add(animation)
                        }
                        override fun onProgress(insets: WindowInsetsCompat,
                            runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                        override fun onEnd(animation: WindowInsetsAnimationCompat) { running.remove(animation) }
                    })
            }
        }
        fun settled(visible: Boolean) {
            driver.await("RSS IME visible=$visible and actual viewport animation finished") {
                var settled = false
                instrumentation.runOnMainSync {
                    settled = root.isAttachedToWindow && root.isLaidOut &&
                        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                        running.isEmpty()
                }
                settled
            }
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertTrue(root.isAttachedToWindow && root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
            assertTrue("RSS viewport frame not committed", committed.await(5, TimeUnit.SECONDS))
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
        }
        override fun close() {
            instrumentation.runOnMainSync { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
        }
    }
}
