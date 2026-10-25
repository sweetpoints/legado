package io.legado.app.ui.rss.subscription

import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.Assert.*

/** Real normal Activity, rendered Flutter semantics and the existing Room/journal authority. */
class FlutterRuleSubscriptionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    private val marker = UUID.randomUUID().toString()
    private val fixture = RuleSub(System.currentTimeMillis() + 900000,
        "Subscription $marker", "https://subscription-$marker.invalid/", 0, -200000,
        js = "keep-js", showRule = "keep-show", sourceUrl = "keep-source")
    private var scenario: ActivityScenario<RuleSubActivity>? = null
    private val ownedIds = mutableSetOf<Long>()
    private var currentType = -1
    @Before fun prepare() { driver.enableAccessibility(); appDb.ruleSubDao.insert(fixture) }
    @After fun cleanup() {
        scenario?.close(); driver.close()
        appDb.ruleSubDao.delete(fixture)
        appDb.ruleSubDao.all.filter { it.id in ownedIds }.forEach { appDb.ruleSubDao.delete(it) }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, RuleSubActivity::class.java))
        ready()
    }
    private fun ready() {
        driver.await("subscription native bootstrap") {
            var loaded = false
            scenario!!.onActivity { activity ->
                loaded = activity.viewModel.state.value.loaded && !activity.viewModel.state.value.busy
                assertTrue(activity.supportFragmentManager.fragments.any { it is FlutterFragment })
            }
            loaded
        }
        var editing = false
        scenario!!.onActivity { editing = it.viewModel.state.value.editor != null }
        driver.awaitIdentifier(if (editing) "subscription-editor" else "subscription-add")
    }
    private fun acknowledged(label: String, check: (RuleSubscriptionState) -> Boolean) {
        driver.await(label) {
            var matched = false
            scenario!!.onActivity { matched = check(it.viewModel.state.value) }
            matched
        }
    }
    @Suppress("DEPRECATION")
    private fun reveal(id: String) {
        val frameReady = AtomicBoolean(true)
        driver.await("visible subscription control $id") {
            if (!frameReady.get()) return@await false
            driver.node(id)?.let { it.recycle(); return@await true }
            var node = driver.node("subscription-editor")
            while (node != null) {
                val current = node
                if (current.isScrollable) {
                    // These controls follow name/URL in the actual scrollable form.
                    val action = when (id) {
                        "subscription-auto", "subscription-silent", "subscription-interval" ->
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    if (current.performAction(action)) {
                        frameReady.set(false)
                        scenario!!.onActivity { activity ->
                            activity.window.decorView.postOnAnimation { frameReady.set(true) }
                        }
                    }
                    current.recycle()
                    return@await false
                }
                node = current.parent
                current.recycle()
            }
            false
        }
    }
    private fun click(id: String) { reveal(id); driver.click(id) }
    private fun input(field: String, value: String) {
        reveal("subscription-$field")
        if (currentType >= 0) probe("before-set-text", field)
        try {
            driver.setText("subscription-$field", value)
        } catch (failure: AssertionError) {
            if (currentType >= 0) probe("set-text-failed", field)
            throw failure
        }
        acknowledged("native subscription $field acknowledgement") { state ->
            val editor = state.editor
            when (field) { "name" -> editor?.name == value; "url" -> editor?.url == value
                else -> editor?.interval == value }
        }
        closeSoftKeyboard()
    }

    private fun within(deadline: Long, description: String, condition: () -> Boolean) {
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    @Suppress("DEPRECATION")
    private fun scrollEditorForward(deadline: Long) {
        var node = driver.node("subscription-editor")
        while (node != null) {
            val current = node
            if (current.isScrollable) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                        val committed = java.util.concurrent.CountDownLatch(1)
                        val callback = Runnable { committed.countDown() }
                        scenario!!.onActivity { activity ->
                            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback)
                            activity.window.decorView.postInvalidateOnAnimation()
                        }
                        try {
                            val remaining = deadline - SystemClock.uptimeMillis()
                            if (remaining > 0) committed.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } finally {
                            scenario!!.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
                        }
                    }
                } finally { current.recycle() }
                return
            }
            node = current.parent
            current.recycle()
        }
    }

    /** Re-enabling native automatic state precedes the rendered TextField's enabled frame. */
    @Suppress("DEPRECATION")
    private fun inputReenabledInterval(value: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        val id = "subscription-interval"
        try {
            within(deadline, "rendered re-enabled interval accepts its own click") {
                val candidate = driver.node(id)
                if (candidate == null) scrollEditorForward(deadline)
                candidate?.let {
                    val ready = it.isEnabled && it.isEditable && it.isClickable &&
                        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
                    it.recycle(); ready
                } ?: false
            }
            val target = requireNotNull(driver.node(id))
            try {
                assertTrue(target.isEnabled && target.isEditable && target.isClickable)
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } finally { target.recycle() }
            val frameReady = AtomicBoolean(false)
            var requestedFrame = false
            within(deadline, "re-enabled interval focused editable ACTION_SET_TEXT and frame") {
                val ready = driver.node(id)?.let {
                    val focused = it.isEnabled && it.isEditable && it.isFocused &&
                        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    it.recycle(); focused
                } ?: false
                if (ready && !requestedFrame) {
                    requestedFrame = true
                    scenario!!.onActivity { activity ->
                        activity.window.decorView.postOnAnimation { frameReady.set(true) }
                    }
                }
                ready && frameReady.get()
            }
            val focused = requireNotNull(driver.node(id))
            try {
                assertTrue(focused.isEnabled && focused.isEditable && focused.isFocused)
                assertTrue(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            } finally { focused.recycle() }
            within(deadline, "rendered interval text and native interval acknowledgement") {
                val rendered = driver.node(id)?.let {
                    val matched = it.text?.toString() == value
                    it.recycle(); matched
                } ?: false
                var native = false
                scenario!!.onActivity { native = it.viewModel.state.value.editor?.interval == value }
                rendered && native
            }
            closeSoftKeyboard()
            assertTrue("Re-enabled interval input stayed within its original total budget",
                SystemClock.uptimeMillis() < deadline)
        } catch (failure: AssertionError) {
            probe("reenabled-interval-input-failed", "interval")
            throw failure
        }
    }

    /** Scalar diagnostics only: never serialize field text, URLs, row IDs or private draft paths. */
    @Suppress("DEPRECATION")
    private fun probe(phase: String, field: String) {
        var native = "unavailable"
        scenario!!.onActivity {
            val state = it.viewModel.state.value
            val editor = state.editor
            val host = it.window.decorView
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(host)
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            native = "loaded=${state.loaded},loading=${state.loading},busy=${state.busy}," +
                "pending=${state.pendingSave},editor=${editor != null},type=${editor?.type}," +
                "auto=${editor?.automatic},silent=${editor?.silent}," +
                "silentEnabled=${editor?.silentEnabled},intervalLength=${editor?.interval?.length},intervalZero=${editor?.interval == "0"}," +
                "windowFocus=${host.hasWindowFocus()},lifecycle=${it.lifecycle.currentState}," +
                "imeVisible=${insets?.isVisible(ime)},imeBottom=${insets?.getInsets(ime)?.bottom}"
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val viewport = Rect().also { root?.getBoundsInScreen(it) }
        Log.i("RuleSubUiProbe", "phase=$phase,currentType=$currentType,field=$field,$native,viewport=$viewport")
        val targets = setOf("subscription-type", "subscription-type-$currentType",
            "subscription-auto", "subscription-silent", "subscription-interval", "subscription-editor")
        fun scalar(node: AccessibilityNodeInfo): String {
            val bounds = Rect().also(node::getBoundsInScreen)
            val intersection = Rect(bounds)
            val intersects = intersection.intersect(viewport)
            return "class=${node.className},visible=${node.isVisibleToUser},enabled=${node.isEnabled},zeroText=${node.text?.toString() == "0"}," +
                "checkable=${node.isCheckable},checked=${node.isChecked}," +
                "editable=${node.isEditable},clickable=${node.isClickable},focused=${node.isFocused}," +
                "scrollable=${node.isScrollable},bounds=$bounds,intersects=$intersects," +
                "intersection=$intersection,actions=${node.actionList.map { it.id }}"
        }
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        root?.let(nodes::add)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val id = node.viewIdResourceName
            if (id in targets) {
                Log.i("RuleSubUiProbe", "target=$id,${scalar(node)}")
                if (id == "subscription-interval" || id == "subscription-silent" || id == "subscription-type-$currentType") {
                    val children = ArrayDeque<AccessibilityNodeInfo>()
                    for (index in 0 until node.childCount) node.getChild(index)?.let(children::add)
                    var count = 0
                    while (children.isNotEmpty() && count < 24) {
                        val child = children.removeFirst()
                        Log.i("RuleSubUiProbe", "target=$id,descendant=${count++},${scalar(child)}")
                        for (index in 0 until child.childCount) child.getChild(index)?.let(children::add)
                        child.recycle()
                    }
                    children.forEach { it.recycle() }
                }
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 6) {
                    val current = parent
                    Log.i("RuleSubUiProbe", "target=$id,ancestor=${depth++},${scalar(current)}")
                    parent = current.parent
                    current.recycle()
                }
                parent?.recycle()
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            node.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun revealRow(id: Long) {
        val frameReady = AtomicBoolean(true)
        driver.await("visible saved subscription row") {
            if (!frameReady.get()) return@await false
            driver.node("subscription-row-$id")?.let { it.recycle(); return@await true }
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            driver.node("subscription-list")?.let(nodes::add)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isVisibleToUser && node.isScrollable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    node.recycle(); nodes.forEach { it.recycle() }
                    frameReady.set(false)
                    scenario!!.onActivity { activity ->
                        activity.window.decorView.postOnAnimation { frameReady.set(true) }
                    }
                    return@await false
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                node.recycle()
            }
            false
        }
    }

    /** Use the actual localized accessibility label, scoped to the requested row/menu. */
    @Suppress("DEPRECATION")
    private fun clickLabel(english: String, chinese: String, rowId: Long? = null) {
        val label = if (context.resources.configuration.locales[0].language == "zh") chinese else english
        driver.await("enabled subscription action $english") {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            (if (rowId == null) instrumentation.uiAutomation.rootInActiveWindow
                else driver.node("subscription-row-$rowId"))?.let(nodes::add)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isVisibleToUser && node.isEnabled &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                    nodes.forEach { it.recycle() }
                    var action: AccessibilityNodeInfo? = node
                    while (action != null) {
                        val current = action
                        if (current.isVisibleToUser && current.isEnabled && current.isClickable &&
                            current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            current.recycle(); return@await true
                        }
                        action = current.parent
                        current.recycle()
                    }
                    return@await false
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                node.recycle()
            }
            false
        }
    }

    /** The closed button also contains its selected item ID. Select only within the real popup. */
    @Suppress("DEPRECATION")
    private fun selectCreatedType(type: Int) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        fun findWithin(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            nodes.add(AccessibilityNodeInfo.obtain(root))
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isVisibleToUser && node.viewIdResourceName == id) {
                    nodes.forEach { it.recycle() }; return node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                node.recycle()
            }
            return null
        }
        fun popup(): AccessibilityNodeInfo? {
            // A newly created editor selects 0. Options 1 and 2 cannot be closed-button content.
            var node = driver.node("subscription-type-1") ?: return null
            while (true) {
                if (node.isVisibleToUser) {
                    val other = findWithin(node, "subscription-type-2")
                    val closedButton = findWithin(node, "subscription-type")
                    val matches = other != null && closedButton == null
                    other?.recycle(); closedButton?.recycle()
                    if (matches) return node
                }
                val parent = node.parent
                node.recycle()
                node = parent ?: return null
            }
        }
        val frameReady = AtomicBoolean(false)
        var requestedFrame = false
        var dispatched = false
        var completed = false
        while (SystemClock.uptimeMillis() < deadline && !completed) {
            if (!dispatched) {
                val menu = popup()
                if (menu != null) {
                    if (!requestedFrame) {
                        requestedFrame = true
                        scenario!!.onActivity { activity ->
                            activity.window.decorView.postOnAnimation { frameReady.set(true) }
                        }
                    } else if (frameReady.get()) {
                        var action = findWithin(menu, "subscription-type-$type")
                        while (action != null && action != menu) {
                            val current = action
                            if (current.isVisibleToUser && current.isEnabled && current.isClickable &&
                                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                dispatched = true; current.recycle(); action = null; break
                            }
                            action = current.parent
                            current.recycle()
                        }
                        if (action == menu) action.recycle()
                    }
                    menu.recycle()
                }
            } else {
                val popupGone = (0..2).filter { it != type }.all { option ->
                    driver.node("subscription-type-$option")?.let { it.recycle(); false } ?: true
                }
                val formVisible = driver.node("subscription-editor")?.let { it.recycle(); true } ?: false
                var acknowledged = false
                scenario!!.onActivity {
                    acknowledged = !it.viewModel.state.value.busy &&
                        it.viewModel.state.value.editor?.type == type
                }
                completed = popupGone && formVisible && acknowledged
            }
            if (!completed) SystemClock.sleep(50)
        }
        if (!completed) probe("type-popup-selection-failed", "type")
        assertTrue("Actual subscription popup selection and native type acknowledgement", completed)
    }

    /** One JUnit method executes a real create/save/reopen/edit/save loop for each of 0, 1 and 2. */
    @Test fun allThreeTypesCreatePersistAndReopenFromTheNormalFlutterActivity() {
        launch()
        for (type in 0..2) {
            currentType = type
            val name = "Created $type $marker"
            val url = "https://created-$marker.invalid/$type"
            driver.click("subscription-add")
            driver.awaitIdentifier("subscription-editor")
            scenario!!.onActivity { ownedIds += it.viewModel.state.value.editor!!.newId }
            input("name", name); input("url", url)
            click("subscription-type"); probe("type-popup-open", "type")
            selectCreatedType(type); probe("type-choice-dispatched", "type")
            acknowledged("created subscription type $type") { it.editor?.type == type }
            click("subscription-auto")
            acknowledged("created automatic interval default") {
                it.editor?.automatic == true && it.editor?.interval == "24"
            }
            probe("automatic-acknowledged", "interval")
            input("interval", (24 + type).toString())
            click("subscription-silent")
            acknowledged("created silent update") { it.editor?.silent == true }
            driver.click("subscription-save")
            acknowledged("created subscription saved") { it.editor == null && !it.busy }
            val saved = appDb.ruleSubDao.all.single { it.url == url }
            assertTrue(saved.id in ownedIds)
            assertEquals(name, saved.name); assertEquals(type, saved.type)
            assertTrue(saved.autoUpdate); assertTrue(saved.silentUpdate)
            assertEquals(24 + type, saved.updateInterval)
            assertNull(saved.js); assertNull(saved.showRule); assertNull(saved.sourceUrl)

            // These fields are owned by native import/update paths, not editable in this form.
            val metadata = saved.copy(js = "js-$type", showRule = "show-$type", sourceUrl = "source-$type")
            appDb.ruleSubDao.update(metadata)
            revealRow(saved.id)
            driver.click("subscription-edit-${saved.id}")
            driver.awaitIdentifier("subscription-editor")
            acknowledged("saved fields reopened for type $type") {
                it.editor?.name == name && it.editor?.url == url && it.editor?.type == type &&
                    it.editor?.automatic == true && it.editor?.interval == (24 + type).toString() &&
                    it.editor?.silent == true
            }
            reveal("subscription-name"); assertEquals(name, driver.text("subscription-name"))
            reveal("subscription-url"); assertEquals(url, driver.text("subscription-url"))
            input("name", "Edited $type $marker")
            driver.click("subscription-save")
            acknowledged("reopened subscription edit saved") { it.editor == null && !it.busy }
            val edited = appDb.ruleSubDao.all.single { it.id == saved.id }
            assertEquals("Edited $type $marker", edited.name)
            assertEquals(url, edited.url); assertEquals(type, edited.type)
            assertTrue(edited.autoUpdate); assertTrue(edited.silentUpdate)
            assertEquals(24 + type, edited.updateInterval)
            assertEquals(metadata.js, edited.js); assertEquals(metadata.showRule, edited.showRule)
            assertEquals(metadata.sourceUrl, edited.sourceUrl)
            assertEquals(metadata.update, edited.update)
            assertEquals(metadata.customOrder, edited.customOrder)
        }
    }

    @Test fun accessibleReorderAndDeletePersistThroughTheNormalFlutterList() {
        val second = fixture.copy(id = fixture.id + 1, name = "Second $marker",
            url = "https://second-$marker.invalid/", type = 1, customOrder = fixture.customOrder + 1)
        val third = fixture.copy(id = fixture.id + 2, name = "Third $marker",
            url = "https://third-$marker.invalid/", type = 2, customOrder = fixture.customOrder + 2)
        ownedIds += second.id; ownedIds += third.id
        appDb.ruleSubDao.insert(second, third)
        val ids = setOf(fixture.id, second.id, third.id)
        val otherIds = appDb.ruleSubDao.all.map { it.id }.filter { it !in ids }.toSet()
        fun expectedOrder(expected: List<Long>) = acknowledged("subscription order committed to Room") {
            !it.busy && it.rows.filter { row -> row.id in ids }.map { row -> row.id } == expected &&
                appDb.ruleSubDao.all.filter { row -> row.id in ids }.map { row -> row.id } == expected
        }
        launch()
        revealRow(second.id)
        clickLabel("Actions", "更多操作", second.id)
        clickLabel("Move up", "上移")
        expectedOrder(listOf(second.id, fixture.id, third.id))
        clickLabel("Actions", "更多操作", second.id)
        clickLabel("Move down", "下移")
        expectedOrder(listOf(fixture.id, second.id, third.id))
        scenario!!.recreate(); ready()
        expectedOrder(listOf(fixture.id, second.id, third.id))
        revealRow(second.id)
        clickLabel("Actions", "更多操作", second.id)
        clickLabel("Delete", "删除")
        acknowledged("only requested subscription deleted") {
            !it.busy && it.rows.none { row -> row.id == second.id } &&
                appDb.ruleSubDao.all.none { row -> row.id == second.id }
        }
        val remaining = appDb.ruleSubDao.all
        assertTrue(remaining.any { it.id == fixture.id }); assertTrue(remaining.any { it.id == third.id })
        assertEquals(otherIds, remaining.map { it.id }.filter { it !in ids }.toSet())
        assertEquals(fixture.url, remaining.single { it.id == fixture.id }.url)
        assertEquals(third.js, remaining.single { it.id == third.id }.js)
        scenario!!.recreate(); ready()
        acknowledged("deletion survives recreation") { it.rows.none { row -> row.id == second.id } }
    }
    @Test fun normalFlutterEditorSavesAllFieldsAndPreservesNativeMetadata() {
        fun clickSilentReady() {
            val deadline = SystemClock.uptimeMillis() + 30_000
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            val animations = mutableSetOf<androidx.core.view.WindowInsetsAnimationCompat>()
            var viewport = false to 0
            scenario!!.onActivity { activity ->
                androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(activity.window.decorView,
                    object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                            if (animation.typeMask and ime != 0) animations.add(animation)
                        }
                        override fun onProgress(insets: androidx.core.view.WindowInsetsCompat,
                            runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): androidx.core.view.WindowInsetsCompat {
                            animations.addAll(runningAnimations.filter { it.typeMask and ime != 0 })
                            return insets
                        }
                        override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) { animations.remove(animation) }
                    })
            }
            fun nativeReady(): Boolean {
                var ready = false
                scenario!!.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val editor = state.editor
                    val host = activity.window.decorView
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(host)
                    if (insets != null) viewport = insets.isVisible(ime) to insets.getInsets(ime).bottom
                    ready = state.loaded && !state.loading && !state.busy && !state.pendingSave &&
                        editor?.type == 2 && editor.automatic && editor.silentEnabled && !editor.silent &&
                        insets != null && animations.isEmpty() && host.isAttachedToWindow &&
                        host.isHardwareAccelerated && host.hasWindowFocus()
                }
                return ready
            }
            fun actualSwitch(): AccessibilityNodeInfo? {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                driver.node("subscription-silent")?.let(queue::add) ?: return null
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node.isCheckable) { queue.forEach { it.recycle() }; return node }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                    node.recycle()
                }
                return null
            }
            fun frame(): Boolean {
                val committed = java.util.concurrent.CountDownLatch(1)
                val callback = Runnable { committed.countDown() }
                scenario!!.onActivity {
                    it.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback)
                    it.window.decorView.postInvalidateOnAnimation()
                }
                try {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    return remaining > 0 && committed.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                } finally {
                    scenario!!.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
                }
            }
            fun formBounds(): Rect? = driver.node("subscription-editor")?.let { node ->
                try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
            }
            fun actionable(node: AccessibilityNodeInfo?, form: Rect?): Boolean {
                if (node == null || form == null) return false
                val bounds = Rect().also(node::getBoundsInScreen)
                return node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isChecked &&
                    !bounds.isEmpty && form.contains(bounds) &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            try {
                while (SystemClock.uptimeMillis() < deadline) {
                    if (nativeReady()) {
                        val before = actualSwitch()
                        val form = formBounds()
                        val bounds = Rect().also { before?.getBoundsInScreen(it) }
                        val inView = before?.isVisibleToUser == true && !bounds.isEmpty && form?.contains(bounds) == true
                        val visible = try { actionable(before, form) } finally { before?.recycle() }
                        val stableViewport = viewport
                        if (visible && frame() && nativeReady() && viewport == stableViewport) {
                            val fresh = actualSwitch()
                            try {
                                if (actionable(fresh, formBounds())) {
                                    assertTrue("Silent click budget exhausted", SystemClock.uptimeMillis() < deadline)
                                    assertTrue("Actual silent switch rejected its single click",
                                        requireNotNull(fresh).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                    return
                                }
                            } finally { fresh?.recycle() }
                        } else if (!inView) {
                            // The IME can resize after reveal: find the form's own scroller
                            // again, never click an ancestor or a dialog scrim.
                            var node = driver.node("subscription-editor")
                            while (node != null) {
                                val current = node
                                if (current.isScrollable) {
                                    try {
                                        val action = if (form != null && !bounds.isEmpty && bounds.top < form.top)
                                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                        if (current.performAction(action)) frame()
                                    }
                                    finally { current.recycle() }
                                    break
                                }
                                node = current.parent
                                current.recycle()
                            }
                        }
                    }
                    val remaining = deadline - SystemClock.uptimeMillis()
                    if (remaining > 0) SystemClock.sleep(minOf(50, remaining))
                }
                fail("Actual silent switch readiness deadline")
            } finally {
                scenario!!.onActivity { androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(it.window.decorView, null) }
            }
        }
        fun inputZeroInterval() {
            reveal("subscription-interval")
            val deadline = SystemClock.uptimeMillis() + 30_000
            val id = "subscription-interval"
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            within(deadline, "actual interval accepts focus before disabling itself") {
                driver.node(id)?.let { node ->
                    try { node.isEnabled && node.isEditable && node.isClickable &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } }
                    finally { node.recycle() }
                } ?: false
            }
            requireNotNull(driver.node(id)).let { node ->
                try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { node.recycle() }
            }
            within(deadline, "focused interval advertises its text action") {
                driver.node(id)?.let { node ->
                    try { node.isFocused && node.isEditable && node.isEnabled &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
                    finally { node.recycle() }
                } ?: false
            }
            // Zero disables this very TextField. Settle the IME viewport before
            // that state transition so its value remains available for the assertion.
            scenario!!.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(ime)
            }
            within(deadline, "interval IME hidden before zero changes editability") {
                var hidden = false
                scenario!!.onActivity { activity ->
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    hidden = insets != null && !insets.isVisible(ime) && insets.getInsets(ime).bottom == 0
                }
                hidden
            }
            val frame = java.util.concurrent.CountDownLatch(1)
            val callback = Runnable { frame.countDown() }
            scenario!!.onActivity { activity ->
                activity.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback)
                activity.window.decorView.postInvalidateOnAnimation()
            }
            try {
                val remaining = deadline - SystemClock.uptimeMillis()
                assertTrue("Zero interval frame within input budget", remaining > 0 &&
                    frame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS))
            } finally {
                scenario!!.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
            }
            requireNotNull(driver.node(id)).let { node ->
                try {
                    assertTrue(node.isFocused && node.isEditable && node.isEnabled)
                    assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0")
                    }))
                } finally { node.recycle() }
            }
            within(deadline, "actual zero interval text and native disabled state") {
                val valueNode = driver.node(id)
                val rendered = try { valueNode?.text?.toString() == "0" } finally { valueNode?.recycle() }
                if (valueNode == null) scrollEditorForward(deadline)
                var accepted = false
                scenario!!.onActivity { activity ->
                    accepted = activity.viewModel.state.value.editor?.let {
                        it.interval == "0" && !it.automatic && !it.silent && !it.silentEnabled
                    } == true
                }
                rendered && accepted
            }
        }
        fun clickAutomaticAfterZero() {
            val frameReady = AtomicBoolean(true)
            var trace = "not observed"
            var scrollAttempts = 0
            var scrollAccepted = 0
            try {
            driver.await("visible enabled automatic control above zero interval") {
                if (!frameReady.get()) return@await false
                driver.node("subscription-auto")?.let { control ->
                    trace = "automatic identifier present; no checkable descendant"
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    pending.add(control)
                    try {
                        while (pending.isNotEmpty()) {
                            val current = pending.removeFirst()
                            try {
                                if (current.isCheckable) {
                                    val bounds = Rect(); current.getBoundsInScreen(bounds)
                                    trace = "checkable visible=${current.isVisibleToUser},enabled=${current.isEnabled}," +
                                        "checked=${current.isChecked},click=${current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }},bounds=$bounds"
                                }
                                if (current.isVisibleToUser && current.isEnabled && current.isCheckable &&
                                    !current.isChecked && current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                                    // One real action on the automatic switch, never on an ancestor.
                                    assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                    return@await true
                                }
                                for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
                            } finally { current.recycle() }
                        }
                    } finally { pending.forEach { it.recycle() } }
                    return@await false
                }
                var node = driver.node("subscription-editor")
                while (node != null) {
                    val current = node
                    if (current.isScrollable) {
                        try {
                            // Zero was just revealed at the bottom. Automatic is above it;
                            // CI's owned ScrollView advertised only BACKWARD at this point.
                            val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            val bounds = Rect(); current.getBoundsInScreen(bounds)
                            trace = "automatic identifier absent; scroll owner bounds=$bounds,actions=${current.actionList.map { it.id }}"
                            if (current.actionList.any { it.id == backward }) scrollAttempts++
                            if (current.actionList.any { it.id == backward } && current.performAction(backward)) {
                                scrollAccepted++
                                frameReady.set(false)
                                scenario!!.onActivity { activity ->
                                    activity.window.decorView.postOnAnimation { frameReady.set(true) }
                                }
                            }
                        } finally { current.recycle() }
                        return@await false
                    }
                    node = current.parent
                    current.recycle()
                }
                false
            }
            } catch (failure: AssertionError) {
                probe("automatic-after-zero-failed", "auto")
                throw AssertionError("${failure.message}; frameReady=${frameReady.get()}," +
                    "scrollAttempts=$scrollAttempts,scrollAccepted=$scrollAccepted;$trace", failure)
            }
        }
        currentType = 0
        launch()
        driver.click("subscription-edit-${fixture.id}")
        driver.awaitIdentifier("subscription-editor")
        input("name", "Edited $marker")
        input("url", "https://edited-$marker.invalid/")
        click("subscription-type")
        selectCreatedType(2)
        currentType = 2
        acknowledged("replacement subscription type") { it.editor?.type == 2 }
        click("subscription-auto")
        acknowledged("automatic first enable defaults to 24 hours") { it.editor?.automatic == true && it.editor?.interval == "24" }
        probe("before-first-silent-click", "silent")
        try {
            clickSilentReady()
        } catch (failure: AssertionError) {
            probe("first-silent-click-failed", "silent")
            throw failure
        }
        acknowledged("silent update enabled") { it.editor?.silent == true }
        try { inputZeroInterval() } catch (failure: AssertionError) {
            probe("zero-input-failed", "interval")
            throw failure
        }
        acknowledged("zero interval disables automatic and silent updates") {
            it.editor?.automatic == false && it.editor?.silent == false && it.editor?.silentEnabled == false
        }
        probe("zero-interval-disabled", "interval")
        clickAutomaticAfterZero()
        acknowledged("automatic re-enable") { it.editor?.interval == "24" && it.editor?.automatic == true }
        probe("automatic-reenabled", "interval")
        inputReenabledInterval("48")
        click("subscription-silent")
        acknowledged("silent restored") { it.editor?.silent == true }
        driver.click("subscription-save")
        acknowledged("journal save completed") { it.editor == null && !it.busy }
        val saved = appDb.ruleSubDao.all.single { it.id == fixture.id }
        assertEquals("Edited $marker", saved.name)
        assertEquals("https://edited-$marker.invalid/", saved.url)
        assertEquals(2, saved.type); assertTrue(saved.autoUpdate)
        assertEquals(48, saved.updateInterval); assertTrue(saved.silentUpdate)
        assertEquals(fixture.js, saved.js); assertEquals(fixture.showRule, saved.showRule)
        assertEquals(fixture.sourceUrl, saved.sourceUrl)
    }
    @Test fun acknowledgedPrivateDraftSurvivesRecreationAndCancelNeverWritesRoom() {
        launch(); driver.click("subscription-add"); driver.awaitIdentifier("subscription-editor")
        input("name", "Private $marker"); input("url", "https://private-$marker.invalid/")
        scenario!!.recreate(); ready(); driver.awaitIdentifier("subscription-editor")
        acknowledged("private draft restored") {
            it.editor?.name == "Private $marker" && it.editor?.url == "https://private-$marker.invalid/"
        }
        driver.click("subscription-cancel")
        acknowledged("cancel clears private editor") { it.editor == null }
        assertFalse(appDb.ruleSubDao.all.any { it.url == "https://private-$marker.invalid/" })
        assertEquals(fixture.name, appDb.ruleSubDao.all.single { it.id == fixture.id }.name)
    }
}
