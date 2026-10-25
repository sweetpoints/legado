package io.legado.app.ui.about

import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicReference
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.data.repository.AppLogRow
import org.junit.Assert.*
import org.junit.Test

/** Synthetic repositories, normal Dialog classes, real rendered Flutter actions. No machine logs. */
class FlutterDiagnosticsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch(crash: Boolean = false) = ActivityScenario.launch<DiagnosticsFixtureActivity>(
        Intent(context, DiagnosticsFixtureActivity::class.java).putExtra("crash", crash))
    private fun exists(driver: FlutterUiTestDriver, id: String) = driver.node(id)?.let { it.recycle(); true } ?: false
    private fun exactText(driver: FlutterUiTestDriver, id: String, expected: String): Boolean {
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        driver.node(id)?.let(nodes::add) ?: return false
        var equal = false
        while(nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if(node.isVisibleToUser && (node.text?.toString() == expected || node.contentDescription?.toString() == expected)) equal = true
            for(index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            node.recycle()
        }
        return equal
    }
    private fun click(driver: FlutterUiTestDriver, id: String) {
        var action: AccessibilityNodeInfo? = null
        driver.await("actual advertised diagnostics action $id") {
            val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
            driver.node(id)?.let(nodes::add) ?: return@await false
            while(nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if(action == null && node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    action = node
                } else {
                    for(index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                    node.recycle()
                }
            }
            action != null
        }
        val actual = requireNotNull(action)
        try { assertTrue("Single actual click rejected: $id", actual.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { actual.recycle() }
    }
    private fun rowId(scenario: ActivityScenario<DiagnosticsFixtureActivity>, crash: Boolean = false, index: Int = 0): String {
        var id = ""
        scenario.onActivity { host ->
            val dialog = host.supportFragmentManager.findFragmentByTag("logs") as DiagnosticsDialog
            // Read-only projection receipt; all actions below use actual Flutter accessibility.
            id = dialog.displayIds().getOrElse(index) { "" }
        }
        return (if(crash) "crash-logs" else "app-logs") + "-open-" + id
    }
    @Test fun normalAppDialogLivePrependPauseReleaseAndRecreatedClearCancel() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                driver.await("normal native observer and actual Flutter list") {
                    var active = false; scenario.onActivity { active = it.fixture.subscribers == 1 }; active && exists(driver, "app-logs-list")
                }
                scenario.onActivity { it.fixture.entries.value = listOf(AppLogRow(2, "12:01", "Synthetic second", false)) + it.fixture.entries.value }
                driver.await("live native prepend projection") { var count = 0; scenario.onActivity { count = (it.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model.state.value.logs.size }; count == 2 && exactText(driver, "app-logs-list", "Synthetic second") }
                scenario.moveToState(Lifecycle.State.CREATED)
                driver.await("PAUSE releases the only source observer") { var count = -1; scenario.onActivity { count = it.fixture.subscribers }; count == 0 }
                scenario.moveToState(Lifecycle.State.RESUMED)
                click(driver, "app-logs-clear"); driver.awaitIdentifier("app-logs-confirmation")
                scenario.recreate(); driver.awaitIdentifier("app-logs-confirmation")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                driver.await("Back cancels only the clear confirmation") { !exists(driver, "app-logs-confirmation") }
                scenario.onActivity { assertEquals(0, it.fixture.clears); assertEquals(2, it.fixture.entries.value.size) }
                click(driver, "app-logs-clear"); click(driver, "app-logs-confirm-clear")
                driver.await("actual empty and native clear once") { var cleared = false; scenario.onActivity { cleared = it.fixture.clears == 1 && it.fixture.entries.value.isEmpty() }; cleared && exists(driver, "app-logs-empty") }
            }
        }
    }
    @Test fun normalAppViewportRestoresAfterRecreateFromSmallNativeReceipt() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                scenario.onActivity { host -> host.fixture.entries.value = (0..49).map { AppLogRow(it.toLong() + 1, "12:00", "Synthetic viewport row $it", false) } }
                driver.await("actual synthetic scroll list") { exactText(driver, "app-logs-list", "Synthetic viewport row 0") }
                var scrolled = false
                driver.await("one actual list scroll") {
                    if(scrolled) return@await true
                    val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
                    driver.node("app-logs-list")?.let(nodes::add) ?: return@await false
                    while(nodes.isNotEmpty()) {
                        val node = nodes.removeFirst()
                        if(!scrolled && node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                            scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        for(index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                        node.recycle()
                    }
                    scrolled
                }
                var index = 0; var offset = 0.0
                driver.await("native viewport receipt from real scroll") {
                    scenario.onActivity { host -> val model = (host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model; index = model.scrollIndex; offset = model.scrollOffset }
                    index > 0 && exactText(driver, "app-logs-list", "Synthetic viewport row $index")
                }
                scenario.recreate()
                driver.await("restored native receipt and actual visible business row") {
                    var restored = false; scenario.onActivity { host -> val model = (host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model; restored = model.scrollIndex == index && kotlin.math.abs(model.scrollOffset - offset) < 1.0 }
                    restored && exactText(driver, "app-logs-list", "Synthetic viewport row $index")
                }
            }
        }
    }

    @Test fun normalAppDetailUsesNativeTicketWithoutFullBodyInBundle() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                driver.await("detail action") { rowId(scenario).let { !it.endsWith("-") && exists(driver, it) } }
                click(driver, rowId(scenario))
                driver.await("native TextDialog staging ticket") {
                    var shown = false; scenario.onActivity { host ->
                        val parent = host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog
                        val details = parent.childFragmentManager.fragments.filterIsInstance<TextDialog>().singleOrNull()
                        shown = details?.arguments?.keySet() == setOf("requestId")
                    }; shown
                }
                scenario.recreate()
                driver.await("restored native details without repeated read") { var shown = false; scenario.onActivity { host -> shown = (host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).childFragmentManager.fragments.any { it is TextDialog } }; shown }
            }
        }
    }
    @Test fun normalAppShortAndLargeShareKeepChooserUriPermissionsAndFailureRetry() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                fun export() { click(driver, "app-logs-menu"); click(driver, "app-logs-export") }
                driver.awaitIdentifier("app-logs-list"); export()
                driver.await("short native chooser") { var ready = false; scenario.onActivity { ready = it.fixture.choosers.size == 1 }; ready }
                scenario.onActivity { host ->
                    val send = host.fixture.choosers.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals(Intent.ACTION_SEND, send.action); assertEquals("synthetic-short-export", send.getStringExtra(Intent.EXTRA_TEXT)); assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
                    host.fixture.exportMode = "file"; host.fixture.exportDirectory = host.cacheDir
                }
                export(); driver.await("large native chooser") { var ready = false; scenario.onActivity { ready = it.fixture.choosers.size == 2 }; ready }
                var firstUri: android.net.Uri? = null
                scenario.onActivity { host ->
                    val send = host.fixture.choosers.last().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    firstUri = send.getParcelableExtra(Intent.EXTRA_STREAM)
                    assertEquals("content", firstUri?.scheme); assertEquals(firstUri, send.clipData?.getItemAt(0)?.uri)
                    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertTrue(host.contentResolver.openInputStream(firstUri!!)?.use { it.readBytes().size > 64000 } == true)
                    host.fixture.rejectShare = true
                }
                export(); driver.awaitIdentifier("app-logs-notice")
                scenario.onActivity { assertEquals(2, it.fixture.choosers.size); it.fixture.rejectShare = false }
                export(); driver.await("retry yields independently retained document") { var ready = false; scenario.onActivity { ready = it.fixture.choosers.size == 3 }; ready }
                scenario.onActivity { host ->
                    val send = host.fixture.choosers.last().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertNotEquals(firstUri, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
                    host.fixture.exportMode = "empty"
                }
                export(); driver.awaitIdentifier("app-logs-notice")
                scenario.onActivity { assertEquals(3, it.fixture.choosers.size) }
            }
        }
    }
    @Test fun normalAppRealSystemChoosersForShortAndLargeShareReturnWithoutReplay() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                val chooserPackage = AtomicReference<String?>(null)
                instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                    if(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.endsWith("ChooserActivity") == true)
                        chooserPackage.set(event.packageName?.toString())
                }
                try {
                    for((index, mode) in listOf("text", "file").withIndex()) {
                        chooserPackage.set(null)
                        scenario.onActivity { host -> host.fixture.launchRealChooser = true; host.fixture.exportMode = mode; host.fixture.exportDirectory = host.cacheDir }
                        click(driver, "app-logs-menu"); click(driver, "app-logs-export")
                        driver.await("real Android ChooserActivity owns active window") {
                            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                            try { chooserPackage.get() != null && root.packageName?.toString() == chooserPackage.get() }
                            finally { root.recycle() }
                        }
                        scenario.onActivity { host ->
                            assertEquals(index + 1, host.fixture.choosers.size)
                            val send = host.fixture.choosers.last().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                            if(mode == "file") {
                                val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                                assertEquals("content", uri.scheme); assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
                                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                                assertTrue(host.contentResolver.openInputStream(uri)?.use { it.readBytes().size > 64000 } == true)
                            } else assertEquals("synthetic-short-export", send.getStringExtra(Intent.EXTRA_TEXT))
                            assertNull((host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model.state.value.export)
                        }
                        // Dismiss the chooser only; never choose a receiving target or send data.
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        driver.awaitIdentifier("app-logs-list")
                        scenario.recreate()
                        driver.await("resumed Flutter observer after chooser and recreate") {
                            var ready = false; scenario.onActivity { host -> ready = host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && host.fixture.subscribers == 1 && host.fixture.choosers.size == index + 1 && (host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model.state.value.export == null }
                            ready && exists(driver, "app-logs-list")
                        }
                    }
                } finally { instrumentation.uiAutomation.setOnAccessibilityEventListener(null) }
            }
        }
    }

    @Test fun normalCrashOpaquePageBeyond100AndNativeDetailsRejectArbitraryPath() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch(crash = true).use { scenario ->
                driver.awaitIdentifier("crash-logs-next"); assertFalse(exists(driver, "crash-logs-menu"))
                click(driver, "crash-logs-next")
                driver.await("second bounded page native identity") { var page = 0; scenario.onActivity { page = (it.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).page }; page == 1 && exists(driver, rowId(scenario, true)) }
                scenario.onActivity { host ->
                    val dialog = host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog
                    assertFalse(dialog.displayIds().any { it.contains("fixture-native-") || it.contains("/") })
                    assertFalse(dialog.acceptsDisplayId("file:///private/synthetic-outside"))
                    assertEquals(0, host.fixture.crashReads)
                }
                click(driver, rowId(scenario, true))
                driver.await("native crash detail once") { var read = false; scenario.onActivity { host -> read = host.fixture.crashReads == 1 && (host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).childFragmentManager.fragments.any { it is TextDialog } }; read }
            }
        }
    }
    @Test fun normalAppLivePrependCarriesVisibleBusinessAnchorAcrossPageBoundary() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch().use { scenario ->
                val original = (0..249).map { AppLogRow(it.toLong() + 1, "12:00", "Synthetic boundary row $it", false) }
                scenario.onActivity { it.fixture.entries.value = original }
                click(driver, "app-logs-next")
                driver.await("actual second page") { exactText(driver, "app-logs-list", "Synthetic boundary row 100") }
                var awaitingIndex = -1
                var dispatches = 0
                var lastDispatchResult = false
                var nativeScalars = "not sampled"
                var listFound = false
                var listScalars = "not sampled"
                var actionScalars = "no action dispatched"
                var visibleFixtureIndices = emptyList<Int>()
                val fixtureLabel = Regex("^Synthetic boundary row ([0-9]+)$")
                fun nodeScalars(node: AccessibilityNodeInfo): String {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    return "class=${node.className?.toString()?.take(80)},visible=${node.isVisibleToUser},enabled=${node.isEnabled},scrollable=${node.isScrollable}," +
                        "window=${node.windowId},bounds=$bounds,actions=${node.actionList.take(16).map { it.id }}"
                }
                try {
                    driver.await("actual viewport near the page tail") {
                        var index = 0
                        scenario.onActivity { host ->
                            val dialog = host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog
                            val model = dialog.model
                            val state = model.state.value
                            index = model.scrollIndex
                            nativeScalars = "page=${dialog.page},index=$index,offset=${model.scrollOffset}," +
                                "anchorPresent=${model.scrollAnchor != null},listEstablished=${state.listEstablished}," +
                                "loading=${state.isLoading},working=${state.isWorking},busy=${state.isBusy},errorPresent=${state.error != null}"
                        }
                        if(index >= 94 && exactText(driver, "app-logs-list", "Synthetic boundary row ${100 + index}")) return@await true
                        if(awaitingIndex >= 0 && index <= awaitingIndex) return@await false
                        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
                        val list = driver.node("app-logs-list")
                        listFound = list != null
                        list?.let { listScalars = nodeScalars(it); nodes.add(it) } ?: return@await false
                        val visible = linkedSetOf<Int>()
                        var sent = false
                        while(nodes.isNotEmpty()) {
                            val node = nodes.removeFirst()
                            if (node.isVisibleToUser) {
                                val value = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
                                fixtureLabel.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let(visible::add)
                            }
                            if(!sent && node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                                actionScalars = nodeScalars(node)
                                dispatches++
                                sent = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                lastDispatchResult = sent
                            }
                            for(child in 0 until node.childCount) node.getChild(child)?.let(nodes::add)
                            node.recycle()
                        }
                        visibleFixtureIndices = visible.take(8)
                        if(sent) awaitingIndex = index
                        false
                    }
                } catch (failure: AssertionError) {
                    val failureNodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
                    val failureList = driver.node("app-logs-list")
                    val failureListFound = failureList != null
                    failureList?.let(failureNodes::add)
                    val failureVisible = linkedSetOf<Int>()
                    var sampled = 0
                    while (failureNodes.isNotEmpty() && sampled++ < 512) {
                        val node = failureNodes.removeFirst()
                        if (node.isVisibleToUser) {
                            val value = node.text?.toString()?.takeIf { it.isNotEmpty() }
                                ?: node.contentDescription?.toString().orEmpty()
                            fixtureLabel.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let(failureVisible::add)
                        }
                        for (child in 0 until node.childCount) node.getChild(child)?.let(failureNodes::add)
                        node.recycle()
                    }
                    failureNodes.forEach { it.recycle() }
                    val progressPresent = driver.node("app-logs-progress")?.let { it.recycle(); true } ?: false
                    throw AssertionError("${failure.message}; awaitingIndex=$awaitingIndex,dispatches=$dispatches," +
                        "lastDispatchResult=$lastDispatchResult,listFound=$listFound,native=[$nativeScalars]," +
                        "list=[$listScalars],action=[$actionScalars],lastVisibleFixtureIndices=$visibleFixtureIndices," +
                        "failureListFound=$failureListFound,failureVisibleFixtureIndices=${failureVisible.take(8)},progressPresent=$progressPresent")
                        .also { it.initCause(failure) }
                }
                var anchorIndex = 0; var offset = 0.0
                scenario.onActivity { host ->
                    val model = (host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog).model
                    anchorIndex = 100 + model.scrollIndex; offset = model.scrollOffset
                    assertNotNull(model.scrollAnchor)
                    host.fixture.entries.value = (1..20).map { AppLogRow(-it.toLong(), "12:01", "Synthetic prepend $it", false) } + original
                }
                driver.await("same actual business anchor after it crosses into page two") {
                    var receipt = false
                    scenario.onActivity { host ->
                        val dialog = host.supportFragmentManager.findFragmentByTag("logs") as AppLogDialog
                        receipt = dialog.page == 2 && dialog.model.scrollIndex == (anchorIndex + 20) % 100 && kotlin.math.abs(dialog.model.scrollOffset - offset) < 1.0
                    }
                    receipt && exactText(driver, "app-logs-list", "Synthetic boundary row $anchorIndex")
                }
            }
        }
    }

    @Test fun normalCrashNewOwnerKeepsPageOneAcrossDelayedLoadFailureAndRetry() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch(crash = true).use { scenario ->
                click(driver, "crash-logs-next")
                driver.await("actual page one before owner replacement") { exactText(driver, "crash-logs-list", "Synthetic-100.log") }
                scenario.onActivity { host ->
                    val old = host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog
                    val checkpoint = host.supportFragmentManager.saveFragmentInstanceState(old)
                    host.fixture.crashLoadGate = kotlinx.coroutines.CompletableDeferred()
                    host.supportFragmentManager.beginTransaction().remove(old).commitNow()
                    CrashLogsDialog().apply { setInitialSavedState(checkpoint) }.showNow(host.supportFragmentManager, "logs")
                }
                driver.await("new VM loading has not consumed saved page checkpoint") {
                    var pending = false; scenario.onActivity { host ->
                        val dialog = host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog
                        pending = dialog.model.state.value.isLoading && dialog.model.state.value.logs.isEmpty() && dialog.page == 1
                    }; pending && exists(driver, "crash-logs-progress")
                }
                scenario.onActivity { host -> host.fixture.crashFailure = true; host.fixture.crashLoadGate!!.complete(Unit) }
                driver.awaitIdentifier("crash-logs-error")
                scenario.onActivity { host ->
                    assertEquals(1, (host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).page)
                    host.fixture.crashFailure = false; host.fixture.crashLoadGate = null
                }
                click(driver, "crash-logs-retry")
                driver.await("successful delayed reload restores actual page one") {
                    var restored = false; scenario.onActivity { host -> restored = (host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).page == 1 }
                    restored && exactText(driver, "crash-logs-list", "Synthetic-100.log")
                }
            }
        }
    }

    @Test fun normalCrashPartialClearRefreshesRemainingAndRetryRecoversLoadFailure() {
        FlutterUiTestDriver(instrumentation).use { driver -> driver.enableAccessibility()
            launch(crash = true).use { scenario ->
                driver.awaitIdentifier("crash-logs-list")
                scenario.onActivity { it.fixture.partialClear = true }
                click(driver, "crash-logs-clear"); driver.awaitIdentifier("crash-logs-error")
                scenario.onActivity { host -> assertEquals(1, (host.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).model.state.value.logs.size); host.fixture.partialClear = false; host.fixture.crashFailure = true }
                click(driver, "crash-logs-retry"); driver.awaitIdentifier("crash-logs-error")
                scenario.onActivity { it.fixture.crashFailure = false }
                click(driver, "crash-logs-retry")
                driver.await("native retry settled") { var ready = false; scenario.onActivity { val s = (it.supportFragmentManager.findFragmentByTag("logs") as CrashLogsDialog).model.state.value; ready = !s.isBusy && s.error == null }; ready }
                click(driver, "crash-logs-clear"); driver.awaitIdentifier("crash-logs-empty")
                scenario.onActivity { assertTrue(it.fixture.crashEntries.isEmpty()) }
            }
        }
    }
}
