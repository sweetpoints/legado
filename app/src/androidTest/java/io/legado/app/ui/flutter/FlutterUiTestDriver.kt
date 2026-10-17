package io.legado.app.ui.flutter

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import org.junit.Assert.assertTrue

/** Reads Flutter's rendered semantics and sends real Android input, without a Dart test channel. */
@Suppress("DEPRECATION")
internal class FlutterUiTestDriver(private val instrumentation: Instrumentation) : AutoCloseable {
    private var flags: Int? = null
    fun enableAccessibility() {
        if (flags != null) return
        instrumentation.uiAutomation.serviceInfo.let {
            flags = it.flags
            it.flags = it.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            instrumentation.uiAutomation.serviceInfo = it
        }
    }
    override fun close() {
        flags?.let { saved ->
            instrumentation.uiAutomation.serviceInfo.let {
                it.flags = saved
                instrumentation.uiAutomation.serviceInfo = it
            }
        }
        flags = null
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for $description", condition())
    }
    fun node(id: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add) ?: return null
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.isVisibleToUser && current.viewIdResourceName == id) {
                pending.forEach { it.recycle() }
                return current
            }
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        return null
    }
    fun awaitIdentifier(id: String) = await("Flutter identifier $id") {
        node(id)?.let { it.recycle(); true } ?: false
    }
    fun bounds(id: String): Rect {
        awaitIdentifier(id)
        return requireNotNull(node(id)).let { value ->
            Rect().also { value.getBoundsInScreen(it); value.recycle() }
        }
    }
    fun text(id: String): String = node(id)?.let { value ->
        value.text?.toString().orEmpty().also { value.recycle() }
    }.orEmpty()
    fun click(id: String) = await("clickable Flutter control $id") {
        val initial = node(id) ?: return@await false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(AccessibilityNodeInfo.obtain(initial))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.isVisibleToUser && current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.recycle(); pending.forEach { it.recycle() }; initial.recycle()
                return@await true
            }
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        var ancestor: AccessibilityNodeInfo? = initial
        while (ancestor != null) {
            if (ancestor.isEnabled && ancestor.isClickable && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ancestor.recycle(); return@await true
            }
            val parent = ancestor.parent
            ancestor.recycle(); ancestor = parent
        }
        false
    }
    /** Scroll the rendered popup/list until its requested semantic action becomes visible. */
    fun clickScrollable(id: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val target = node(id)
            if (target != null) {
                target.recycle()
                click(id)
                return
            }
            assertTrue("Flutter scroll limit exceeded while finding $id", scrolls < 24)
            // Wait for the popup's own scroll semantics. Scrolling the underlying
            // source list cannot reveal a menu action, and absent semantics must
            // not consume the finite budget of actual scroll actions.
            if (scrollForward()) {
                scrolls++
                SystemClock.sleep(200)
            } else {
                SystemClock.sleep(50)
            }
        }
        assertTrue("Flutter scroll deadline exceeded while finding $id", false)
    }
    private fun containsMenuAction(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(AccessibilityNodeInfo.obtain(root))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val action = current.viewIdResourceName
                ?.takeIf { it.startsWith("source-manager-action:") }
                ?.removePrefix("source-manager-action:")
            // Inline row actions include a URL suffix (edit:<url>); they belong
            // to the source list and must not identify it as the popup scroller.
            val matches = current.isVisibleToUser && action != null && ':' !in action
            if (matches) {
                current.recycle()
                pending.forEach { it.recycle() }
                return true
            }
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        return false
    }
    private fun scrollForward(): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add) ?: return false
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.isVisibleToUser && current.isEnabled && current.isScrollable &&
                containsMenuAction(current) &&
                current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                current.recycle()
                pending.forEach { it.recycle() }
                return true
            }
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        return false
    }
    fun setText(id: String, text: String) {
        click(id)
        var ready: AccessibilityNodeInfo? = null
        await("Flutter field advertises editable ACTION_SET_TEXT: $id") {
            val candidate = node(id) ?: return@await false
            val acceptsText = candidate.isFocused && candidate.isEditable &&
                candidate.isEnabled && candidate.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                }
            if (acceptsText) ready = candidate else candidate.recycle()
            acceptsText
        }
        // Flutter's Android bridge can optimistically return true for SET_TEXT
        // before Dart has installed onSetText. The advertised action above is
        // the actual semantics capability, not merely Android's focus state.
        val field = requireNotNull(ready)
        try {
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
        } finally { field.recycle() }
        await("Flutter field value $id") { this.text(id) == text }
    }
    fun containsText(id: String, text: String): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        node(id)?.let(pending::add) ?: return false
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.text?.toString()?.contains(text) == true || current.contentDescription?.toString()?.contains(text) == true) {
                current.recycle(); pending.forEach { it.recycle() }; return true
            }
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        return false
    }
    fun touchDown(x: Float, y: Float): HeldTouch = HeldTouch(x, y)
    inner class HeldTouch internal constructor(private var x: Float, private var y: Float) : AutoCloseable {
        private val downTime = SystemClock.uptimeMillis()
        private var active = true
        init { send(MotionEvent.ACTION_DOWN) }
        private fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        fun hold(millis: Long) { SystemClock.sleep(millis) }
        fun moveTo(nextX: Float, nextY: Float, duration: Long = 500) {
            val startX = x; val startY = y
            val steps = 20
            for (step in 1..steps) {
                x = startX + (nextX - startX) * step / steps
                y = startY + (nextY - startY) * step / steps
                send(MotionEvent.ACTION_MOVE)
                SystemClock.sleep(duration / steps)
            }
        }
        override fun close() {
            if (active) { active = false; send(MotionEvent.ACTION_UP) }
        }
    }
}
