package io.legado.app.ui.book.toc.rule

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.legado.app.ui.flutter.FlutterUiTestDriver
import org.junit.Assert.assertTrue
import java.util.ArrayDeque

/** Feature-local input requires an advertised action on the identifier or its actual descendants. */
internal class TxtTocManagerUiProbe(private val instrumentation: Instrumentation) : AutoCloseable {
    val driver = FlutterUiTestDriver(instrumentation)
    init { driver.enableAccessibility() }
    fun await15(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for $description", condition())
    }
    fun actual(id: String, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        driver.node(id)?.let(nodes::add) ?: return false
        var success = false
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (!success && node.isVisibleToUser && action(node)) success = true
            for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
            node.recycle()
        }
        return success
    }
    fun click(id: String) = await15("actual clickable $id") {
        actual(id) { it.isEnabled && it.isClickable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_CLICK } && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    fun checked(id: String, expected: Boolean) = actual(id) { it.isCheckable && it.isChecked == expected }
    fun reveal(id: String) = await15("rendered row $id") {
        driver.node(id)?.let { it.recycle(); return@await15 true }
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        driver.node("txt-toc-list")?.let(nodes::add) ?: return@await15 false
        var scrolled = false
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (!scrolled && node.isVisibleToUser && node.isEnabled && node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolled = true
            for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
            node.recycle()
        }
        if (scrolled) SystemClock.sleep(150)
        false
    }
    fun bounds(id: String): android.graphics.Rect {
        var bounds: android.graphics.Rect? = null
        await15("actual bounds $id") { actual(id) { node ->
            val r = android.graphics.Rect(); node.getBoundsInScreen(r)
            if (!r.isEmpty) { bounds = r; true } else false
        } }
        return checkNotNull(bounds)
    }
    override fun close() = driver.close()
}
