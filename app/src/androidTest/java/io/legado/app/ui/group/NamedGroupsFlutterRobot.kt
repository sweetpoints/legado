package io.legado.app.ui.group

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.flutter.FlutterUiTestDriver
import org.junit.Assert.*
import java.util.ArrayDeque

/** Normal dialogs and real advertised Android accessibility actions; no test channel. */
internal class NamedGroupsFlutterRobot(private val scenario: ActivityScenario<AboutActivity>) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val driver = FlutterUiTestDriver(instrumentation)
    init { driver.enableAccessibility() }
    fun host(): NamedGroupDialog {
        var dialog: NamedGroupDialog? = null
        scenario.onActivity { dialog = it.supportFragmentManager.fragments.filterIsInstance<NamedGroupDialog>().singleOrNull() }
        return requireNotNull(dialog)
    }
    fun ready() {
        driver.await("Normal native group host and Flutter add action ready") {
            var ready = false
            scenario.onActivity {
                val host = it.supportFragmentManager.fragments.filterIsInstance<NamedGroupDialog>().singleOrNull()
                ready = host?.model?.state?.value?.let { state -> !state.loading && !state.busy } == true
            }
            ready && driver.node("named-group-add")?.let { n -> try { n.isEnabled } finally { n.recycle() } } == true
        }
    }
    @Suppress("DEPRECATION")
    fun click(id: String) {
        var action: AccessibilityNodeInfo? = null
        driver.await("Actual named group control $id advertises CLICK") {
            val root = driver.node(id) ?: return@await false
            val nodes = ArrayDeque<AccessibilityNodeInfo>(); nodes.add(AccessibilityNodeInfo.obtain(root)); root.recycle()
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isEnabled && node.isVisibleToUser && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    action = node; nodes.forEach { it.recycle() }; return@await true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
                node.recycle()
            }
            false
        }
        val node = requireNotNull(action)
        try { assertTrue("Single named group CLICK rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { node.recycle() }
    }
    fun edit(name: String) {
        val id = "named-group-edit-" + namedGroupRowId(name)
        repeat(40) {
            if (driver.node(id)?.let { n -> n.recycle(); true } == true) {
                click(id)
                driver.await("Native and actual named group editor ready") {
                    host().model.state.value.editing && editable()?.let { it.recycle(); true } == true
                }
                return
            }
            val list = driver.node("named-group-list")
            if (list != null) {
                try { list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { list.recycle() }
            }
            android.os.SystemClock.sleep(50)
        }
        fail("Group row was not rendered")
    }
    private fun editable(): AccessibilityNodeInfo? {
        val node = driver.node("named-group-name") ?: return null
        return if (node.isEnabled && node.isFocused && node.isEditable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) node
            else { node.recycle(); null }
    }
    fun input(text: String) {
        var field: AccessibilityNodeInfo? = null
        driver.await("Focused named group input") { field = editable(); field != null }
        val node = requireNotNull(field)
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) } finally { node.recycle() }
        assertName(text)
    }
    fun select(start: Int, end: Int) {
        var field: AccessibilityNodeInfo? = null
        driver.await("Actual named group selection action") {
            field = editable()
            val ready = field?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION } == true
            if (!ready) { field?.recycle(); field = null }; ready
        }
        val node = requireNotNull(field)
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })) } finally { node.recycle() }
        assertSelection(start, end)
    }
    fun assertSelection(start: Int, end: Int) {
        driver.await("Actual and native named group raw selection") {
            val state = host().model.state.value
            val node = driver.node("named-group-name")
            val actual = node?.let { try { it.isFocused && it.textSelectionStart == start && it.textSelectionEnd == end } finally { it.recycle() } } == true
            state.selectionStart == start && state.selectionEnd == end && actual
        }
    }
    fun awaitOriginalRoomReceipt(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 1000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            android.os.SystemClock.sleep(10)
        }
        assertTrue("Original Room receipt deadline", condition())
    }
    fun assertName(text: String) {
        driver.await("Native and actual group name acknowledge text") {
            host().model.state.value.name == text && driver.text("named-group-name") == text
        }
    }
    override fun close() { driver.close() }
}
