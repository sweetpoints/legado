package io.legado.app.ui.highlight.edit

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.utils.GSON
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Native Compose queries below target only the retained management/style adapters. */
class HighlightRuleEditorHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val driver = FlutterUiTestDriver(InstrumentationRegistry.getInstrumentation())
    @Before fun accessibility() = driver.enableAccessibility()
    @After fun cleanup() = driver.close()
    private fun editor(activity: androidx.fragment.app.FragmentActivity): HighlightRuleEditDialog =
        activity.supportFragmentManager.fragments.filterIsInstance<HighlightRuleEditDialog>().single()
    private fun ready() = driver.awaitIdentifier("highlight-rule-name")
    private fun reveal(id: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        driver.await("actual highlight editor field $id") {
            driver.node(id)?.let { it.recycle(); return@await true }
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(pending::add)
            var scrolled = false
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (!scrolled && node.isVisibleToUser && node.isScrollable)
                    scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                node.recycle()
            }
            false
        }
    }
    private fun actualSelection(id: String, start: Int, end: Int) {
        driver.click(id)
        driver.await("actual selection action $id") {
            val node = driver.node(id) ?: return@await false
            try { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }) } finally { node.recycle() }
        }
        driver.await("actual focused raw selection $id") {
            driver.node(id)?.let { node -> try { node.isFocused && node.textSelectionStart == start && node.textSelectionEnd == end } finally { node.recycle() } } ?: false
        }
    }
    @Test fun normalManagerEditorRestoresRawDraftAndStyleThenSavesAllMetadata() {
        val original = HighlightRule(name = "Editor ${UUID.randomUUID()}", pattern = "literal", scope = "Book", isEnabled = false,
            order = 777, timeoutMillisecond = 4567, group = "Original", applyToBody = false, applyToTitle = true)
            .apply { applyStyle(HighlightStyle(fill = 123, fontPath = "font", fontSize = 25f)) }
        val id = runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.insert(original).single() }
        try {
            ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                // One original 30s entry deadline covers native readiness, the single real
                // button click, native dialog creation and rendered Flutter accessibility.
                val entryDeadline = SystemClock.uptimeMillis() + 30_000
                var entryDiagnosis = "manager state not sampled"
                fun sampleEntry(): HighlightRuleEditDialog? {
                    var active: HighlightRuleEditDialog? = null
                    scenario.onActivity { host ->
                        val state = host.viewModel.state.value
                        val fragments = host.supportFragmentManager.fragments
                        active = fragments.filterIsInstance<HighlightRuleEditDialog>().singleOrNull()
                        entryDiagnosis = "loaded=${state.loaded}, rowsReady=${state.rowsReady}, " +
                            "busy=${state.busy}, canAct=${state.canAct}, error=${state.error}, " +
                            "rowPresent=${state.rules.any { it.uuid == original.uuid }}, " +
                            "effects=${state.draft.effects.map { it.action to it.id }}, " +
                            "selection=${state.draft.selection}, fragments=${fragments.map { it.javaClass.simpleName to it.lifecycle.currentState }}, " +
                            "dialogAdded=${active?.isAdded}, dialogShowing=${active?.dialog?.isShowing}, " +
                            "children=${active?.childFragmentManager?.fragments?.map { it.javaClass.simpleName to it.lifecycle.currentState }}"
                    }
                    return active
                }
                fun awaitEntry(description: String, condition: () -> Boolean) {
                    // Compose v2 uses StandardTestDispatcher: external Thread.sleep polling
                    // does not dispatch the Route's queued LaunchedEffect continuation.
                    // waitUntil advances Compose work while preserving this entry's one budget.
                    val remaining = (entryDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1)
                    try {
                        compose.waitUntil(timeoutMillis = remaining, condition = condition)
                    } catch (timeout: ComposeTimeoutException) {
                        sampleEntry()
                        throw AssertionError("Timed out waiting for $description within shared 30s entry deadline; $entryDiagnosis", timeout)
                    }
                }
                awaitEntry("normal manager enabled edit entry") {
                    sampleEntry()
                    var available = false
                    scenario.onActivity { host ->
                        val state = host.viewModel.state.value
                        available = state.canAct && state.rules.any { it.uuid == original.uuid }
                    }
                    available
                }
                compose.onNodeWithTag("highlight-management-edit-${original.uuid}")
                    .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                awaitEntry("native editor fragment after single manager edit click") {
                    sampleEntry()?.isAdded == true
                }
                awaitEntry("rendered Flutter highlight-rule-name after manager entry") {
                    sampleEntry()
                    driver.node("highlight-rule-name")?.let { it.recycle(); true } ?: false
                }
                driver.setText("highlight-rule-name", "Edited")
                reveal("highlight-rule-group"); driver.setText("highlight-rule-group", " New group ")
                scenario.onActivity { editor(it).onHighlightStyleChanged(editor(it).currentHighlightStyle().copy(bold = true)) }
                actualSelection("highlight-rule-name", 1, 4)
                scenario.onActivity { host -> assertTrue(editor(host).childFragmentManager.fragments.any { it is FlutterFragment }) }
                scenario.recreate(); ready()
                driver.await("restored actual raw selection and focus") {
                    driver.node("highlight-rule-name")?.let { node -> try {
                        node.text?.toString() == "Edited" && node.isFocused && node.textSelectionStart == 1 && node.textSelectionEnd == 4
                    } finally { node.recycle() } } ?: false
                }
                assertEquals(" New group ", driver.text("highlight-rule-group"))
                scenario.onActivity { assertTrue(editor(it).currentHighlightStyle().bold) }
                driver.click("highlight-rule-save")
                driver.await("Room metadata save") { runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.findById(id)?.name == "Edited" } }
                val saved = runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.findById(id) }!!
                assertEquals(original.uuid, saved.uuid); assertFalse(saved.isEnabled); assertEquals(777, saved.order)
                assertEquals(4567L, saved.timeoutMillisecond); assertFalse(saved.applyToBody); assertTrue(saved.applyToTitle)
                assertEquals("New group", saved.group); assertTrue(saved.styleObj().bold); assertEquals("font", saved.styleObj().fontPath); assertEquals(25f, saved.styleObj().fontSize)
                driver.await("editor closed once") { var gone = false; scenario.onActivity { gone = it.supportFragmentManager.fragments.none { f -> f is HighlightRuleEditDialog } }; gone }
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.findById(id)?.let { appDb.highlightRuleDao.delete(it) } } }
    }
    @Test fun newSeedAndIndependentColorDraftRestoreWithHighBitBeforeSingleEditorRoomSave() {
        val pattern = "Rule ${UUID.randomUUID()}"
        val manual = BookHighlight(time = System.nanoTime(), bookText = pattern, bookName = "Book", bookUrl = "https://manual.invalid")
        runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.insert(manual) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val dialog = HighlightRuleEditDialog.create(pattern, scope = "Book", style = GSON.toJson(HighlightStyle(fill = 321)))
                    assertFalse(dialog.requireArguments().containsKey("pattern")); assertTrue(dialog.requireArguments().getString("seed")!!.length < 100)
                    dialog.show(host.supportFragmentManager, "editor")
                }
                ready(); scenario.onActivity { editor(it).pickHighlightColor(HighlightStyleDialog.HL_FILL, 321, true) }
                driver.awaitIdentifier("highlight-rule-color-hex"); driver.setText("highlight-rule-color-hex", "#80123456")
                scenario.recreate(); driver.awaitIdentifier("highlight-rule-color-hex")
                driver.await("actual restored color hex") { driver.text("highlight-rule-color-hex") == "#80123456" }
                driver.click("highlight-rule-color-save"); driver.awaitIdentifier("highlight-rule-save")
                driver.await("native draft color ACK") { var same = false; scenario.onActivity { same = editor(it).currentHighlightStyle().fill == 0x80123456.toInt() }; same }
                assertTrue(runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.all.none { it.pattern == pattern } })
                driver.click("highlight-rule-save")
                driver.await("one inserted editor rule") { runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.all.count { it.pattern == pattern } == 1 } }
                assertEquals(GSON.toJson(manual), GSON.toJson(runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.all.first { it.time == manual.time } }))
            }
        } finally { runBlocking(Dispatchers.IO) {
            appDb.highlightRuleDao.all.filter { it.pattern == pattern }.forEach { appDb.highlightRuleDao.delete(it) }; appDb.bookHighlightDao.delete(manual)
        } }
    }
    @Test fun normalEditorStyleAdapterColorCancelAndEditorCancelNeverWriteRoom() {
        val pattern = "Cancel ${UUID.randomUUID()}"
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { HighlightRuleEditDialog.create(pattern, style = GSON.toJson(HighlightStyle(fill = 321))).show(it.supportFragmentManager, "editor") }
            ready(); reveal("highlight-rule-style"); driver.click("highlight-rule-style")
            compose.onNodeWithTag("highlight-style-color-Fill").performScrollTo().performClick()
            driver.awaitIdentifier("highlight-rule-color-hex"); driver.setText("highlight-rule-color-hex", "#80223344")
            driver.click("highlight-rule-color-cancel")
            scenario.onActivity { assertEquals(321, editor(it).currentHighlightStyle().fill) }
            compose.onNodeWithTag("highlight-style-color-Fill").performScrollTo().performClick()
            driver.awaitIdentifier("highlight-rule-color-hex"); driver.setText("highlight-rule-color-hex", "#FF123456"); driver.click("highlight-rule-color-save")
            driver.await("style adapter receives selected color exactly once") { var ok = false; scenario.onActivity { ok = editor(it).currentHighlightStyle().fill == 0xff123456.toInt() }; ok }
            scenario.onActivity { editor(it).requestBackFromHost() }
            driver.awaitIdentifier("highlight-rule-cancel"); driver.click("highlight-rule-cancel")
            driver.await("cancel closes editor") { var gone = false; scenario.onActivity { gone = it.supportFragmentManager.fragments.none { f -> f is HighlightRuleEditDialog } }; gone }
            assertTrue(runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.all.none { it.pattern == pattern } })
        }
    }
    @Test fun nativeBackDuringColdDialogBootstrapClosesOnlyDialogAndNeverParentActivity() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val dialog = HighlightRuleEditDialog.create("Cold ${UUID.randomUUID()}")
                dialog.showNow(host.supportFragmentManager, "editor")
                dialog.requestBackFromHost()
            }
            driver.await("cold dialog safely closed") { var closed = false; scenario.onActivity { host ->
                closed = host.supportFragmentManager.fragments.none { it is HighlightRuleEditDialog }
                assertFalse(host.isFinishing); assertFalse(host.isDestroyed)
            }; closed }
        }
    }
    @Test fun nativeReaderPreviewPngIsBoundedCachedAndChangesForActualStyle() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val renderer = HighlightRuleFlutterPreview(host.resources)
                val first = renderer.render(HighlightStyle(fill = 0xffff0000.toInt()))
                assertSame(first, renderer.render(HighlightStyle(fill = 0xffff0000.toInt())))
                val changed = renderer.render(HighlightStyle(fill = 0xff00ff00.toInt()))
                assertNotEquals(first["inputSha256"], changed["inputSha256"])
                val a = first["png"] as ByteArray; val b = changed["png"] as ByteArray
                assertTrue(a.size <= 256 * 1024); assertFalse(a.contentEquals(b))
                val bitmap = BitmapFactory.decodeByteArray(a, 0, a.size)
                try { assertTrue(bitmap.width in 1..2048); assertTrue(bitmap.height in 1..2048) } finally { bitmap.recycle() }
                assertNotSame(first, renderer.render(HighlightStyle(fill = 0xffff0000.toInt())))
            }
        }
    }
}
