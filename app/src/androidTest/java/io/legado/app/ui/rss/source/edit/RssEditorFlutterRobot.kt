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
    private var inputField: RssSourceEditorField? = null

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
    private fun remaining(deadline: Long) = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
    private fun awaitWithin(deadline: Long, message: String, condition: () -> Boolean) {
        while (remaining(deadline) > 0) {
            if (condition()) return
            SystemClock.sleep(minOf(25L, remaining(deadline)))
        }
        fail("$message; ${ime?.diagnostic()}")
    }
    private fun identifierWithin(id: String, deadline: Long) = awaitWithin(deadline, "Missing RSS control $id") {
        driver.node(id)?.let { it.recycle(); true } ?: false
    }
    @Suppress("DEPRECATION")
    private fun clickWithin(id: String, deadline: Long) {
        var clickable: AccessibilityNodeInfo? = null
        awaitWithin(deadline, "Missing clickable RSS control $id") {
            val initial = driver.node(id) ?: return@awaitWithin false
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            pending.add(AccessibilityNodeInfo.obtain(initial))
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
                    clickable = node; pending.forEach { it.recycle() }; initial.recycle()
                    return@awaitWithin true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                node.recycle()
            }
            var ancestor: AccessibilityNodeInfo? = initial
            while (ancestor != null) {
                if (ancestor.isVisibleToUser && ancestor.isEnabled && ancestor.isClickable) {
                    clickable = ancestor
                    return@awaitWithin true
                }
                val parent = ancestor.parent
                ancestor.recycle(); ancestor = parent
            }
            false
        }
        requireNotNull(clickable).let { node ->
            try { assertTrue("Single RSS click rejected: $id", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            finally { node.recycle() }
        }
    }
    fun selectTab(tab: Int, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        var selected = false
        scenario!!.onActivity { selected = it.viewModel.state.value.tab == tab }
        if (!selected) {
            reveal("rss-tab-$tab", "rss-tabs", backwards = tab == 0, deadline = deadline)
            clickWithin("rss-tab-$tab", deadline)
        }
        // Re-selecting the same tab clears native focus. Do not enqueue that redundant
        // command and then mistake its old tab value for acknowledgement of completion.
        awaitWithin(deadline, "RSS native tab acknowledgement $tab") {
            scenario!!.onActivity { selected = it.viewModel.state.value.tab == tab }
            selected
        }
        identifierWithin("rss-fields-$tab", deadline)
    }
    fun field(field: RssSourceEditorField, deadline: Long = SystemClock.uptimeMillis() + 30_000,
        requireEditable: Boolean = false) {
        inputField = field
        selectTab(field.tab, deadline)
        reveal("rss-nav-${field.key}", "rss-navigation",
            backwards = field == RssSourceEditorField.entries.first { it.tab == field.tab }, deadline = deadline)
        clickWithin("rss-nav-${field.key}", deadline)
        awaitWithin(deadline, "RSS native focus and rendered field acknowledgement ${field.key}") {
            var focused = false
            scenario!!.onActivity { focused = it.viewModel.state.value.focus == field }
            val node = driver.node("rss-field-${field.key}")
            val rendered = node?.let {
                try { !requireEditable || (it.isFocused && it.isEditable && it.isEnabled) }
                finally { it.recycle() }
            } ?: false
            focused && rendered
        }
        lateinit var root: View
        scenario!!.onActivity { root = it.window.decorView }
        committedFrame(root, deadline)
    }
    fun input(field: RssSourceEditorField, text: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        field(field, deadline, requireEditable = true)
        clickWithin("rss-field-${field.key}", deadline)
        ime!!.settled(true, deadline)
        // The viewport barrier can replace the semantics node. Acquire a fresh focused
        // node, dispatch exactly one input, then require both platform and native receipt.
        var editable: AccessibilityNodeInfo? = null
        awaitWithin(deadline, "RSS focused editable field ${field.key}") {
            val node = driver.node("rss-field-${field.key}") ?: return@awaitWithin false
            if (node.isFocused && node.isEditable && node.isEnabled && node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                editable = node; true
            } else { node.recycle(); false }
        }
        requireNotNull(editable).let { node ->
            try { assertTrue("Single RSS SET_TEXT rejected: ${field.key}",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })) }
            finally { node.recycle() }
        }
        awaitWithin(deadline, "Private RSS draft and actual field did not acknowledge ${field.key}") {
            var accepted = false
            scenario!!.onActivity { accepted = it.viewModel.state.value.draft[field].text == text }
            val node = driver.node("rss-field-${field.key}")
            val rendered = node?.let {
                try { it.text?.toString() == text } finally { it.recycle() }
            } ?: false
            accepted && rendered
        }
        hideIme(deadline)
    }
    fun hideIme(deadline: Long = SystemClock.uptimeMillis() + 30_000) { closeSoftKeyboard(); ime!!.settled(false, deadline) }
    fun select(field: RssSourceEditorField, start: Int, end: Int) {
        field(field, requireEditable = true)
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
    fun commitViewportFrame() {
        lateinit var root: View
        scenario!!.onActivity { root = it.window.decorView }
        committedFrame(root)
    }
    private fun committedFrame(root: View, deadline: Long = SystemClock.uptimeMillis() + 10_000) {
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue(root.isAttachedToWindow && root.isHardwareAccelerated)
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("RSS viewport frame not committed", committed.await(minOf(5_000L, remaining(deadline)), TimeUnit.MILLISECONDS))
        val idleBudget = minOf(5_000L, remaining(deadline))
        assertTrue("RSS viewport idle exceeded phase budget", idleBudget > 0)
        instrumentation.uiAutomation.waitForIdle(100, idleBudget)
    }
    fun recreate() {
        ime?.close(); ime = null
        scenario!!.recreate()
        loaded()
    }
    @Suppress("DEPRECATION")
    fun reveal(target: String, container: String, backwards: Boolean = false,
        deadline: Long = SystemClock.uptimeMillis() + 30_000) {
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
                SystemClock.sleep(minOf(100L, remaining(deadline)))
            } else SystemClock.sleep(minOf(50L, remaining(deadline)))
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
        private var prepared = 0
        private var ended = 0
        init {
            scenario!!.onActivity {
                root = it.window.decorView
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                                prepared++; running.add(animation)
                            }
                        }
                        override fun onProgress(insets: WindowInsetsCompat,
                            runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                        override fun onEnd(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) ended++
                            running.remove(animation)
                        }
                    })
            }
        }
        fun diagnostic(): String {
            var state = ""
            instrumentation.runOnMainSync {
                val insets = ViewCompat.getRootWindowInsets(root)
                state = "field=${inputField?.key},attached=${root.isAttachedToWindow},laidOut=${root.isLaidOut}," +
                    "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}," +
                    "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}," +
                    "running=${running.size},prepared=$prepared,ended=$ended"
            }
            val node = inputField?.let { driver.node("rss-field-${it.key}") }
            return state + (node?.let {
                try { ",focused=${it.isFocused},editable=${it.isEditable},enabled=${it.isEnabled}" }
                finally { it.recycle() }
            } ?: ",fieldNodeMissing=true")
        }
        fun settled(visible: Boolean, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
            awaitWithin(deadline, "RSS IME visible=$visible and actual viewport animation finished") {
                var settled = false
                instrumentation.runOnMainSync {
                    settled = root.isAttachedToWindow && root.isLaidOut &&
                        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                        running.isEmpty()
                }
                settled
            }
            committedFrame(root, deadline)
        }
        override fun close() {
            instrumentation.runOnMainSync { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
        }
    }
}
