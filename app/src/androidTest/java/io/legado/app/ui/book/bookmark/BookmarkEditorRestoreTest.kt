package io.legado.app.ui.book.bookmark

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.FileBookmarkEditorRepository
import io.legado.app.ui.about.AboutActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookmarkEditorRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = io.legado.app.ui.flutter.FlutterUiTestDriver(instrumentation)
    @Before fun accessibility() { driver.enableAccessibility() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()
    private val bookmarks = mutableListOf<Bookmark>()

    @After
    fun cleanup() {
        driver.close()
        runBlocking(Dispatchers.IO) {
            appDb.bookmarkDao.delete(*bookmarks.toTypedArray())
            ids.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
        }
    }

    private fun await(scenario: ActivityScenario<AboutActivity>, closed: Boolean = false) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("bookmark-test")
                        as? BookmarkDialog
                ready = if (closed) dialog == null else dialog?.model?.state?.value?.loaded == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Bookmark editor did not restore/close")
    }

    private var diagnosticLogs = 0
    private var lastChosenClick = "not dispatched"
    private fun nodeScalar(node: android.view.accessibility.AccessibilityNodeInfo?): String {
        if (node == null) return "missing"
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return "class=${node.className},id=${node.viewIdResourceName},window=${node.windowId}," +
            "visible=${node.isVisibleToUser},enabled=${node.isEnabled},editable=${node.isEditable}," +
            "focused=${node.isFocused},axFocused=${node.isAccessibilityFocused}," +
            "actions=${node.actionList.take(12).map { it.id }},bounds=$bounds," +
            "textPresent=${node.text != null},textLength=${node.text?.length ?: 0}," +
            "descriptionPresent=${node.contentDescription != null},descriptionLength=${node.contentDescription?.length ?: 0}"
    }
    private fun diagnosticLog(value: String) {
        if (diagnosticLogs < 8) {
            diagnosticLogs++
            android.util.Log.i("BookmarkEditorProbe", value.take(3800))
        }
    }
    /** Read-only bounded observations. No strings from book text/note/chapter are emitted. */
    private inner class InputProbe(private val scenario: ActivityScenario<AboutActivity>, private val key: String, private val expected: String) {
        private var samples = 0
        private var nextSample = 0L
        var last = "not sampled"
            private set
        fun sample(phase: String, force: Boolean = false) {
            try { capture(phase, force) }
            catch (error: Exception) {
                last = "phase=$phase,diagnosticErrorClass=${error.javaClass.simpleName}"
                diagnosticLog(last)
            }
        }
        private fun capture(phase: String, force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && (samples >= 4 || now < nextSample)) return
            if (!force) { samples++; nextSample = now + 5_000 }
            val parts = mutableListOf("phase=$phase,key=$key,expectedLength=${expected.length},chosenCLICK=$lastChosenClick")
            scenario.onActivity { host ->
                val editor = host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog
                val state = editor?.model?.state?.value
                if (state == null) parts += "native=missing" else {
                    val value = when (key) { "text" -> state.bookText; "content" -> state.content; else -> state.chapter }
                    parts += "nativeFocus=${state.focused},nativeLength=${value.length},nativeEquals=${value == expected},loaded=${state.loaded},loading=${state.loading},busy=${state.busy},finished=${state.finished},errorPresent=${state.error != null}"
                }
                val decor = editor?.dialog?.window?.decorView
                val focus = editor?.dialog?.window?.currentFocus
                val activityManager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val windowManager = decor?.context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                val insets = decor?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
                val bounds = android.graphics.Rect(); decor?.getGlobalVisibleRect(bounds)
                parts += "decorClass=${decor?.javaClass?.simpleName},decorIdentity=${decor?.let(System::identityHashCode)},decorSize=${decor?.width}x${decor?.height},decorBounds=$bounds,attached=${decor?.isAttachedToWindow},windowFocus=${decor?.hasWindowFocus()},imeKnown=${insets != null},imeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())},imeBottom=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}"
                parts += "currentFocusClass=${focus?.javaClass?.name},focusIdentity=${focus?.let(System::identityHashCode)},focusAttached=${focus?.isAttachedToWindow},focusWindow=${focus?.hasWindowFocus()},activityIMM=${System.identityHashCode(activityManager)},windowIMM=${windowManager?.let(System::identityHashCode)},activityActiveCurrent=${focus?.let { activityManager.isActive(it) }},windowActiveCurrent=${focus?.let { windowManager?.isActive(it) }},acceptingText=${activityManager.isAcceptingText},windowAcceptingText=${windowManager?.isAcceptingText}"
                val views = java.util.ArrayDeque<android.view.View>()
                decor?.let(views::add); var visited = 0; var flutterViews = 0
                while (views.isNotEmpty() && visited++ < 48 && flutterViews < 2) {
                    val view = views.removeFirst()
                    if (view is io.flutter.embedding.android.FlutterView) {
                        flutterViews++
                        val metrics = view.viewportMetrics
                        parts += "FlutterViewClass=${view.javaClass.name},identity=${System.identityHashCode(view)},isCurrent=${view === focus},attached=${view.isAttachedToWindow},windowFocus=${view.hasWindowFocus()},hasFocus=${view.hasFocus()},isFocused=${view.isFocused},activityActive=${activityManager.isActive(view)},windowActive=${windowManager?.isActive(view)},engineAttached=${view.isAttachedToFlutterEngine},displaying=${view.attachedFlutterEngine?.renderer?.isDisplayingFlutterUi},viewport=${metrics.width}x${metrics.height},engineImeBottom=${metrics.viewInsetBottom}"
                    }
                    if (view is android.view.ViewGroup) for (index in 0 until minOf(view.childCount, 24)) views.add(view.getChildAt(index))
                }
            }
            val activeRoot = instrumentation.uiAutomation.rootInActiveWindow
            parts += "AXRootPackage=${activeRoot?.packageName},AXRootWindowId=${activeRoot?.windowId},AXRootClass=${activeRoot?.className}"
            activeRoot?.recycle()
            val validatedOutside = ownedNode(scenario, "outside")
            parts += "ownedOutsideFound=${validatedOutside != null}"
            validatedOutside?.recycle()
            val outside = driver.node("bookmark-editor-outside")
            parts += "ownerOutside=${nodeScalar(outside)}"
            outside?.recycle()
            val marker = driver.node("bookmark-editor-$key")
            parts += "marker=${nodeScalar(marker)},textEquals=${marker?.text?.toString() == expected},descriptionEquals=${marker?.contentDescription?.toString() == expected}"
            marker?.recycle()
            val field = actual(scenario, key) { it.isEditable }
            parts += "editableDescendant=${nodeScalar(field)},actualRawEquals=${field?.text?.toString() == expected},actualNormalizedEquals=${field != null && field.text?.toString().orEmpty() == expected}"
            field?.recycle()
            last = parts.joinToString(";")
            diagnosticLog(last)
        }
        fun fail(failure: AssertionError) {
            sample("failure", force = true)
            failure.addSuppressed(AssertionError("Bookmark bounded scalar: $last"))
        }
    }
    private fun ownedNode(scenario: ActivityScenario<AboutActivity>, id: String): android.view.accessibility.AccessibilityNodeInfo? {
        val node = driver.node("bookmark-editor-$id") ?: return null
        val outside = driver.node("bookmark-editor-outside")
        val window = android.graphics.Rect()
        scenario.onActivity { host ->
            (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)
                ?.dialog?.window?.decorView?.getGlobalVisibleRect(window)
        }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val owned = outside != null && node.windowId == outside.windowId && android.graphics.Rect.intersects(window, bounds)
        outside?.recycle()
        if (owned) return node
        node.recycle(); return null
    }
    private fun chapterText(scenario: ActivityScenario<AboutActivity>): String? {
        val node = ownedNode(scenario, "chapter") ?: return null
        // Fixed Flutter BaseRoleConfigurator maps ordinary Text to contentDescription;
        // only editable fields map their value to AccessibilityNodeInfo.text.
        return try { node.contentDescription?.toString() } finally { node.recycle() }
    }
    private fun until(deadline: Long, description: String, condition: () -> Boolean) {
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(description, condition())
    }
    private data class WindowObservation(
        val decorIdentity: Int,
        val focusIdentity: Int?,
        val bounds: android.graphics.Rect,
        val width: Int,
        val height: Int,
        val attached: Boolean,
        val windowFocused: Boolean,
        val imeVisible: Boolean?,
        val imeBottom: Int?,
        val inputActive: Boolean,
        val acceptingText: Boolean,
    )
    private fun observation(scenario: ActivityScenario<AboutActivity>): WindowObservation? {
        var result: WindowObservation? = null
        scenario.onActivity { host ->
            val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)?.dialog?.window
                ?: return@onActivity
            val decor = window.decorView
            val bounds = android.graphics.Rect()
            if (!decor.getGlobalVisibleRect(bounds)) return@onActivity
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
            val focus = window.currentFocus
            val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            result = WindowObservation(System.identityHashCode(decor), focus?.let(System::identityHashCode), bounds,
                decor.width, decor.height, decor.isAttachedToWindow, decor.hasWindowFocus(),
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()),
                insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom,
                focus != null && manager.isActive(focus), manager.isAcceptingText)
        }
        return result
    }
    private fun frame(scenario: ActivityScenario<AboutActivity>, deadline: Long, requireInputConnection: Boolean = false) {
        // Observe two Choreographer turns without invalidating, requesting layout or
        // replacing a host draw callback. Works on the API26 minimum as well.
        until(deadline, "Owned viewport, IME and input connection stable across two natural animation turns") {
            val before = observation(scenario) ?: return@until false
            if (!before.attached || !before.windowFocused || before.width <= 0 || before.height <= 0 ||
                before.imeVisible == null || before.imeBottom == null) return@until false
            if (requireInputConnection && (!before.inputActive || !before.acceptingText)) return@until false
            val latch = java.util.concurrent.CountDownLatch(1)
            var decor: android.view.View? = null
            val second = Runnable { latch.countDown() }
            val first = Runnable { decor?.postOnAnimation(second) }
            scenario.onActivity { host ->
                decor = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!.decorView
                decor!!.postOnAnimation(first)
            }
            val observed = try {
                val remaining = deadline - SystemClock.uptimeMillis()
                remaining > 0 && latch.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                scenario.onActivity { decor?.removeCallbacks(first); decor?.removeCallbacks(second) }
            }
            observed && observation(scenario) == before
        }
    }
    private fun actual(scenario: ActivityScenario<AboutActivity>, id: String, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        ownedNode(scenario, id)?.let(nodes::add) ?: return null
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && predicate(node)) { nodes.forEach { it.recycle() }; return node }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            node.recycle()
        }
        return null
    }
    private fun click(scenario: ActivityScenario<AboutActivity>, id: String, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        var action: android.view.accessibility.AccessibilityNodeInfo? = null
        until(deadline, "Owned $id advertises CLICK") {
            action = actual(scenario, id) { it.isClickable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK } }
            action != null
        }
        try { assertTrue(action!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { action?.recycle() }
    }
    private fun diagnosticClick(scenario: ActivityScenario<AboutActivity>, id: String, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        var action: android.view.accessibility.AccessibilityNodeInfo? = null
        until(deadline, "Owned $id advertises CLICK") {
            action = actual(scenario, id) { it.isClickable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK } }
            action != null
        }
        lastChosenClick = nodeScalar(action)
        diagnosticLog("phase=chosenCLICK,key=$id;$lastChosenClick")
        try { assertTrue(action!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { action?.recycle() }
    }
    private fun reveal(scenario: ActivityScenario<AboutActivity>, key: String, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        until(deadline, "Visible owned bookmark field $key without changing IME") {
            ownedNode(scenario, key)?.let { it.recycle(); return@until true }
            val root = ownedNode(scenario, "outside") ?: return@until false
            val windowId = root.windowId
            var top = root
            while (true) {
                val parent = top.parent ?: break
                if (parent.windowId != windowId) { parent.recycle(); break }
                top.recycle(); top = parent
            }
            val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); nodes.add(top)
            var scroll: android.view.accessibility.AccessibilityNodeInfo? = null
            val direction = if (key == "text") android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (scroll == null && node.isVisibleToUser && node.isEnabled && node.isScrollable && node.actionList.any { it.id == direction }) {
                    scroll = node
                } else {
                    for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                    node.recycle()
                }
            }
            if (scroll != null) {
                try { assertTrue(scroll.performAction(direction)) } finally { scroll.recycle() }
                frame(scenario, deadline)
            }
            false
        }
    }
    // Only nativeBackDiscardsEditedDraftWithoutSavingAndKeepsParentAlive uses this
    // pre-click fitted-viewport gate. The six successful cases keep their input path.
    private fun inputBackDraft(scenario: ActivityScenario<AboutActivity>, text: String) {
        val key = "content"
        val probe = InputProbe(scenario, key, text)
        try {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var focusedOrClicked = false
        while (!focusedOrClicked && SystemClock.uptimeMillis() < deadline) {
            probe.sample("beforeFirstFocus")
            // The IME may fit the native window after reveal first sees the note.
            // Re-observe this failed case's target before its first/only focus action.
            reveal(scenario, key, deadline)
            frame(scenario, deadline)
            val candidate = actual(scenario, key) { node ->
                node.isEditable && (node.isFocused ||
                    (node.isClickable && node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }))
            }
            if (candidate == null) continue
            try {
                if (candidate.isFocused) {
                    lastChosenClick = "not dispatched(alreadyFocused=true);${nodeScalar(candidate)}"
                } else {
                    lastChosenClick = nodeScalar(candidate)
                    diagnosticLog("phase=chosenCLICK,key=$key;$lastChosenClick")
                    assertTrue(candidate.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
                focusedOrClicked = true
            } finally { candidate.recycle() }
        }
        assertTrue("Owned content advertises CLICK or already has actual focus", focusedOrClicked)
        fun connectionReady(): Boolean {
            var ready = false
            scenario.onActivity { host ->
                val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!
                val focus = window.currentFocus
                val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ready = focus != null && focus.isAttachedToWindow && focus.hasWindowFocus() && manager.isActive(focus) && manager.isAcceptingText
            }
            return ready
        }
        var field: android.view.accessibility.AccessibilityNodeInfo? = null
        while (field == null && SystemClock.uptimeMillis() < deadline) {
            until(deadline, "Owned focused editable field and real native input connection") {
                probe.sample("readiness")
                val candidate = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
                if (candidate == null) {
                    // A focus acquired in the old viewport can move offscreen when the
                    // actual IME fits the native window. Reveal it using real scrolling;
                    // do not repeat the already dispatched focus click or text action.
                    val visible = ownedNode(scenario, key)
                    if (visible == null && SystemClock.uptimeMillis() < deadline) reveal(scenario, key, deadline)
                    visible?.recycle()
                    false
                } else {
                    val ready = connectionReady(); candidate.recycle(); ready
                }
            }
            frame(scenario, deadline, requireInputConnection = true)
            probe.sample("afterNaturalTurns", force = true)
            // The natural platform boundary may replace or hide the AX node. Return
            // to the same deadline/visibility observation rather than reusing it.
            val fresh = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
            if (fresh != null && connectionReady()) field = fresh else fresh?.recycle()
        }
        assertNotNull("Fresh input target remains owned and ready", field)
        assertTrue("Input connection remains active before the single text action", connectionReady())
        try { assertTrue(field!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) } finally { field?.recycle() }
        until(deadline, "Native and actual bookmark $key input ACK") {
            probe.sample("afterSingleSET_TEXT")
            var same = false
            scenario.onActivity { host -> val state = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                same = (if (key == "text") state.bookText else state.content) == text }
            val node = actual(scenario, key) { it.isEditable }
            val actual = node?.let { try { it.text?.toString().orEmpty() == text } finally { it.recycle() } } ?: false
            same && actual
        }
        } catch (failure: AssertionError) {
            probe.fail(failure)
            throw failure
        }
    }

    private fun input(scenario: ActivityScenario<AboutActivity>, key: String, text: String) {
        val probe = InputProbe(scenario, key, text)
        try {
        val deadline = SystemClock.uptimeMillis() + 30_000
        reveal(scenario, key, deadline)
        val alreadyFocused = actual(scenario, key) { it.isEditable && it.isFocused }
        if (alreadyFocused == null) diagnosticClick(scenario, key, deadline) else {
            lastChosenClick = "not dispatched(alreadyFocused=true);${nodeScalar(alreadyFocused)}"
            alreadyFocused.recycle()
        }
        fun connectionReady(): Boolean {
            var ready = false
            scenario.onActivity { host ->
                val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!
                val focus = window.currentFocus
                val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ready = focus != null && focus.isAttachedToWindow && focus.hasWindowFocus() && manager.isActive(focus) && manager.isAcceptingText
            }
            return ready
        }
        var field: android.view.accessibility.AccessibilityNodeInfo? = null
        while (field == null && SystemClock.uptimeMillis() < deadline) {
            until(deadline, "Owned focused editable field and real native input connection") {
                probe.sample("readiness")
                val candidate = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
                if (candidate == null) {
                    // A focus acquired in the old viewport can move offscreen when the
                    // actual IME fits the native window. Reveal it using real scrolling;
                    // do not repeat the already dispatched focus click or text action.
                    val visible = ownedNode(scenario, key)
                    if (visible == null && SystemClock.uptimeMillis() < deadline) reveal(scenario, key, deadline)
                    visible?.recycle()
                    false
                } else {
                    val ready = connectionReady(); candidate.recycle(); ready
                }
            }
            frame(scenario, deadline, requireInputConnection = true)
            probe.sample("afterNaturalTurns", force = true)
            // The natural platform boundary may replace or hide the AX node. Return
            // to the same deadline/visibility observation rather than reusing it.
            val fresh = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
            if (fresh != null && connectionReady()) field = fresh else fresh?.recycle()
        }
        assertNotNull("Fresh input target remains owned and ready", field)
        assertTrue("Input connection remains active before the single text action", connectionReady())
        try { assertTrue(field!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) } finally { field?.recycle() }
        until(deadline, "Native and actual bookmark $key input ACK") {
            probe.sample("afterSingleSET_TEXT")
            var same = false
            scenario.onActivity { host -> val state = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                same = (if (key == "text") state.bookText else state.content) == text }
            val node = actual(scenario, key) { it.isEditable }
            val actual = node?.let { try { it.text?.toString().orEmpty() == text } finally { it.recycle() } } ?: false
            same && actual
        }
        } catch (failure: AssertionError) {
            probe.fail(failure)
            throw failure
        }
    }

    @Test
    fun fullMetadataAndLargeTextUseDiskIdAndRealRecreationKeepsNoteDraftSelectionAndFocus() {
        val bookmark =
            Bookmark(
                System.nanoTime(),
                "Book-${UUID.randomUUID()}",
                "Author",
                3,
                15,
                "Chapter",
                "Large ".repeat(20000),
                "Original note",
            )
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog(bookmark, 0)
                assertEquals(setOf("requestId"), dialog.requireArguments().keySet())
                ids += dialog.requireArguments().getString("requestId")!!
                assertTrue(dialog.requireArguments().toString().length < 500)
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
            }
            await(scenario)
            // Initial giant text is a private-file checkpoint; real edits remain independent.
            val initial = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
            assertEquals(bookmark.bookText, initial.bookText)
            input(scenario, "text", "Draft text")
            input(scenario, "content", "Draft note")
            val selectionDeadline = SystemClock.uptimeMillis() + 30_000
            val selectionProbe = InputProbe(scenario, "content", "Draft note")
            var selectionReceipt = "not observed"
            try {
                var node: android.view.accessibility.AccessibilityNodeInfo? = null
                while (node == null && SystemClock.uptimeMillis() < selectionDeadline) {
                    // Input can finish before the real IME shrinks the native window.
                    // Reveal the already focused note only if that transition hid it;
                    // never click it again or repeat the completed text input.
                    val visible = ownedNode(scenario, "content")
                    if (visible == null) {
                        selectionProbe.sample("selectionFieldMissing")
                        reveal(scenario, "content", selectionDeadline)
                    }
                    visible?.recycle()
                    frame(scenario, selectionDeadline, requireInputConnection = true)
                    node = actual(scenario, "content") { it.isEditable && it.isFocused && it.actionList.any { action -> action.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION } }
                    if (node == null) SystemClock.sleep(minOf(25L, (selectionDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                }
                assertNotNull("Owned focused note advertises selection action", node)
                try { assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    android.os.Bundle().apply {
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 5)
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                    })) } finally { node?.recycle() }
                until(selectionDeadline, "native and actual reverse note selection") {
                    var native = false
                    scenario.onActivity { host -> val value = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                        native = value.contentStart == 5 && value.contentEnd == 2 && value.focused == "content" }
                    var current = actual(scenario, "content") { it.isEditable && it.isFocused }
                    if (current == null && SystemClock.uptimeMillis() < selectionDeadline) {
                        val visible = ownedNode(scenario, "content")
                        if (visible == null) reveal(scenario, "content", selectionDeadline)
                        visible?.recycle()
                        frame(scenario, selectionDeadline, requireInputConnection = true)
                        current = actual(scenario, "content") { it.isEditable && it.isFocused }
                    }
                    val actual = current?.let { try { it.textSelectionStart == 5 && it.textSelectionEnd == 2 } finally { it.recycle() } } ?: false
                    selectionReceipt = "nativeReverseFocusACK=$native,actualReverseFocusACK=$actual"
                    native && actual
                }
            } catch (failure: AssertionError) {
                selectionProbe.fail(failure)
                failure.addSuppressed(AssertionError(selectionReceipt))
                throw failure
            }
            // Read the durable repository receipt rather than assuming debounce elapsed.
            driver.await("owned bookmark draft durable before recreate") {
                val value = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
                value.bookText == "Draft text" && value.content == "Draft note"
            }
            scenario.recreate()
            await(scenario)
            driver.await("actual restored note and raw selection") {
                driver.node("bookmark-editor-content")?.let { try { it.text?.toString() == "Draft note" && it.isFocused && it.textSelectionStart == 5 && it.textSelectionEnd == 2 } finally { it.recycle() } } ?: false
            }
            assertEquals("Chapter", chapterText(scenario))
            reveal(scenario, "text"); assertEquals("Draft text", driver.text("bookmark-editor-text"))
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("bookmark-test")
                        as BookmarkDialog
                assertEquals(ids.single(), dialog.requireArguments().getString("requestId"))
                assertEquals(5, dialog.model.state.value.contentStart)
                assertEquals(2, dialog.model.state.value.contentEnd)
            }
            val disk = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
            assertEquals(bookmark, disk.seed.bookmark())
            assertEquals("Draft text", disk.bookText)
            assertEquals("Draft note", disk.content)
            click(scenario, "cancel")
            await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertTrue(
                    appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()
                )
            }
        }
    }

    @Test
    fun actualConfirmInsertsCompleteBookmarkAndLegacyParcelableConvertsToSmallRequest() {
        val bookmark =
            Bookmark(
                System.nanoTime(),
                "Book-${UUID.randomUUID()}",
                "Author",
                4,
                21,
                "Chapter four",
                "Original",
                "Note",
            )
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog =
                    BookmarkDialog().apply {
                        arguments =
                            android.os.Bundle().apply {
                                putParcelable("bookmark", bookmark)
                                putInt("editPos", 0)
                            }
                    }
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
                ids += dialog.requireArguments().getString("requestId")!!
                assertFalse(dialog.requireArguments().containsKey("bookmark"))
            }
            await(scenario)
            input(scenario, "text", "")
            input(scenario, "content", " Edited ")
            click(scenario, "confirm")
            await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertEquals(
                    listOf(bookmark.copy(bookText = "", content = " Edited ")),
                    appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor),
                )
            }
        }
    }

    @Test
    fun missingParametersDismissWithoutCreatingDatabaseBookmark() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog()
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
                ids += dialog.requireArguments().getString("requestId")!!
            }
            await(scenario, true)
        }
    }
    @Test fun existingBookmarkDeleteUsesActualControlAndPreservesOtherRows() {
        val owned = Bookmark(System.nanoTime(), "Delete-${UUID.randomUUID()}", "Author", 3, 11, "Chapter", "Original", "Note")
        val other = owned.copy(time = owned.time + 1, bookName = "Other-${UUID.randomUUID()}")
        bookmarks += owned; bookmarks += other
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(owned, other) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(owned, 0)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); click(scenario, "delete"); await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertTrue(appDb.bookmarkDao.getByBook(owned.bookName, owned.bookAuthor).isEmpty())
                assertEquals(listOf(other), appDb.bookmarkDao.getByBook(other.bookName, other.bookAuthor))
            }
        }
    }
    @Test fun nativeBackDiscardsEditedDraftWithoutSavingAndKeepsParentAlive() {
        val bookmark = Bookmark(System.nanoTime(), "Back-${UUID.randomUUID()}", "Author", 2, 9, "Chapter", "Original", "Note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(bookmark)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); inputBackDraft(scenario, "Unsaved note")
            fun dialogPresent(): Boolean {
                var present = false
                scenario.onActivity { present = it.supportFragmentManager.findFragmentByTag("bookmark-test") != null }
                return present
            }
            fun imeVisible(): Boolean? {
                var visible: Boolean? = null
                scenario.onActivity { host ->
                    val decor = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)?.dialog?.window?.decorView
                    if (decor != null) visible = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                }; return visible
            }
            val deadline = SystemClock.uptimeMillis() + 30_000
            until(deadline, "Owned IME state known before real Back") { !dialogPresent() || imeVisible() != null }
            if (dialogPresent() && imeVisible() == true) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                until(deadline, "Real Back hides owned IME or cancels bookmark Dialog") { !dialogPresent() || imeVisible() == false }
            }
            if (dialogPresent()) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await(scenario, true)
            scenario.onActivity { assertFalse(it.isFinishing) }
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }
    @Test fun missingTicketRetryLoadsActualOriginalTicketAndCommitsFullMetadataOnce() {
        val bookmark = Bookmark(System.nanoTime(), "Retry-${UUID.randomUUID()}", "Author", 8, 19, "Retry chapter", "Original", "Note")
        bookmarks += bookmark
        val ticket = UUID.randomUUID().toString(); ids += ticket
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> BookmarkDialog().apply { arguments = android.os.Bundle().apply { putString("requestId", ticket) } }
                .showNow(host.supportFragmentManager, "bookmark-test") }
            driver.awaitIdentifier("bookmark-editor-retry")
            val draft = io.legado.app.data.repository.BookmarkEditorDraft(io.legado.app.data.repository.BookmarkEditorSeed.from(bookmark))
            runBlocking(Dispatchers.IO) {
                val file = File(context.filesDir, "bookmark-editor/$ticket.json"); file.parentFile!!.mkdirs()
                file.writeText(io.legado.app.utils.GSON.toJson(draft))
            }
            diagnosticClick(scenario, "retry")
            await(scenario)
            val probe = InputProbe(scenario, "chapter", bookmark.chapterName)
            try {
                driver.await("actual original ticket chapter") {
                    probe.sample("chapterReceipt")
                    chapterText(scenario) == bookmark.chapterName
                }
            } catch (failure: AssertionError) { probe.fail(failure); throw failure }
            click(scenario, "confirm"); await(scenario, true)
            runBlocking(Dispatchers.IO) { assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)) }
            val finished = runBlocking { FileBookmarkEditorRepository(context).load(ticket) }; assertTrue(finished.finished)
            scenario.recreate()
            scenario.onActivity { host -> BookmarkDialog().apply { arguments = android.os.Bundle().apply { putString("requestId", ticket) } }
                .showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario, true)
            runBlocking(Dispatchers.IO) { assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)) }
        }
    }

    @Test fun actualBackdropCancelsDraftImmediatelyWithoutSaving() {
        val bookmark = Bookmark(System.nanoTime(), "Backdrop-${UUID.randomUUID()}", "Author", 1, 2, "Chapter", "Original", "Note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(bookmark)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); input(scenario, "content", "Discard on backdrop")
            val outside = driver.bounds("bookmark-editor-outside")
            val heading = driver.bounds("bookmark-editor-heading")
            val window = android.graphics.Rect()
            scenario.onActivity { host -> (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!.decorView.getGlobalVisibleRect(window) }
            assertTrue(outside.intersect(window)); assertTrue(heading.top > outside.top + 8)
            click(scenario, "outside")
            await(scenario, true)
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }

}
