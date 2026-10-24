package io.legado.app.ui.highlight

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.ArrayDeque
import org.junit.Assert.*

/** Actual Android accessibility only: no test bridge or direct ViewModel action. */
@Suppress("DEPRECATION")
internal class HighlightManagerUiProbe(val driver: FlutterUiTestDriver, private val instrumentation: Instrumentation) {
    fun await15(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        assertTrue(description, predicate())
    }
    fun exists(id: String) = driver.node(id)?.let { it.recycle(); true } ?: false
    fun reveal(id: String, list: String = "highlight-management-list") {
        if (exists(id)) return
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline && !exists(id)) {
            var root = driver.node(list)
            if (root == null) root = instrumentation.uiAutomation.rootInActiveWindow
            val pending = ArrayDeque<AccessibilityNodeInfo>(); root?.let(pending::add)
            var scrolled = false
            while (pending.isNotEmpty()) {
                val n = pending.removeFirst()
                if (!scrolled && n.isVisibleToUser && n.isEnabled && n.isScrollable)
                    scrolled = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
                n.recycle()
            }
            SystemClock.sleep(if (scrolled) 150 else 50)
        }
        assertTrue("actual identifier $id", exists(id))
    }
    private fun actionNode(id: String): AccessibilityNodeInfo? {
        val original = driver.node(id) ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(AccessibilityNodeInfo.obtain(original))
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst()
            if (n.isVisibleToUser && n.isEnabled && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                pending.forEach { it.recycle() }; original.recycle(); return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
            n.recycle()
        }
        original.recycle()
        return null
    }
    fun click(id: String) {
        var candidate: AccessibilityNodeInfo? = null
        await15("fresh enabled CLICK $id") { candidate = actionNode(id); candidate != null }
        requireNotNull(candidate).let { try { assertTrue("Single actual click accepted $id", it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { it.recycle() } }
    }
    fun checked(id: String, expected: Boolean): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); driver.node(id)?.let(pending::add) ?: return false
        var result = false
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst(); if (n.isCheckable && n.isChecked == expected) result = true
            for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
            n.recycle()
        }
        return result
    }
    fun text(id: String): String {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); driver.node(id)?.let(pending::add) ?: return ""
        val values = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst(); n.text?.toString()?.takeIf(String::isNotEmpty)?.let(values::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
            n.recycle()
        }
        return values.firstOrNull().orEmpty()
    }
}
