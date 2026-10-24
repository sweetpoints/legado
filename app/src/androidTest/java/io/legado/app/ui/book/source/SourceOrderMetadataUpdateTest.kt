package io.legado.app.ui.book.source

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.rssSourceManagementId
import io.legado.app.ui.replace.ReplaceManagerUiProbe
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.GSON
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A displayed row can predate a committed move or an edit from another screen. */
@RunWith(AndroidJUnit4::class)
class SourceOrderMetadataUpdateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun staleRssSwitchAndSelectionPreserveCommittedOrderAndMetadata() {
        val group = "Enable ${UUID.randomUUID()}"
        val before = appDb.rssSourceDao.all
        val stale =
            (0..2).map { index ->
                RssSource(
                    sourceUrl = "https://enable.invalid/$group/$index",
                    sourceName = "Source $index",
                    sourceGroup = group,
                    enabled = false,
                    customOrder = index * 100,
                    sourceComment = "Before $index",
                )
            }
        val keys = stale.map { it.sourceUrl }.toSet()
        val flutter = FlutterUiTestDriver(instrumentation)
        try {
            flutter.enableAccessibility()
            appDb.rssSourceDao.insert(*stale.toTypedArray())
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                val expectedOrder =
                    appDb.rssSourceDao.all
                        .map { it.sourceUrl }
                        .toMutableList()
                        .apply {
                            remove(stale[0].sourceUrl)
                            add(indexOf(stale[2].sourceUrl) + 1, stale[0].sourceUrl)
                        }
                waitUntil("RSS model loaded") {
                    var loaded = false
                    scenario.onActivity { loaded = it.managementModel.state.value.loaded }
                    loaded
                }
                val ids = stale.map { rssSourceManagementId(it.sourceUrl) }
                scenario.onActivity { it.managementModel.query("group:$group") }
                waitUntil("RSS filter ready") {
                    var ready = false
                    scenario.onActivity {
                        ready = it.managementModel.state.value.rows.map { row -> row.id } == ids
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    activity.managementModel.beginDrag(ids[0])
                    activity.managementModel.dragTo(ids[2], true)
                    activity.managementModel.finishDrag()
                }
                waitUntil("RSS move committed") {
                    appDb.rssSourceDao.all.map { it.sourceUrl } == expectedOrder
                }
                appDb.rssSourceDao.update(
                    *appDb.rssSourceDao
                        .getRssSources(*keys.toTypedArray())
                        .map {
                            it.copy(sourceComment = "Updated comment", ruleContent = "body@text")
                        }
                        .toTypedArray()
                )
                val committed = appDb.rssSourceDao.all
                fun assertState(enabledKeys: Set<String>) {
                    val expected = committed.associate { source ->
                        source.sourceUrl to
                            GSON.toJson(
                                if (source.sourceUrl in keys)
                                    source.copy(enabled = source.sourceUrl in enabledKeys)
                                else source
                            )
                    }
                    assertEquals(
                        expected,
                        appDb.rssSourceDao.all.associate {
                            it.sourceUrl to GSON.toJson(it)
                        },
                    )
                }
                fun readyForMutation(): Boolean {
                    var ready = false
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        ready = state.loaded && !state.busy && state.pending == null
                    }
                    return ready
                }
                fun switchDiagnostic(phase: String) {
                    val room = appDb.rssSourceDao.getByKey(stale[0].sourceUrl)
                    var native = ""
                    scenario.onActivity { activity ->
                        val state = activity.managementModel.state.value
                        val row = state.rows.firstOrNull { it.id == ids[0] }
                        native = "loaded=${state.loaded} busy=${state.busy} dialog=${state.dialog?.name} " +
                            "pending=${state.pending != null} effect=${state.pending?.action?.name} " +
                            "waitingNative=${state.waitingNative} error=${state.error != null} " +
                            "dragging=${state.dragging != null} rowCount=${state.rows.size} " +
                            "rowIndex=${state.rows.indexOfFirst { it.id == ids[0] }} " +
                            "rowPresent=${row != null} rowEnabled=${row?.enabled} " +
                            "queryMatches=${state.query == "group:$group"} queryLength=${state.query.length} " +
                            "windowFocus=${activity.window.decorView.hasWindowFocus()} " +
                            "lifecycle=${activity.lifecycle.currentState}"
                    }
                    val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    flutter.node("rss-manager-enabled-${ids[0]}")?.let(queue::add)
                    val rendered = mutableListOf<String>()
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        try {
                            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                            rendered.add("checked=${node.isChecked} checkable=${node.isCheckable} " +
                                "enabled=${node.isEnabled} clickable=${node.isClickable} " +
                                "visible=${node.isVisibleToUser} bounds=$bounds actions=${node.actionList.map { it.id }}")
                            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                        } finally { node.recycle() }
                    }
                    // Scalars only: never print row names, IDs, URLs, comments, or query contents.
                    android.util.Log.i("RssOrderSwitch", "phase=$phase roomPresent=${room != null} " +
                        "roomEnabled=${room?.enabled} roomOrder=${room?.customOrder} $native nodes=$rendered")
                }
                fun clickSettledSwitch() {
                    // Replace the driver's one 30s click budget; do not add another deadline.
                    val deadline = android.os.SystemClock.uptimeMillis() + 30_000
                    val hostBounds = android.graphics.Rect()
                    fun nativeReady(): Boolean {
                        var ready = false
                        scenario.onActivity { activity ->
                            val state = activity.managementModel.state.value
                            val host = activity.window.decorView
                            val visibleHost = host.getGlobalVisibleRect(hostBounds)
                            ready = visibleHost && host.isAttachedToWindow && host.isHardwareAccelerated &&
                                host.hasWindowFocus() && state.loaded && !state.busy && state.pending == null &&
                                !state.waitingNative && state.dialog == null && state.dragging == null &&
                                state.query == "group:$group" &&
                                state.rows.map { it.id } == listOf(ids[1], ids[2], ids[0]) &&
                                state.rows.last().enabled == false
                        }
                        return ready
                    }
                    fun target(): android.view.accessibility.AccessibilityNodeInfo? {
                        val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                        flutter.node("rss-manager-enabled-${ids[0]}")?.let(queue::add) ?: return null
                        while (queue.isNotEmpty()) {
                            val node = queue.removeFirst()
                            if (node.isCheckable) {
                                queue.forEach { it.recycle() }
                                return node
                            }
                            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                            node.recycle()
                        }
                        return null
                    }
                    fun bounds(node: android.view.accessibility.AccessibilityNodeInfo?): android.graphics.Rect? {
                        if (node == null || !node.isVisibleToUser || !node.isEnabled ||
                            !node.isClickable || node.isChecked ||
                            node.actionList.none { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }) return null
                        return android.graphics.Rect().also(node::getBoundsInScreen)
                            .takeIf { !it.isEmpty && hostBounds.contains(it) }
                    }
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        if (!nativeReady()) {
                            val left = deadline - android.os.SystemClock.uptimeMillis()
                            if (left > 0) android.os.SystemClock.sleep(minOf(50L, left))
                            continue
                        }
                        val first = target()
                        val beforeFrame = try { bounds(first) } finally { first?.recycle() }
                        if (beforeFrame == null) {
                            val left = deadline - android.os.SystemClock.uptimeMillis()
                            if (left > 0) android.os.SystemClock.sleep(minOf(50L, left))
                            continue
                        }
                        val frame = java.util.concurrent.CountDownLatch(1)
                        val committed = Runnable { frame.countDown() }
                        var observer: android.view.ViewTreeObserver? = null
                        scenario.onActivity { activity ->
                            val host = activity.window.decorView
                            observer = host.viewTreeObserver
                            observer!!.registerFrameCommitCallback(committed)
                            host.postInvalidateOnAnimation()
                        }
                        // This is a host frame, not proof of a Dart frame; fresh semantics below
                        // independently confirms Flutter's actual value and target geometry.
                        val remaining = deadline - android.os.SystemClock.uptimeMillis()
                        val drawn = try {
                            remaining > 0 && frame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } finally {
                            scenario.onActivity {
                                observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(committed)
                            }
                        }
                        if (!drawn) break
                        if (!nativeReady()) continue
                        val fresh = target()
                        try {
                            if (bounds(fresh) != beforeFrame) continue
                            check(android.os.SystemClock.uptimeMillis() < deadline) { "RSS switch click deadline" }
                            check(requireNotNull(fresh).performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                            )) { "RSS switch rejected its single click" }
                            return // Never dispatch again after this one actual ACTION_CLICK.
                        } finally { fresh?.recycle() }
                    }
                    switchDiagnostic("switchPreconditionDeadline")
                    error("RSS switch input precondition deadline")
                }
                // Immutable IDs captured before the concurrent edit target only the enabled column.
                waitUntil("RSS switch row settled") { readyForMutation() }
                switchDiagnostic("beforeOriginalClick")
                try { clickSettledSwitch() } catch (failure: Throwable) {
                    switchDiagnostic("switchInputFailed")
                    throw failure
                }
                switchDiagnostic("originalClickAccepted")
                try {
                    waitUntil("RSS switch enabled") {
                        appDb.rssSourceDao.getByKey(stale[0].sourceUrl)?.enabled == true &&
                            readyForMutation()
                    }
                } catch (failure: Throwable) {
                    switchDiagnostic("originalNativeAckFailed")
                    throw failure
                }
                switchDiagnostic("originalNativeAckPassed")
                assertState(setOf(stale[0].sourceUrl))
                // Room commits before the model clears busy; synchronize each actual command ACK.
                scenario.onActivity { it.managementModel.enabled(ids, true) }
                waitUntil("RSS selection enabled") {
                    keys.all { appDb.rssSourceDao.getByKey(it)?.enabled == true } && readyForMutation()
                }
                assertState(keys)
                scenario.onActivity { it.managementModel.enabled(ids, false) }
                waitUntil("RSS selection disabled") {
                    keys.all { appDb.rssSourceDao.getByKey(it)?.enabled == false } && readyForMutation()
                }
                assertState(emptySet())
            }
        } finally {
            flutter.close()
            appDb.rssSourceDao.delete(*stale.toTypedArray())
            val orders = before.associate { it.sourceUrl to it.customOrder }
            appDb.rssSourceDao.update(
                *appDb.rssSourceDao.all
                    .map {
                        it.copy(customOrder = orders[it.sourceUrl] ?: it.customOrder)
                    }
                    .toTypedArray()
            )
        }
    }

    @Test
    fun staleReplaceSwitchAndSelectionPreserveCommittedOrderAndMetadata() {
        val group = "Enable ${UUID.randomUUID()}"
        val before = appDb.replaceRuleDao.all
        val firstId = System.currentTimeMillis() * 1000
        val stale =
            (0..2).map { index ->
                ReplaceRule(
                    id = firstId + index,
                    name = "Rule $index",
                    group = group,
                    pattern = "Before $index",
                    replacement = "Before",
                    order = index * 100,
                    isEnabled = false,
                )
            }
        val keys = stale.map { it.id }.toSet()
        val ui = ReplaceManagerUiProbe(instrumentation)
        try {
            appDb.replaceRuleDao.insert(*stale.toTypedArray())
            ActivityScenario.launch(ReplaceRuleActivity::class.java).use { scenario ->
                val expectedOrder =
                    appDb.replaceRuleDao.all
                        .map { it.id }
                        .toMutableList()
                        .apply {
                            remove(stale[0].id)
                            add(indexOf(stale[2].id) + 1, stale[0].id)
                        }
                waitUntil("replacement model loaded") {
                    var loaded = false
                    scenario.onActivity { loaded = it.managementModel.state.value.loaded }
                    loaded
                }
                ui.click("replace-rule-search")
                waitUntil("actual replacement query input") {
                    val field = ui.driver.node("replace-rule-search") ?: return@waitUntil false
                    try {
                        field.isFocused && field.isEditable && field.isEnabled &&
                            field.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } &&
                            field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "group:$group")
                            })
                    } finally { field.recycle() }
                }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                waitUntil("replacement filter ready") {
                    var ready = false
                    scenario.onActivity {
                        ready =
                            it.managementModel.state.value.rows.map { row -> row.id } ==
                                stale.map { row -> row.id } &&
                                it.managementModel.state.value.query == "group:$group"
                    }
                    ready && ui.driver.text("replace-rule-search") == "group:$group"
                }
                val rowIds = stale.map { "replace-rule-row-${it.id}" }
                var previousGeometry: List<android.graphics.Rect>? = null
                waitUntil("actual replacement drag rows and hidden IME settled") {
                    var imeHidden = false
                    scenario.onActivity { activity ->
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        imeHidden = insets != null && insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom == 0
                    }
                    val actual = rowIds.map { id -> ui.driver.node(id)?.let { node -> android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() } } }
                    if (actual.any { it == null }) return@waitUntil false
                    val geometry = actual.map { checkNotNull(it) }
                    val stable = geometry == previousGeometry
                    previousGeometry = geometry
                    imeHidden && stable && ui.actual("replace-rule-enabled-${stale[0].id}") { node ->
                        node.isCheckable && node.isEnabled && node.isClickable &&
                            node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }
                    }
                }
                fun bounds(id: String) = checkNotNull(ui.driver.node(id)).let { node ->
                    android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() }
                }
                val first = bounds(rowIds[0]); val last = bounds(rowIds[2])
                val x = first.left + minOf(first.width() / 2f, 80 * instrumentation.targetContext.resources.displayMetrics.density)
                ui.driver.touchDown(x, first.exactCenterY()).use { pointer ->
                    pointer.hold(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
                    pointer.moveTo(x, last.exactCenterY() + last.height() / 4f, 500)
                    waitUntil("actual replacement held preview") {
                        var preview = false
                        scenario.onActivity { preview = it.managementModel.state.value.rows.map { row -> row.id } == listOf(stale[1].id, stale[2].id, stale[0].id) }
                        val actual = rowIds.map { id -> ui.driver.node(id)?.let { node -> android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() } } }
                        preview && actual.all { it != null } && actual[1]!!.centerY() < actual[2]!!.centerY() && actual[2]!!.centerY() < actual[0]!!.centerY()
                    }
                }
                waitUntil("replacement move committed") {
                    appDb.replaceRuleDao.all.map { it.id } == expectedOrder
                }
                appDb.replaceRuleDao.update(
                    *appDb.replaceRuleDao
                        .findByIds(*keys.toLongArray())
                        .map {
                            it.copy(
                                pattern = "Updated pattern",
                                replacement = "Updated replacement",
                                scope = "Updated scope",
                            )
                        }
                        .toTypedArray()
                )
                val committed = appDb.replaceRuleDao.all
                fun assertState(enabledKeys: Set<Long>) {
                    val expected = committed.associate { rule ->
                        rule.id to
                            GSON.toJson(
                                if (rule.id in keys) rule.copy(isEnabled = rule.id in enabledKeys)
                                else rule
                            )
                    }
                    assertEquals(
                        expected,
                        appDb.replaceRuleDao.all.associate {
                            it.id to GSON.toJson(it)
                        },
                    )
                }
                waitUntil("replacement switch row") {
                    var ready = false
                    scenario.onActivity {
                        ready =
                            it.managementModel.state.value.rows.map { row -> row.id } ==
                                listOf(stale[1].id, stale[2].id, stale[0].id)
                    }
                    ready
                }
                fun readyForMutation(): Boolean {
                    var ready = false
                    scenario.onActivity {
                        val state = it.managementModel.state.value
                        ready = state.loaded && !state.busy && state.pending == null
                    }
                    return ready
                }
                waitUntil("actual replacement switch ready and clicked once") {
                    readyForMutation() && ui.actual("replace-rule-enabled-${stale[0].id}") { node ->
                        node.isCheckable && !node.isChecked && node.isEnabled && node.isClickable &&
                            node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK } &&
                            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                waitUntil("replacement switch enabled") {
                    appDb.replaceRuleDao.findById(stale[0].id)?.isEnabled == true &&
                        readyForMutation() && ui.checked("replace-rule-enabled-${stale[0].id}", true)
                }
                assertState(setOf(stale[0].id))
                // Room commits before the model completes its mutation. The next user action
                // must wait for the busy state to clear, or enabled() correctly ignores it.
                ui.click("replace-rule-all")
                waitUntil("actual selected replacement identities") {
                    var selected = false
                    scenario.onActivity { selected = it.managementModel.state.value.selected == keys }
                    selected && stale.all { ui.checked("replace-rule-select-${it.id}", true) }
                }
                ui.click("replace-rule-selection-menu")
                ui.click("replace-rule-action:enable")
                waitUntil("replacement selection enabled") {
                    keys.all { appDb.replaceRuleDao.findById(it)?.isEnabled == true } &&
                        readyForMutation() && stale.all { ui.checked("replace-rule-enabled-${it.id}", true) }
                }
                assertState(keys)
                ui.click("replace-rule-selection-menu")
                ui.click("replace-rule-action:disable")
                waitUntil("replacement selection disabled") {
                    keys.all { appDb.replaceRuleDao.findById(it)?.isEnabled == false } &&
                        readyForMutation() && stale.all { ui.checked("replace-rule-enabled-${it.id}", false) }
                }
                assertState(emptySet())
            }
        } finally {
            ui.close()
            appDb.replaceRuleDao.delete(*stale.toTypedArray())
            val orders = before.associate { it.id to it.order }
            appDb.replaceRuleDao.update(
                *appDb.replaceRuleDao.all
                    .map {
                        it.copy(order = orders[it.id] ?: it.order)
                    }
                    .toTypedArray()
            )
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000, condition = condition)
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            error("Timed out waiting for $description")
        }
    }
}
