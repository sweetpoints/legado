package io.legado.app.ui.highlight

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Normal entry points; real Flutter actions and Room/durable-session receipts. */
class FlutterHighlightManagementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dao = appDb.highlightRuleDao
    private fun <T> database(action: () -> T) = runBlocking(Dispatchers.IO) { action() }
    private fun fixture(action: (HighlightRule, HighlightRule) -> Unit) {
        val backup = database { dao.all }
        val first = HighlightRule(name = "Flutter first", pattern = "first", group = "Flutter A", order = 0).apply { isEnabled = false }
        val hidden = HighlightRule(name = "Hidden", pattern = "hidden", group = "Flutter B", order = 1)
        val last = HighlightRule(name = "Flutter last", pattern = "last", group = "Flutter A", order = 2)
        try { database { dao.deleteAll(); dao.insert(first, hidden, last) }; action(database { dao.all.single { it.uuid == first.uuid } }, database { dao.all.single { it.uuid == hidden.uuid } }) }
        finally { database { dao.deleteAll(); if (backup.isNotEmpty()) dao.insert(*backup.toTypedArray()) } }
    }
    @Test fun normalManagerFilterSelectionRecreateDeleteCancelAndSwitchPreserveFullMetadata() = fixture { first, hidden ->
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
            ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                ui.await15("normal Flutter manager and Room projection") {
                    var ready = false; scenario.onActivity { host -> ready = host.supportFragmentManager.fragments.any { it is FlutterFragment } && host.viewModel.state.value.canAct && host.viewModel.state.value.rules.size == 3 }; ready
                }
                ui.click("highlight-management-menu"); ui.click("highlight-management-filter"); ui.click("highlight-filter-Flutter A")
                ui.await15("actual filtered rows") { !ui.exists("highlight-management-row-${hidden.uuid}") && ui.exists("highlight-management-row-${first.uuid}") }
                ui.click("highlight-management-select-${first.uuid}"); ui.await15("actual selection ACK") { ui.checked("highlight-management-select-${first.uuid}", true) }
                scenario.recreate()
                ui.await15("private filter and actual selection restored") {
                    var native = false; scenario.onActivity { native = it.viewModel.state.value.canAct && it.viewModel.state.value.draft.filter == "Flutter A" && it.viewModel.state.value.draft.selection == setOf(first.uuid) }
                    native && ui.checked("highlight-management-select-${first.uuid}", true) && !ui.exists("highlight-management-row-${hidden.uuid}")
                }
                ui.click("highlight-management-delete-selection"); ui.await15("actual deletion confirmation") { ui.exists("highlight-management-delete-confirm") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.await15("Back cancels only deletion") { !ui.exists("highlight-management-delete-confirm") }
                assertEquals(3, database { dao.all.size })
                ui.click("highlight-management-enabled-${first.uuid}")
                ui.await15("actual enabled switch and committed Room") { ui.checked("highlight-management-enabled-${first.uuid}", true) && database { dao.all.single { it.uuid == first.uuid }.isEnabled } }
                assertEquals(GSON.toJson(first.copy(isEnabled = true)), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
                val last = database { dao.all.single { it.name == "Flutter last" } }
                val sourceId = "highlight-management-name-${first.uuid}"
                val targetId = "highlight-management-name-${last.uuid}"
                val fixtureIds = listOf(first.uuid, hidden.uuid, last.uuid)
                // Recognition and the original held-order assertion share one 15s budget.
                // A fixed Android hold is not a receipt for Flutter's delayed recognizer.
                val deadline = android.os.SystemClock.uptimeMillis() + 15_000
                var nativeReceipt = "not sampled"
                fun nativeBegin(): Boolean {
                    var begun = false
                    scenario.onActivity { host ->
                        val state = host.viewModel.state.value
                        begun = host.viewModel.reorderInProgress
                        nativeReceipt = "begun=$begun,canAct=${state.canAct},busy=${state.busy},loaded=${state.loaded}," +
                            "rowsReady=${state.rowsReady},errorPresent=${state.error != null}," +
                            "visibleFixtureOrder=${state.visible.map { fixtureIds.indexOf(it.uuid) }}"
                    }
                    return begun
                }
                fun bounds(id: String): android.graphics.Rect? = driver.node(id)?.let { node ->
                    try { android.graphics.Rect().also(node::getBoundsInScreen).takeIf { !it.isEmpty } }
                    finally { node.recycle() }
                }
                fun actionable(id: String): Boolean {
                    val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    driver.node(id)?.let(pending::add) ?: return false
                    var available = false
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        if (node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }) available = true
                        for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                        node.recycle()
                    }
                    return available
                }
                fun awaitHeld(description: String, condition: () -> Boolean) {
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        if (condition()) return
                        android.os.SystemClock.sleep(minOf(50L, (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    }
                    val matched = condition()
                    if (!matched) {
                        nativeBegin()
                        fail("$description within original shared 15s; native=[$nativeReceipt],source=[${ui.diagnostic(sourceId)}],target=[${ui.diagnostic(targetId)}]")
                    }
                }
                awaitHeld("native and actual controls ready before the single physical pointer") {
                    var ready = false
                    scenario.onActivity { ready = it.viewModel.state.value.canAct && !it.viewModel.reorderInProgress }
                    ready && actionable(sourceId) && actionable(targetId) && bounds(sourceId) != null && bounds(targetId) != null
                }
                val source = checkNotNull(bounds(sourceId))
                driver.touchDown(source.exactCenterX(), source.exactCenterY()).use { touch ->
                    touch.hold(650)
                    awaitHeld("real native drag-begin acknowledgement before pointer movement", ::nativeBegin)
                    val target = checkNotNull(bounds(targetId))
                    touch.moveTo(target.exactCenterX(), target.exactCenterY())
                    awaitHeld("held physical drag updates native visible order before release") {
                        var moved = false; scenario.onActivity { moved = it.viewModel.state.value.visible.map { row -> row.uuid } == listOf(last.uuid, first.uuid) }; moved
                    }
                }
                ui.await15("filtered drag Room receipt preserves hidden slot") { database { dao.all.map { it.uuid } == listOf(last.uuid, hidden.uuid, first.uuid) } }
                assertEquals(GSON.toJson(first.copy(isEnabled = true, order = 2)), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(last.copy(order = 0)), GSON.toJson(database { dao.all.single { it.uuid == last.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
            }
        }
    }
    @Test fun normalGroupRawRenameRecreateCancelThenConfirmPreservesOtherMetadata() = fixture { first, hidden ->
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
            ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                ui.await15("normal manager ready") { ui.exists("highlight-management-menu") }
                ui.click("highlight-management-menu"); ui.click("highlight-management-groups")
                ui.await15("normal group Flutter label") { ui.exists("highlight-group-label-Flutter A") }
                fun inputGroupName(value: String) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 30_000
                    var receipt = "not sampled"
                    lateinit var owner: HighlightGroupManageDialog
                    scenario.onActivity { owner = it.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().single() }
                    fun awaitInput(description: String, condition: () -> Boolean) {
                        while (android.os.SystemClock.uptimeMillis() < deadline) {
                            if (condition()) return
                            android.os.SystemClock.sleep(25)
                        }
                        assertTrue("$description; $receipt", condition())
                    }
                    fun field(focused: Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                        val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                        driver.node("highlight-group-name")?.let(nodes::add) ?: return null
                        var windowId = -1
                        scenario.onActivity {
                            owner.dialog?.window?.decorView?.createAccessibilityNodeInfo()?.let { node ->
                                try { windowId = node.windowId } finally { node.recycle() }
                            }
                        }
                        while (nodes.isNotEmpty()) {
                            val node = nodes.removeFirst()
                            receipt = "sameWindow=${node.windowId == windowId},visible=${node.isVisibleToUser}," +
                                "enabled=${node.isEnabled},editable=${node.isEditable},focused=${node.isFocused}," +
                                "setText=${node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT }}"
                            if (node.windowId == windowId && node.isVisibleToUser && node.isEnabled && node.isEditable &&
                                (!focused || node.isFocused) && node.actionList.any {
                                    it.id == if (focused) android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
                                    else android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                                }) {
                                nodes.forEach { it.recycle() }; return node
                            }
                            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                            node.recycle()
                        }
                        return null
                    }
                    // The identifier may belong to the Semantics wrapper rather than its
                    // editable descendant. Preserve the page's autofocus; click at most once.
                    var target: android.view.accessibility.AccessibilityNodeInfo? = null
                    awaitInput("Owned actual group field") {
                        target = field(true) ?: field(false); target != null
                    }
                    target!!.let { node -> try {
                        if (!node.isFocused) assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    } finally { node.recycle() } }
                    fun viewport(): List<Int>? {
                        var result: List<Int>? = null
                        scenario.onActivity { host ->
                            if (host.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().singleOrNull() !== owner) return@onActivity
                            val window = owner.dialog?.window ?: return@onActivity
                            val decor = window.decorView
                            val focus = window.currentFocus ?: return@onActivity
                            val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor) ?: return@onActivity
                            if (owner.isResumed && decor.isAttachedToWindow && decor.isLaidOut && !decor.isLayoutRequested &&
                                decor.hasWindowFocus() && manager.isActive(focus) && manager.isAcceptingText) {
                                result = listOf(System.identityHashCode(decor), System.identityHashCode(focus), decor.width, decor.height,
                                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom,
                                    if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) 1 else 0)
                            }
                        }
                        return result
                    }
                    awaitInput("Owned input connection and viewport across natural animation turns") {
                        val before = viewport() ?: return@awaitInput false
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var decor: android.view.View? = null
                        val second = Runnable { latch.countDown() }
                        val first = Runnable { decor?.postOnAnimation(second) }
                        scenario.onActivity { decor = owner.dialog?.window?.decorView; decor?.postOnAnimation(first) }
                        val observed = try {
                            val remaining = deadline - android.os.SystemClock.uptimeMillis()
                            remaining > 0 && latch.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } finally { scenario.onActivity { decor?.removeCallbacks(first); decor?.removeCallbacks(second) } }
                        observed && viewport() == before && field(true)?.let { it.recycle(); true } == true
                    }
                    val fresh = field(true)
                    assertNotNull("Fresh owned editable descendant before single SET_TEXT; $receipt", fresh)
                    try { assertTrue(fresh!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    })) } finally { fresh?.recycle() }
                    awaitInput("Native and actual raw group field ACK") {
                        var native = false
                        scenario.onActivity { native = owner.model.state.value.name == value }
                        val actual = field(true)?.let { node -> try { node.text?.toString() == value } finally { node.recycle() } } ?: false
                        native && actual
                    }
                }
                ui.click("highlight-group-edit-Flutter A"); inputGroupName("  Private😀\nDraft  ")
                ui.await15("raw group native ACK") {
                    var raw = false; scenario.onActivity { host -> raw = host.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().single().model.state.value.name == "  Private😀\nDraft  " }; raw
                }
                scenario.recreate()
                var restoredReceipt = "not sampled"
                ui.await15("actual recreated private raw draft", { restoredReceipt }) {
                    var native = false
                    var windowId = -1
                    scenario.onActivity { host ->
                        val dialog = host.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().singleOrNull()
                        val state = dialog?.model?.state?.value
                        native = state?.name == "  Private😀\nDraft  "
                        restoredReceipt = "nativeFound=${state != null},nativeEquals=$native,nativeLength=${state?.name?.length}," +
                            "loading=${state?.loading},busy=${state?.busy},rename=${state?.stage == HighlightGroupStage.Rename}"
                        dialog?.dialog?.window?.decorView?.createAccessibilityNodeInfo()?.let { node ->
                            try { windowId = node.windowId } finally { node.recycle() }
                        }
                    }
                    // InputDecoration's label is not the restored editable value. Read
                    // only the owned TextField descendant, without clicking or refocusing.
                    val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    driver.node("highlight-group-name")?.let(nodes::add)
                    var actual = false
                    var visited = 0
                    while (nodes.isNotEmpty()) {
                        val node = nodes.removeFirst()
                        if (visited++ < 8) restoredReceipt += ";nodeWindowMatches=${node.windowId == windowId}," +
                            "visible=${node.isVisibleToUser},enabled=${node.isEnabled},editable=${node.isEditable}," +
                            "focused=${node.isFocused},textLength=${node.text?.length}," +
                            "rawEquals=${node.text?.toString() == "  Private😀\nDraft  "}"
                        if (node.windowId == windowId && node.isVisibleToUser && node.isEnabled && node.isEditable &&
                            node.text?.toString() == "  Private😀\nDraft  ") actual = true
                        for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                        node.recycle()
                    }
                    native && actual
                }
                ui.click("highlight-group-cancel"); ui.await15("rename canceled") { !ui.exists("highlight-group-name") }
                assertEquals(GSON.toJson(first), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                ui.click("highlight-group-edit-Flutter A"); inputGroupName("  Renamed  "); ui.click("highlight-group-rename-confirm")
                ui.await15("rename commits before actual group receipt") { database { dao.all.single { it.uuid == first.uuid }.group == "Renamed" } && ui.exists("highlight-group-label-Renamed") }
                assertEquals(GSON.toJson(first.copy(group = "Renamed")), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
            }
        }
    }
    @Test fun normalManagerActualListScrollRestoresFromPrivateSessionAfterActivityRecreate() {
        val backup = database { dao.all }
        val rows = (0 until 60).map { index -> HighlightRule(name = "Scroll $index", pattern = "pattern $index", order = index) }
        try {
            database { dao.deleteAll(); dao.insert(*rows.toTypedArray()) }
            FlutterUiTestDriver(instrumentation).use { driver ->
                driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
                ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                    val id = "highlight-management-name-${rows[40].uuid}"
                    ui.reveal(id)
                    val bounds = driver.bounds(id)
                    var savedIndex = 0; var savedOffset = 0.0
                    ui.await15("actual scroll acknowledged in private native draft") {
                        scenario.onActivity { savedIndex = it.viewModel.state.value.draft.scrollIndex; savedOffset = it.viewModel.state.value.draft.scrollOffset }
                        savedIndex > 0
                    }
                    scenario.recreate()
                    ui.await15("actual row restores without scrolling the recreated list") { ui.exists(id) && kotlin.math.abs(driver.bounds(id).top - bounds.top) <= 2 }
                    scenario.onActivity { assertEquals(savedIndex, it.viewModel.state.value.draft.scrollIndex); assertEquals(savedOffset, it.viewModel.state.value.draft.scrollOffset, .01) }
                    assertEquals(rows.map { it.uuid }, database { dao.all.map { it.uuid } })
                }
            }
        } finally { database { dao.deleteAll(); if (backup.isNotEmpty()) dao.insert(*backup.toTypedArray()) } }
    }

}
