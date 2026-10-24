package io.legado.app.ui.highlight

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.ArrayDeque
import org.junit.Assert.*

/** Actual Android accessibility only: no test bridge or direct ViewModel action. */
@Suppress("DEPRECATION")
internal class HighlightManagerUiProbe(val driver: FlutterUiTestDriver, private val instrumentation: Instrumentation) {
    fun await15(description: String, predicate: () -> Boolean) = await15(description, { "" }, predicate)
    fun await15(description: String, diagnostic: () -> String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        if (!predicate()) fail("$description; ${diagnostic()}")
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
    private fun scalar(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf(String::isNotEmpty)
            ?: node.contentDescription?.toString()?.takeIf(String::isNotEmpty)

    fun text(id: String): String {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); driver.node(id)?.let(pending::add) ?: return ""
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst()
            // Fixed Flutter embedding maps editable value to text, ordinary label/value/hint
            // to contentDescription. Preserve the complete scalar; never trim or concatenate.
            val value = scalar(n)
            if (value != null) { n.recycle(); pending.forEach { it.recycle() }; return value }
            for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
            n.recycle()
        }
        return ""
    }
    /** Failure-only shape/equality diagnostics, without names, labels or other private text. */
    fun diagnostic(id: String, expectedText: String? = null): String {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); driver.node(id)?.let(pending::add) ?: return "nodeMissing=true"
        val nodes = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst()
            val bounds = Rect().also(n::getBoundsInScreen)
            val value = scalar(n)
            nodes += "index=${nodes.size},textLength=${n.text?.length},descriptionLength=${n.contentDescription?.length}," +
                "scalarLength=${value?.length},scalarEquals=${expectedText?.let { it == value }}," +
                "visible=${n.isVisibleToUser},enabled=${n.isEnabled},editable=${n.isEditable}," +
                "checkable=${n.isCheckable},checked=${n.isChecked},bounds=$bounds"
            for (i in 0 until n.childCount) n.getChild(i)?.let(pending::add)
            n.recycle()
        }
        return nodes.joinToString(" | ")
    }
}
