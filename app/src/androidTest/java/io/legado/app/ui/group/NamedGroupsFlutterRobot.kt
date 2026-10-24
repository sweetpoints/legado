package io.legado.app.ui.group

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.flutter.FlutterUiTestDriver
import org.junit.Assert.*
import io.flutter.embedding.android.FlutterView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
            scrollListForward()
            android.os.SystemClock.sleep(50)
        }
        fail("Group row was not rendered")
    }
    private fun scrollListForward(): Boolean {
        // The identifier wraps ListView, whose Scrollable owns another semantics boundary.
        // Only this list and its actual descendants may supply the advertised scroll action.
        val root = driver.node("named-group-list") ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isEnabled && node.isVisibleToUser && node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                try {
                    // Do not try another node after dispatch, even if Android rejects this action.
                    return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } finally {
                    node.recycle()
                    nodes.forEach { it.recycle() }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
            node.recycle()
        }
        return false
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
        var diagnostic = "No observation"
        try {
            driver.await("Native and actual group name acknowledge text") {
                var nativeEquals = false
                var native = "nativeHostMissing=true"
                scenario.onActivity { activity ->
                    val dialog = activity.supportFragmentManager.fragments.filterIsInstance<NamedGroupDialog>().singleOrNull()
                    dialog?.let {
                        val state = it.model.state.value
                        nativeEquals = state.name == text
                        val decor = it.dialog?.window?.decorView
                        val insets = decor?.let(ViewCompat::getRootWindowInsets)
                        native = "nativeNameEquals=$nativeEquals,nativeLength=${state.name.length}," +
                            "editing=${state.editing},busy=${state.busy},loading=${state.loading}," +
                            "recoveryFailed=${state.recoveryFailed},uncertain=${state.mutationUncertain}," +
                            "nativeSelection=${state.selectionStart}..${state.selectionEnd},errorPresent=${state.error != null}," +
                            "decorAttached=${decor?.isAttachedToWindow},laidOut=${decor?.isLaidOut}," +
                            "windowFocused=${decor?.hasWindowFocus()},decorShown=${decor?.isShown}," +
                            "decorSize=${decor?.width}x${decor?.height},lastDrawUptime=${decor?.drawingTime}," +
                            "observedUptime=${android.os.SystemClock.uptimeMillis()}," +
                            "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}," +
                            "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
                    }
                }
                val node = driver.node("named-group-name")
                var actualEquals = text.isEmpty()
                val actual = node?.let {
                    try {
                        val rendered = it.text?.toString().orEmpty()
                        actualEquals = rendered == text
                        "actualFound=true,actualLength=${rendered.length},actualNameEquals=$actualEquals," +
                            "actualFocused=${it.isFocused},actualEditable=${it.isEditable},actualEnabled=${it.isEnabled}," +
                            "actualSelection=${it.textSelectionStart}..${it.textSelectionEnd}"
                    } finally { it.recycle() }
                } ?: "actualFound=false,actualLength=-1,actualNameEquals=$actualEquals"
                diagnostic = "$native;$actual;expectedLength=${text.length}"
                // Keep the same two name-equality predicates and the driver's original deadline.
                nativeEquals && actualEquals
            }
        } catch (failure: AssertionError) {
            // This assertion has already failed. Capturing evidence must not retry or turn it green.
            val originalFailure = AssertionError("${failure.message}; $diagnostic", failure)
            val evidence = try { captureNameFailure() }
                catch (captureFailure: Exception) { "captureError=${captureFailure.javaClass.simpleName}" }
            originalFailure.addSuppressed(AssertionError("Named group failure evidence: $evidence"))
            throw originalFailure
        }
    }
    /** Failure-only observations, taken before interactive-window flags can change semantics. */
    @Suppress("DEPRECATION")
    private fun captureNameFailure(): String {
        val stamp = SystemClock.uptimeMillis()
        val directory = File(instrumentation.targetContext.cacheDir, "named-group-failure")
        check(directory.isDirectory || directory.mkdirs())
        val report = JSONObject().put("failureObservedUptime", stamp)
        var window: Window? = null
        var decor: View? = null
        try {
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.fragments.filterIsInstance<NamedGroupDialog>().singleOrNull()
                window = host?.dialog?.window
                decor = window?.decorView
                val attributes = window?.attributes
                report.put("ownedWindow", JSONObject()
                    .put("type", attributes?.type ?: JSONObject.NULL)
                    .put("flags", attributes?.flags ?: JSONObject.NULL)
                    .put("softInputMode", attributes?.softInputMode ?: JSONObject.NULL)
                    .put("nativeViews", nativeViewTree(decor)))
            }
        } catch (failure: Exception) { report.put("nativeCaptureError", failure.javaClass.simpleName) }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        report.put("originalServiceFlags", originalFlags)
        try { report.put("originalActiveRoot", accessibilityTree(automation.rootInActiveWindow)) }
        catch (failure: Exception) { report.put("activeRootError", failure.javaClass.simpleName) }
        // PixelCopy reads the owned window surface. Do not call View.draw/requestLayout or hide IME.
        report.put("screenshot", copyOwnedWindow(window, decor, File(directory, "$stamp.png")))
        try {
            if (originalFlags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
            }
            report.put("windowsCapturedUptime", SystemClock.uptimeMillis())
            val windows = automation.windows
            val values = JSONArray()
            try {
                windows.take(12).forEach { info ->
                    val bounds = Rect(); info.getBoundsInScreen(bounds)
                    val value = JSONObject().put("id", info.id).put("type", info.type)
                        .put("active", info.isActive).put("focused", info.isFocused)
                        .put("accessibilityFocused", info.isAccessibilityFocused).put("layer", info.layer)
                        .put("bounds", rectangle(bounds))
                    try { value.put("tree", accessibilityTree(info.root)) }
                    catch (failure: Exception) { value.put("rootError", failure.javaClass.simpleName) }
                    values.put(value)
                }
                report.put("availableWindowCount", windows.size)
                report.put("windowsTruncated", windows.size > 12)
                report.put("windows", values)
            } finally { windows.forEach { it.recycle() } }
        } catch (failure: Exception) { report.put("windowsError", failure.javaClass.simpleName) }
        finally {
            try { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
            catch (failure: Exception) { report.put("serviceFlagsRestoreError", failure.javaClass.simpleName) }
            val restoredFlags = runCatching { automation.serviceInfo.flags }.getOrNull()
            report.put("restoredServiceFlags", restoredFlags ?: JSONObject.NULL)
            report.put("serviceFlagsRestored", restoredFlags == originalFlags)
            report.put("captureFinishedUptime", SystemClock.uptimeMillis())
            File(directory, "$stamp.json").writeText(report.toString(2))
        }
        return "cache/named-group-failure/$stamp.json; screenshot=" +
            (report.getJSONObject("screenshot").optString("file", "not-created"))
    }

    private fun copyOwnedWindow(window: Window?, decor: View?, file: File): JSONObject {
        val result = JSONObject().put("requestedUptime", SystemClock.uptimeMillis())
        val width = decor?.width ?: 0; val height = decor?.height ?: 0
        if (window == null || width <= 0 || height <= 0 || width.toLong() * height > 8_388_608L) {
            return result.put("skipped", "owned_surface_unavailable_or_exceeds_pixel_bound")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val completed = CountDownLatch(1)
        val status = AtomicInteger(-1)
        var finished = false
        try {
            instrumentation.runOnMainSync {
                try {
                    PixelCopy.request(window, bitmap, { code -> status.set(code); completed.countDown() }, Handler(Looper.getMainLooper()))
                } catch (_: Exception) { status.set(-2); completed.countDown() }
            }
            // This bounded wait happens only after the unchanged original assertion has failed.
            finished = completed.await(2, TimeUnit.SECONDS)
            result.put("callbackCompleted", finished).put("resultCode", status.get())
                .put("width", width).put("height", height).put("completedUptime", SystemClock.uptimeMillis())
            if (finished && status.get() == PixelCopy.SUCCESS) {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                result.put("file", "cache/named-group-failure/${file.name}").put("bytes", file.length())
            }
        } catch (failure: Exception) { result.put("error", failure.javaClass.simpleName) }
        finally { if (finished) bitmap.recycle() }
        return result
    }

    private fun nativeViewTree(root: View?): JSONObject {
        val result = JSONObject().put("capturedUptime", SystemClock.uptimeMillis())
        val values = JSONArray()
        val pending = ArrayDeque<Pair<View, Int>>()
        root?.let { pending.add(it to -1) }
        while (pending.isNotEmpty() && values.length() < 128) {
            val (view, parent) = pending.removeFirst()
            val index = values.length()
            val location = IntArray(2); view.getLocationOnScreen(location)
            val visible = Rect(); val visibleResult = view.getGlobalVisibleRect(visible)
            val insets = ViewCompat.getRootWindowInsets(view)
            val value = JSONObject().put("parent", parent).put("class", view.javaClass.name)
                .put("id", if (view.id == View.NO_ID) "none" else runCatching { view.resources.getResourceName(view.id) }.getOrDefault("unresolved"))
                .put("size", JSONArray(listOf(view.width, view.height))).put("screenLocation", JSONArray(location.toList()))
                .put("globalVisible", visibleResult).put("globalVisibleBounds", rectangle(visible))
                .put("attached", view.isAttachedToWindow).put("laidOut", view.isLaidOut)
                .put("shown", view.isShown).put("visibility", view.visibility).put("focused", view.hasFocus())
                .put("children", if (view is ViewGroup) view.childCount else 0)
                .put("childrenTruncated", view is ViewGroup && view.childCount > 32)
                .put("windowFocused", view.hasWindowFocus()).put("lastDrawUptime", view.drawingTime)
                .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
                .put("imeBottom", insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: JSONObject.NULL)
            if (view is FlutterView) {
                val metrics = view.viewportMetrics
                value.put("flutterEngineAttached", view.isAttachedToFlutterEngine)
                    .put("flutterDisplayingUi", view.attachedFlutterEngine?.renderer?.isDisplayingFlutterUi ?: JSONObject.NULL)
                    .put("viewport", JSONObject().put("dpr", metrics.devicePixelRatio.toDouble())
                        .put("width", metrics.width).put("height", metrics.height)
                        .put("insetTop", metrics.viewInsetTop).put("insetBottom", metrics.viewInsetBottom)
                        .put("insetLeft", metrics.viewInsetLeft).put("insetRight", metrics.viewInsetRight)
                        .put("paddingTop", metrics.viewPaddingTop).put("paddingBottom", metrics.viewPaddingBottom)
                        .put("paddingLeft", metrics.viewPaddingLeft).put("paddingRight", metrics.viewPaddingRight))
            }
            values.put(value)
            if (view is ViewGroup) {
                for (child in 0 until minOf(view.childCount, 32)) pending.add(view.getChildAt(child) to index)
            }
        }
        return result.put("nodes", values).put("truncated", pending.isNotEmpty())
    }

    @Suppress("DEPRECATION")
    private fun accessibilityTree(root: AccessibilityNodeInfo?): JSONObject {
        val result = JSONObject().put("capturedUptime", SystemClock.uptimeMillis())
            .put("rootPackage", root?.packageName?.toString() ?: JSONObject.NULL)
        val values = JSONArray()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        root?.let { pending.add(it to -1) }
        try {
            while (pending.isNotEmpty() && values.length() < 256) {
                val (node, parent) = pending.removeFirst()
                try {
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    val id = node.viewIdResourceName
                    val safeId = if (id == null || id in setOf("named-group-add", "named-group-close", "named-group-backdrop",
                            "named-group-list", "named-group-done", "named-group-name", "named-group-error", "named-group-retry",
                            "named-group-cancel", "named-group-confirm") ||
                            id.matches(Regex("named-group-(edit|delete)-[a-f0-9]{64}")) ||
                            id.matches(Regex("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_]+"))) id
                        else "other-identifier-length=${id.length}"
                    val index = values.length()
                    values.put(JSONObject().put("parent", parent).put("class", node.className?.toString() ?: JSONObject.NULL)
                        .put("package", node.packageName?.toString() ?: JSONObject.NULL).put("id", safeId ?: JSONObject.NULL)
                        .put("windowId", node.windowId).put("bounds", rectangle(bounds)).put("children", node.childCount)
                        .put("childrenTruncated", node.childCount > 32)
                        .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled).put("focused", node.isFocused)
                        .put("accessibilityFocused", node.isAccessibilityFocused).put("editable", node.isEditable)
                        .put("textLength", node.text?.length ?: -1).put("descriptionLength", node.contentDescription?.length ?: -1)
                        .put("selectionStart", node.textSelectionStart).put("selectionEnd", node.textSelectionEnd)
                        .put("actionIds", JSONArray(node.actionList.take(32).map { it.id })))
                    for (child in 0 until minOf(node.childCount, 32)) node.getChild(child)?.let { pending.add(it to index) }
                } finally { node.recycle() }
            }
            result.put("nodes", values).put("truncated", pending.isNotEmpty())
        } finally { pending.forEach { it.first.recycle() } }
        return result
    }

    private fun rectangle(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    override fun close() { driver.close() }
}
