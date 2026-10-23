package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.rssSourceManagementId
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

/** Normal Android entry + actual Flutter input and Room; historical class name is retained. */
class RssSourceActivityComposeTest {
    // Keep the original waitUntil default budget for all original native-state assertions.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    private val preferences get() = context.defaultSharedPreferences
    private val originalLanguage = preferences.getString(PreferKey.language, null)
    private val hadBackup = preferences.contains(PreferKey.autoCheckNewBackup)
    private val originalBackup = preferences.getBoolean(PreferKey.autoCheckNewBackup, true)
    private val hadPrivacy = LocalConfig.contains("privacyPolicyOk")
    private val originalPrivacy = LocalConfig.getBoolean("privacyPolicyOk", false)
    private val hadVersion = LocalConfig.contains("appVersionCode")
    private val originalVersion = LocalConfig.getLong("appVersionCode", 0)

    @Before fun setupFlutterInput() {
        preferences.edit().putString(PreferKey.language, "en")
            .putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode).commit()
        driver.enableAccessibility()
    }

    @After fun restoreInputAndPreferences() {
        try { driver.close() } finally {
            preferences.edit().apply {
                if (originalLanguage == null) remove(PreferKey.language) else putString(PreferKey.language, originalLanguage)
                if (hadBackup) putBoolean(PreferKey.autoCheckNewBackup, originalBackup) else remove(PreferKey.autoCheckNewBackup)
            }.commit()
            LocalConfig.edit().apply {
                if (hadPrivacy) putBoolean("privacyPolicyOk", originalPrivacy) else remove("privacyPolicyOk")
                if (hadVersion) putLong("appVersionCode", originalVersion) else remove("appVersionCode")
            }.commit()
        }
    }

    private fun filtered(scenario: ActivityScenario<RssSourceActivity>, group: String, rows: Int) {
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.managementModel.state.value.loaded }
            loaded
        }
        driver.setText("rss-manager-query", "group:$group")
        compose.waitUntil {
            var ready = false
            scenario.onActivity {
                val state = it.managementModel.state.value
                ready = state.query == "group:$group" && state.rows.size == rows
            }
            ready
        }
        closeSoftKeyboard()
    }

    @Suppress("DEPRECATION")
    private fun textNode(value: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add) ?: return null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (node.text?.toString() == value || node.contentDescription?.toString() == value)) {
                queue.forEach { it.recycle() }
                return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            node.recycle()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun clickLabel(value: String) {
        // Labels here are fixed UI strings, never source bodies, URLs or fixture identifiers.
        driver.await("visible dialog/control label") { textNode(value)?.let { it.recycle(); true } ?: false }
        val node = requireNotNull(textNode(value))
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.recycle()
                return
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        fail("Visible control did not accept its click")
    }

    private fun frame(scenario: ActivityScenario<RssSourceActivity>) {
        val committed = CountDownLatch(1)
        scenario.onActivity {
            val root = it.window.decorView
            assertTrue(root.isAttachedToWindow && root.isHardwareAccelerated)
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("Flutter host frame did not commit", committed.await(5, TimeUnit.SECONDS))
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
    }

    private inner class Pointer(private var x: Float, private var y: Float) : AutoCloseable {
        private val downTime = SystemClock.uptimeMillis()
        private var active = true
        init { send(MotionEvent.ACTION_DOWN) }
        private fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        fun moveTo(nextX: Float, nextY: Float) {
            val startX = x
            val startY = y
            // Preserve the original 100ms movement while delivering actual Android input.
            for (step in 1..20) {
                x = startX + (nextX - startX) * step / 20
                y = startY + (nextY - startY) * step / 20
                send(MotionEvent.ACTION_MOVE)
                SystemClock.sleep(5)
            }
        }
        fun cancel() {
            if (active) { active = false; send(MotionEvent.ACTION_CANCEL) }
        }
        fun release() {
            if (active) { active = false; send(MotionEvent.ACTION_UP) }
        }
        // An exception while a pointer is held must roll back the gesture, not commit it.
        override fun close() = cancel()
    }

    @Test
    fun urlDraftAndSelectionSurviveActivityRecreationAndCancelDoesNotImport() {
        val group = "Management-${UUID.randomUUID()}"
        val source = RssSource(
            sourceUrl = "https://$group.invalid", sourceName = "Managed", sourceGroup = group,
            enabled = false, sourceComment = "Keep", ruleContent = "body@text",
        )
        appDb.rssSourceDao.insert(source)
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                filtered(scenario, group, 1)
                val id = rssSourceManagementId(source.sourceUrl)
                driver.click("rss-manager-select-$id")
                compose.waitUntil {
                    var selected = false
                    scenario.onActivity { selected = it.managementModel.state.value.selected == setOf(id) }
                    selected
                }
                driver.click("rss-manager-menu")
                driver.click("rss-manager-action:url")
                val draft = "https://draft.invalid/import.json"
                fun draftDiagnostic(phase: String) {
                    var native = ""
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        val root = it.window.decorView
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                        val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
                        native = "nativeLength=${state.draft.length} nativeMatches=${state.draft == draft} " +
                            "nativeSelection=${state.draftStart}..${state.draftEnd} dialog=${state.dialog?.name} " +
                            "busy=${state.busy} pending=${state.pending != null} waitingNative=${state.waitingNative} " +
                            "error=${state.error != null} windowFocus=${root.hasWindowFocus()} " +
                            "imeVisible=${insets?.isVisible(ime)} imeBottom=${insets?.getInsets(ime)?.bottom} " +
                            "lifecycle=${it.lifecycle.currentState}"
                    }
                    val rendered = driver.node("rss-manager-draft")
                    try {
                        val scalar = if (rendered == null) "renderedPresent=false" else {
                            val bounds = android.graphics.Rect().also(rendered::getBoundsInScreen)
                            val text = rendered.text?.toString().orEmpty()
                            "renderedPresent=true renderedLength=${text.length} renderedMatches=${text == draft} " +
                                "renderedSelection=${rendered.textSelectionStart}..${rendered.textSelectionEnd} " +
                                "focused=${rendered.isFocused} editable=${rendered.isEditable} " +
                                "enabled=${rendered.isEnabled} bounds=$bounds actions=${rendered.actionList.map { it.id }}"
                        }
                        // Scalars only: no draft, fixture ID, URL, or private path.
                        android.util.Log.i("RssDraftInput", "phase=$phase $native $scalar")
                    } finally { rendered?.recycle() }
                }
                // Focus, actual IME readiness, text ACK and selection dispatch share the
                // original text-input 30s budget. The following native assertion stays 1s.
                val inputDeadline = SystemClock.uptimeMillis() + 30_000
                val animations = mutableSetOf<androidx.core.view.WindowInsetsAnimationCompat>()
                val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()
                scenario.onActivity {
                    androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(it.window.decorView,
                        object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                            override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                                if (animation.typeMask and imeType != 0) animations.add(animation)
                            }
                            override fun onProgress(insets: androidx.core.view.WindowInsetsCompat,
                                runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): androidx.core.view.WindowInsetsCompat {
                                animations.addAll(runningAnimations.filter { it.typeMask and imeType != 0 })
                                return insets
                            }
                            override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) { animations.remove(animation) }
                        })
                }
                fun pauseInput() {
                    val remaining = inputDeadline - SystemClock.uptimeMillis()
                    if (remaining > 0) SystemClock.sleep(minOf(50, remaining))
                }
                fun readyField(textAcknowledged: Boolean): AccessibilityNodeInfo? {
                    var nativeReady = false
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        val root = it.window.decorView
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                        nativeReady = root.isAttachedToWindow && root.isHardwareAccelerated && root.hasWindowFocus() &&
                            insets?.isVisible(imeType) == true && insets.getInsets(imeType).bottom > 0 && animations.isEmpty() &&
                            state.dialog == RssSourceManagementDialog.ImportUrl && !state.busy && state.pending == null &&
                            !state.waitingNative && (!textAcknowledged ||
                                (state.draft == draft && state.draftStart == draft.length && state.draftEnd == draft.length))
                    }
                    val node = driver.node("rss-manager-draft") ?: return null
                    val actions = node.actionList.map { it.id }
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    val ready = nativeReady && node.isVisibleToUser && node.isFocused && node.isEditable && node.isEnabled &&
                        !bounds.isEmpty && AccessibilityNodeInfo.ACTION_SET_SELECTION in actions &&
                        AccessibilityNodeInfo.ACTION_SET_TEXT in actions && (!textAcknowledged ||
                            (node.text?.toString() == draft && node.textSelectionStart == draft.length && node.textSelectionEnd == draft.length))
                    if (ready) return node
                    node.recycle()
                    return null
                }
                fun framedField(textAcknowledged: Boolean): AccessibilityNodeInfo {
                    while (SystemClock.uptimeMillis() < inputDeadline) {
                        val candidate = readyField(textAcknowledged)
                        if (candidate == null) { pauseInput(); continue }
                        candidate.recycle()
                        val committed = CountDownLatch(1)
                        val callback = Runnable { committed.countDown() }
                        scenario.onActivity {
                            it.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback)
                            it.window.decorView.postInvalidateOnAnimation()
                        }
                        try {
                            val remaining = inputDeadline - SystemClock.uptimeMillis()
                            if (remaining <= 0 || !committed.await(remaining, TimeUnit.MILLISECONDS)) break
                        } finally {
                            scenario.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
                        }
                        // Host frame is not a Dart-frame ACK: re-read Flutter's advertised
                        // actions, actual text/selection and native/IME state after that frame.
                        readyField(textAcknowledged)?.let { return it }
                    }
                    draftDiagnostic("inputReadinessDeadline")
                    error("RSS draft input readiness deadline")
                }
                try {
                    var focusClicked = false
                    while (!focusClicked && SystemClock.uptimeMillis() < inputDeadline) {
                        val node = driver.node("rss-manager-draft")
                        try {
                            if (node != null && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                                assertTrue("Draft focus input deadline", SystemClock.uptimeMillis() < inputDeadline)
                                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                focusClicked = true
                            }
                        } finally { node?.recycle() }
                        if (!focusClicked) pauseInput()
                    }
                    assertTrue("Draft focus click deadline", focusClicked)
                    val textField = framedField(false)
                    try {
                        assertTrue("Draft text input deadline", SystemClock.uptimeMillis() < inputDeadline)
                        assertTrue(textField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                        }))
                    } finally { textField.recycle() }
                    draftDiagnostic("textRenderedBeforeSelectionBarrier")
                    val selectionField = framedField(true)
                    try {
                        android.util.Log.i("RssDraftInput", "phase=selectionFieldReady " +
                            "selection=${selectionField.textSelectionStart}..${selectionField.textSelectionEnd} " +
                            "actions=${selectionField.actionList.map { it.id }}")
                        assertTrue("Draft selection input deadline", SystemClock.uptimeMillis() < inputDeadline)
                        assertTrue(selectionField.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 8)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 13)
                        }))
                    } finally { selectionField.recycle() }
                } finally {
                    scenario.onActivity { androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(it.window.decorView, null) }
                }
                draftDiagnostic("selectionActionAccepted")
                try {
                    compose.waitUntil {
                        var matches = false
                        scenario.onActivity {
                            val state = it.managementModel.state.value
                            matches = state.draft == draft && state.draftStart == 8 && state.draftEnd == 13
                        }
                        matches
                    }
                } catch (failure: Throwable) {
                    draftDiagnostic("nativeSelectionAckFailed")
                    throw failure
                }
                draftDiagnostic("nativeSelectionAckPassed")
                closeSoftKeyboard()
                scenario.recreate()
                compose.waitUntil {
                    var loaded = false
                    scenario.onActivity { loaded = it.managementModel.state.value.loaded }
                    loaded
                }
                driver.awaitIdentifier("rss-manager-draft")
                assertEquals(draft, driver.text("rss-manager-draft"))
                scenario.onActivity {
                    assertEquals(8, it.managementModel.state.value.draftStart)
                    assertEquals(13, it.managementModel.state.value.draftEnd)
                }
                clickLabel("Cancel")
                compose.waitUntil { textNode("1/1")?.let { it.recycle(); true } ?: false }
                scenario.onActivity {
                    assertNull(it.managementModel.state.value.dialog)
                    assertNull(it.managementModel.state.value.pending)
                    assertFalse(it.managementModel.state.value.waitingNative)
                }
                assertEquals(GSON.toJson(source), GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)))
            }
        } finally { appDb.rssSourceDao.delete(source) }
    }

    @Test
    fun deleteConfirmationCancelAndFreshEnableLeaveFullMetadataUntouched() {
        val group = "Cancel-${UUID.randomUUID()}"
        val source = RssSource(
            sourceUrl = "https://$group.invalid", sourceName = "Managed", sourceGroup = group,
            enabled = false, header = "headers", loginUrl = "https://login.invalid",
            ruleContent = "body@text", variableComment = "vars",
        )
        appDb.rssSourceDao.insert(source)
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                filtered(scenario, group, 1)
                val id = rssSourceManagementId(source.sourceUrl)
                clickLabel("Source actions")
                driver.click("rss-manager-action:delete")
                scenario.recreate()
                compose.waitUntil {
                    var loaded = false
                    scenario.onActivity { loaded = it.managementModel.state.value.loaded }
                    loaded
                }
                scenario.onActivity { assertEquals(RssSourceManagementDialog.Delete, it.managementModel.state.value.dialog) }
                clickLabel("Cancel")
                compose.waitUntil {
                    var cancelled = false
                    scenario.onActivity { cancelled = it.managementModel.state.value.dialog == null }
                    cancelled
                }
                driver.click("rss-manager-enabled-$id")
                compose.waitUntil { appDb.rssSourceDao.getByKey(source.sourceUrl)?.enabled == true }
                assertEquals(GSON.toJson(source.copy(enabled = true)), GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)))
            }
        } finally { appDb.rssSourceDao.delete(source) }
    }

    @Test
    fun slideSelectionCancelRestoresBaselineWithoutCheckboxToggleAndReleaseCommitsRange() {
        val group = "Slide-${UUID.randomUUID()}"
        val sources = (0..2).map { index ->
            RssSource(sourceUrl = "https://$group.invalid/$index", sourceName = "Source $index", sourceGroup = group, customOrder = index)
        }
        appDb.rssSourceDao.insert(*sources.toTypedArray())
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                filtered(scenario, group, 3)
                val ids = sources.map { rssSourceManagementId(it.sourceUrl) }
                fun gesture(whileHeld: (Pointer) -> Unit) {
                    val first = driver.bounds("rss-manager-select-${ids[0]}")
                    val last = driver.bounds("rss-manager-select-${ids[2]}")
                    Pointer(first.exactCenterX(), first.exactCenterY()).use { pointer ->
                        pointer.moveTo(last.exactCenterX(), last.exactCenterY())
                        whileHeld(pointer)
                    }
                }
                gesture { pointer ->
                    compose.waitUntil {
                        var selected = false
                        scenario.onActivity { selected = it.managementModel.state.value.selected.size == 3 }
                        selected
                    }
                    pointer.cancel()
                }
                compose.waitUntil {
                    var empty = false
                    scenario.onActivity { empty = it.managementModel.state.value.selected.isEmpty() }
                    empty
                }
                gesture { pointer ->
                    compose.waitUntil {
                        var selected = false
                        scenario.onActivity { selected = it.managementModel.state.value.selected == ids.toSet() }
                        selected
                    }
                    pointer.release()
                }
                compose.waitUntil { textNode("3/3")?.let { it.recycle(); true } ?: false }
                scenario.recreate()
                compose.waitUntil {
                    var loaded = false
                    scenario.onActivity { loaded = it.managementModel.state.value.loaded }
                    loaded
                }
                compose.waitUntil { textNode("3/3")?.let { it.recycle(); true } ?: false }
            }
        } finally { appDb.rssSourceDao.delete(*sources.toTypedArray()) }
    }

    @Test
    fun unconfirmedGroupDialogBlocksTopBarNativeActionsAndOnlyScrimMayCancel() {
        val group = "Modal-${UUID.randomUUID()}"
        val source = RssSource(sourceUrl = "https://$group.invalid", sourceName = "Managed", sourceGroup = group, sourceComment = "Keep", ruleContent = "body@text")
        appDb.rssSourceDao.insert(source)
        try {
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                filtered(scenario, group, 1)
                val more = driver.bounds("rss-manager-menu")
                val filters = driver.bounds("rss-manager-filters")
                val id = rssSourceManagementId(source.sourceUrl)
                var round = 0
                fun nativeScalar(): Triple<Boolean, String, Boolean> {
                    var selected = false
                    var scalar = ""
                    var interactive = false
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        selected = id in state.selected
                        interactive = state.loaded && !state.busy && state.pending == null &&
                            !state.waitingNative && state.dialog == null && state.dragging == null
                        scalar = "round=$round selectedContains=$selected selectedCount=${state.selected.size} " +
                            "dialog=${state.dialog?.name} busy=${state.busy} loaded=${state.loaded} " +
                            "pending=${state.pending != null} effect=${state.pending?.action?.name} waitingNative=${state.waitingNative} " +
                            "dragging=${state.dragging != null} queryLength=${state.query.length} " +
                            "visibleCount=${state.rows.size} visibleSelectedCount=${state.visibleSelection.size}"
                    }
                    return Triple(selected, scalar, interactive)
                }
                @Suppress("DEPRECATION")
                fun checkboxNode(): AccessibilityNodeInfo? {
                    val queue = ArrayDeque<AccessibilityNodeInfo>()
                    driver.node("rss-manager-select-$id")?.let(queue::add) ?: return null
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        if (node.isVisibleToUser && node.isCheckable) {
                            queue.forEach { it.recycle() }
                            return node
                        }
                        for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                        node.recycle()
                    }
                    return null
                }
                fun nodeScalar(node: AccessibilityNodeInfo?): String {
                    if (node == null) return "checkboxPresent=false"
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    return "checkboxPresent=true checked=${node.isChecked} checkable=${node.isCheckable} " +
                        "enabled=${node.isEnabled} clickable=${node.isClickable} visible=${node.isVisibleToUser} " +
                        "bounds=$bounds actions=${node.actionList.map { it.id }}"
                }
                fun diagnostic(phase: String, node: AccessibilityNodeInfo? = null): String =
                    "phase=$phase ${nativeScalar().second} ${nodeScalar(node)}"
                fun clickCheckbox(expected: Boolean, phase: String) {
                    // This replaces driver.click's single 30s budget: pre-render barrier,
                    // one actual ACTION_CLICK, and rendered ACK all share this deadline.
                    val deadline = SystemClock.uptimeMillis() + 30_000
                    var dispatched = false
                    var observed = false
                    var last = diagnostic(phase)
                    while (SystemClock.uptimeMillis() < deadline) {
                        val node = checkboxNode()
                        try {
                            val native = nativeScalar()
                            last = "phase=$phase ${native.second} ${nodeScalar(node)} dispatched=$dispatched"
                            if (!observed) {
                                android.util.Log.i("RssModalInput", "initial $last")
                                observed = true
                            }
                            if (!dispatched && node != null && native.third && native.first == expected &&
                                node.isChecked == expected && node.isEnabled && node.isClickable &&
                                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                                android.util.Log.i("RssModalInput", "before $last")
                                // Never retry the input if acceptance/ACK is missing.
                                dispatched = true
                                assertTrue("Checkbox rejected its single action; $last",
                                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            } else if (dispatched && node != null && node.isChecked == !expected) {
                                android.util.Log.i("RssModalInput", "renderedAck $last")
                                return
                            }
                        } finally { node?.recycle() }
                        SystemClock.sleep(50)
                    }
                    fail("Checkbox input deadline; $last")
                }
                fun nativeAck(phase: String, condition: () -> Boolean) {
                    try {
                        // Keep the original default 1000ms native business assertion.
                        compose.waitUntil(condition = condition)
                    } catch (failure: Throwable) {
                        val node = checkboxNode()
                        try { android.util.Log.e("RssModalInput", diagnostic(phase, node)) }
                        finally { node?.recycle() }
                        throw failure
                    }
                }
                fun openGroupDraft() {
                    var alreadySelected = false
                    scenario.onActivity { alreadySelected = id in it.managementModel.state.value.selected }
                    // Cancelling the modal preserves the selection used to open it.
                    if (!alreadySelected) clickCheckbox(false, "select")
                    nativeAck("selectedNativeAck") {
                        var selected = false
                        scenario.onActivity { selected = id in it.managementModel.state.value.selected }
                        selected
                    }
                    driver.click("rss-manager-bulk")
                    driver.click("rss-manager-action:AddGroup")
                    driver.setText("rss-manager-draft", "unconfirmed group")
                    compose.waitUntil {
                        var matches = false
                        scenario.onActivity { matches = it.managementModel.state.value.draft == "unconfirmed group" }
                        matches
                    }
                    closeSoftKeyboard()
                    compose.waitUntil {
                        val menu = driver.node("rss-manager-menu")
                        val blocked = menu == null
                        menu?.recycle()
                        blocked
                    }
                }
                for (topBar in listOf(more, filters)) {
                    round++
                    openGroupDraft()
                    val title = requireNotNull(textNode("Add group"))
                    val titleBounds = android.graphics.Rect()
                    try { title.getBoundsInScreen(titleBounds) } finally { title.recycle() }
                    Pointer(titleBounds.exactCenterX(), titleBounds.exactCenterY()).use { it.release() }
                    frame(scenario)
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        assertEquals(RssSourceManagementDialog.AddGroup, state.dialog)
                        assertEquals("unconfirmed group", state.draft)
                        assertNull(state.pending)
                        assertFalse(state.waitingNative)
                    }
                    Pointer(topBar.exactCenterX(), topBar.exactCenterY()).use { it.release() }
                    frame(scenario)
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        assertFalse(it.isFinishing)
                        assertNull(state.pending)
                        assertFalse(state.waitingNative)
                        assertTrue(state.dialog == null || state.dialog == RssSourceManagementDialog.AddGroup)
                        assertTrue(id in state.selected)
                        assertEquals(if (state.dialog == null) "" else "unconfirmed group", state.draft)
                    }
                    for (label in listOf("Add source", "Enabled")) {
                        val backgroundMenu = textNode(label)
                        val blocked = backgroundMenu == null
                        backgroundMenu?.recycle()
                        assertTrue("Modal tap must not open a background menu", blocked)
                    }
                    var remains = false
                    scenario.onActivity { remains = it.managementModel.state.value.dialog != null }
                    if (remains) clickLabel("Cancel")
                    compose.waitUntil {
                        var cancelled = false
                        scenario.onActivity { cancelled = it.managementModel.state.value.dialog == null }
                        cancelled
                    }
                    // Selection was never changed by cancel. Clear it through the actual checkbox
                    // so the next independent modal attempt starts with the same baseline.
                    clickCheckbox(true, "clear")
                    nativeAck("clearedNativeAck") {
                        var empty = false
                        scenario.onActivity { empty = it.managementModel.state.value.selected.isEmpty() }
                        empty
                    }
                }
                assertEquals(GSON.toJson(source), GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)))
            }
        } finally { appDb.rssSourceDao.delete(source) }
    }
}
