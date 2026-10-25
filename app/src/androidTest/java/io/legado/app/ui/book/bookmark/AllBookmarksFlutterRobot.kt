package io.legado.app.ui.book.bookmark

import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.ArrayDeque
import org.junit.Assert.*

/** Only actual advertised controls in the normal Flutter activity/dialog windows. */
internal class AllBookmarksFlutterRobot : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val driver = FlutterUiTestDriver(instrumentation).apply { enableAccessibility() }
    fun ready() = driver.awaitIdentifier("all-bookmarks-list")
    @Suppress("DEPRECATION")
    fun click(id: String) {
        var action: AccessibilityNodeInfo? = null
        driver.await("Actual bookmarks CLICK $id") {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            driver.node(id)?.let(pending::add) ?: return@await false
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    action = node; pending.forEach { it.recycle() }; return@await true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                node.recycle()
            }
            false
        }
        requireNotNull(action).let { try { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { it.recycle() } }
    }
    var plainLabelObservation: String = "not observed"
        private set
    fun plainLabel(id: String): String? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        driver.node(id)?.let(pending::add) ?: run {
            plainLabelObservation = "found=false"; return null
        }
        val labels = linkedSetOf<String>()
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            try {
                visited++
                // Flutter 3.47.6 BaseRoleConfigurator maps non-editable Text to
                // contentDescription; keep scope to this identifier and descendants.
                if (node.isVisibleToUser && !node.isEditable) {
                    node.text?.toString()?.takeIf { it.isNotEmpty() }?.let(labels::add)
                    node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let(labels::add)
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
            } finally { node.recycle() }
        }
        plainLabelObservation = "found=true nodes=$visited distinctLabels=${labels.size} lengths=${labels.take(8).map { it.length }}"
        // Never concatenate unrelated labels or weaken a full-value assertion.
        return labels.singleOrNull()
    }
    fun reveal(id: String) {
        repeat(60) {
            driver.node(id)?.let { it.recycle(); return }
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            driver.node("all-bookmarks-list")?.let(pending::add)
            var scrolled = false
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (!scrolled && node.isEnabled && node.isVisibleToUser && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                    scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                if (!scrolled) for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                node.recycle()
            }
            android.os.SystemClock.sleep(50)
        }
        fail("Bookmark identity was not rendered")
    }
    fun longPress(id: String) {
        val bounds = driver.bounds(id)
        driver.touchDown(bounds.exactCenterX(), bounds.exactCenterY()).use {
            it.hold(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        }
    }
    override fun close() = driver.close()
}
