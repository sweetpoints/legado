package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Test-only host records the real public parent callback after the editor's Room transaction. */
class TxtTocEditorCallbackHost : Fragment(), TxtTocRuleEditDialog.Callback {
    val receipts = mutableListOf<TxtTocRule>()
    val persistedAtCallback = mutableListOf<String?>()
    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        receipts += txtTocRule.copy()
        persistedAtCallback += runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.get(txtTocRule.id)?.name }
    }
}

/** Normal public dialog -> actual Flutter controls -> native CodeEdit/Room/callback. */
class TxtTocRuleEditorScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = FlutterUiTestDriver(instrumentation)
    private val id = System.currentTimeMillis()
    private val otherId = id + 1
    private val createdIds = mutableListOf<Long>()
    private val original = "toc-original-${UUID.randomUUID()}"
    private val renamed = "toc-renamed-${UUID.randomUUID()}"
    private lateinit var scenario: ActivityScenario<AboutActivity>
    @Before fun setup() {
        driver.enableAccessibility()
        runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.insert(TxtTocRule(id, original, "^Chapter", "@js:return result", "Chapter One", 431, false)) }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        show()
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<CodeEditActivity>().forEach { it.finish() }
        }
        scenario.close(); driver.close()
        runBlocking(Dispatchers.IO) { (listOf(id, otherId) + createdIds).mapNotNull { appDb.txtTocRuleDao.get(it) }.forEach { appDb.txtTocRuleDao.delete(it) } }
    }
    private fun show(ruleId: Long? = id, parent: Boolean = false) {
        scenario.onActivity { activity ->
            if (parent) {
                val host = TxtTocEditorCallbackHost()
                activity.supportFragmentManager.beginTransaction().add(host, "toc-callback").commitNow()
                TxtTocRuleEditDialog(ruleId).showNow(host.childFragmentManager, "toc-editor")
            } else TxtTocRuleEditDialog(ruleId).showNow(activity.supportFragmentManager, "toc-editor")
        }
        driver.await("TXT TOC rule load settled") { state { !it.loading } }
        driver.awaitIdentifier("txt-toc-editor-Name")
        scenario.onActivity { assertTrue(dialog(it)!!.childFragmentManager.fragments.any { child -> child is TxtTocRuleEditorFlutterFragment }) }
    }
    private fun dialog(activity: AboutActivity): TxtTocRuleEditDialog? {
        val parent = activity.supportFragmentManager.findFragmentByTag("toc-callback")
        return (parent?.childFragmentManager ?: activity.supportFragmentManager).findFragmentByTag("toc-editor") as? TxtTocRuleEditDialog
    }
    private fun state(check: (TxtTocRuleEditorUiState) -> Boolean): Boolean {
        var result = false
        scenario.onActivity { dialog(it)?.let { d -> result = check(d.viewModel.state.value) } }
        return result
    }
    private fun closed() = driver.await("TXT editor closed") { var done = false; scenario.onActivity { done = dialog(it) == null }; done }
    private fun reveal(key: String, forward: Boolean = true) {
        driver.await("Actual TXT field visible: $key") {
            driver.node("txt-toc-editor-$key")?.let { it.recycle(); return@await true }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    node.recycle(); queue.forEach { it.recycle() }; return@await false
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                node.recycle()
            }
            false
        }
    }
    private fun input(key: String, text: String) {
        reveal(key)
        driver.setText("txt-toc-editor-$key", text)
        driver.await("Native TXT field ACK") { state { it[TxtTocEditorField.valueOf(key)] == text } }
        closeSoftKeyboard()
    }
    private fun row() = runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.get(id) }

    @Test fun saveAllFieldsPersistsBeforeNotifyingParentAndClosesOnce() {
        scenario.onActivity { dialog(it)!!.dismissAllowingStateLoss(); it.supportFragmentManager.executePendingTransactions() }
        show(parent = true)
        input("Name", renamed); input("Regex", "^Chapter (.+)$"); input("Replacement", "@js:return result"); input("Example", "Chapter Two")
        driver.click("txt-toc-editor-save"); closed()
        val saved = row()!!
        assertEquals(renamed, saved.name); assertEquals("^Chapter (.+)$", saved.rule)
        assertEquals("@js:return result", saved.replacement); assertEquals("Chapter Two", saved.example)
        assertEquals(431, saved.serialNumber); assertFalse(saved.enable)
        scenario.onActivity {
            val host = it.supportFragmentManager.findFragmentByTag("toc-callback") as TxtTocEditorCallbackHost
            assertEquals(1, host.receipts.size); assertEquals(id, host.receipts.single().id)
            assertEquals(listOf(renamed), host.persistedAtCallback)
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        scenario.onActivity { assertEquals(1, (it.supportFragmentManager.findFragmentByTag("toc-callback") as TxtTocEditorCallbackHost).receipts.size) }
    }
    @Test fun concurrentDeletionSaveFailureLeavesActualDraftOpenWithoutRecreatingTheRow() {
        input("Name", renamed)
        runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.delete(row()!!) }
        driver.click("txt-toc-editor-save")
        driver.await("Native missing-original save error") { state { it.error != null && !it.saving && !it.finished && !it.pendingCallback } }
        driver.await("Actual failed-save draft retained") { driver.text("txt-toc-editor-Name") == renamed }
        driver.awaitIdentifier("txt-toc-editor-error"); assertNull(row())
        driver.click("txt-toc-editor-close"); driver.click("txt-toc-editor-discard"); closed()
    }
    @Test fun pastedFieldsKeepIdAndCopyExportsCurrentDraftMetadata() {
        scenario.onActivity { it.sendToClip("{\"id\":999,\"name\":\"$renamed\",\"rule\":\"^New\",\"replacement\":\"return result\",\"example\":\"New example\",\"enable\":true,\"serialNumber\":0}") }
        driver.click("txt-toc-editor-menu"); driver.click("txt-toc-editor-paste")
        driver.await("Pasted native fields") { state { it.name == renamed && it.regex == "^New" && it.replacement == "return result" && it.example == "New example" } }
        reveal("Example"); driver.await("Actual pasted example") { driver.text("txt-toc-editor-Example") == "New example" }
        driver.click("txt-toc-editor-menu"); driver.click("txt-toc-editor-copy")
        var copied: String? = null
        driver.await("Actual clipboard exports acknowledged current draft") {
            scenario.onActivity { copied = it.getClipText() }
            runCatching { com.google.gson.JsonParser.parseString(copied).asJsonObject["name"].asString == renamed }.getOrDefault(false)
        }
        val json = com.google.gson.JsonParser.parseString(copied).asJsonObject
        assertEquals(id, json["id"].asLong); assertEquals(431, json["serialNumber"].asInt); assertFalse(json["enable"].asBoolean)
        assertEquals("^New", json["rule"].asString); assertEquals("return result", json["replacement"].asString); assertEquals("New example", json["example"].asString)
        driver.click("txt-toc-editor-save"); closed(); assertEquals(renamed, row()?.name)
    }
    @Test fun actualBackdropKeepThenDiscardDoesNotWriteRoom() {
        input("Name", renamed)
        val backdrop = driver.bounds("txt-toc-editor-backdrop"); val toolbar = driver.bounds("txt-toc-editor-close")
        val window = android.graphics.Rect(); scenario.onActivity { dialog(it)!!.requireDialog().window!!.decorView.getGlobalVisibleRect(window) }
        assertTrue(backdrop.intersect(window)); assertTrue(toolbar.top > backdrop.top + 8)
        driver.touchDown(backdrop.exactCenterX(), (backdrop.top + toolbar.top) / 2f).close()
        driver.click("txt-toc-editor-keep")
        // Keep enables the text field again and restores Flutter focus on its next frame.
        // A hide sent before that frame can be followed by the restored field's IME show.
        val deadline = SystemClock.uptimeMillis() + 30_000
        var hideRequested = false
        driver.await("Keep ACK and owned dialog IME hidden before actual Android Back") {
            if (SystemClock.uptimeMillis() >= deadline) return@await false
            if (!state { it.name == renamed && !it.confirmExit && it.focused == TxtTocEditorField.Name }) return@await false
            val field = driver.node("txt-toc-editor-Name") ?: return@await false
            val restored = try { field.isEnabled && field.isFocused && field.text?.toString() == renamed } finally { field.recycle() }
            if (!restored) return@await false
            if (!hideRequested) {
                val frame = java.util.concurrent.CountDownLatch(1)
                val callback = Runnable { frame.countDown() }
                scenario.onActivity {
                    val decor = dialog(it)!!.requireDialog().window!!.decorView
                    decor.viewTreeObserver.registerFrameCommitCallback(callback)
                    decor.postInvalidateOnAnimation()
                }
                val committed = try {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    remaining > 0 && frame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                } finally {
                    scenario.onActivity { dialog(it)!!.requireDialog().window!!.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
                }
                if (!committed) return@await false
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val window = dialog(it)!!.requireDialog().window!!
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                hideRequested = true
            }
            var hidden = false
            scenario.onActivity {
                hidden = androidx.core.view.ViewCompat.getRootWindowInsets(dialog(it)!!.requireDialog().window!!.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
            }
            hidden
        }
        assertEquals(original, row()?.name)
        androidx.test.espresso.Espresso.pressBack()
        driver.awaitIdentifier("txt-toc-editor-discard")
        scenario.onActivity { assertFalse("Native Back must not close the parent Activity", it.isFinishing) }
        driver.click("txt-toc-editor-discard"); closed(); assertEquals(original, row()?.name)
        scenario.onActivity { assertFalse(it.isFinishing) }
    }
    @Test fun fullscreenReplacementTransfersRealCursorAndReturnsBeforeFinalRoomSave() {
        val text = "return result"
        input("Replacement", text)
        val field = requireNotNull(driver.node("txt-toc-editor-Replacement"))
        try { assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 3); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
        })) } finally { field.recycle() }
        driver.await("Native and actual outgoing cursor") {
            val native = state { it.cursors[2] == 3 && it.selectionEnds[2] == 3 && it.focused == TxtTocEditorField.Replacement }
            val node = driver.node("txt-toc-editor-Replacement")
            val actual = node?.let { it.isFocused && it.textSelectionStart == 3 && it.textSelectionEnd == 3 } ?: false
            node?.recycle(); native && actual
        }
        driver.click("txt-toc-editor-fullscreen")
        var code: CodeEditActivity? = null
        driver.await("Native fullscreen Sora contains text and actual cursor") {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().singleOrNull()
                val editor = activity?.findViewById<CodeEditor>(R.id.editText)
                if (editor?.text?.toString() == text && editor.cursor.left == 3) code = activity
            }; code != null
        }
        instrumentation.runOnMainSync { code!!.findViewById<CodeEditor>(R.id.editText).apply { setText("return changed"); setSelection(0, 4) } }
        driver.click("code-save")
        driver.await("Native fullscreen result ACK") { state { it.replacement == "return changed" && it.cursors[2] == 4 && !it.codeEditing } }
        reveal("Replacement"); driver.await("Actual returned field and focus") {
            driver.node("txt-toc-editor-Replacement")?.let { (it.text?.toString() == "return changed" && it.isFocused).also { _ -> it.recycle() } } ?: false
        }
        assertEquals("@js:return result", row()?.replacement)
        driver.click("txt-toc-editor-save"); closed(); assertEquals("return changed", row()?.replacement)
    }
    @Test fun recreationRestoresDraftRawSelectionAndFocusWithoutWritingRoom() {
        input("Replacement", "draft selection")
        val field = requireNotNull(driver.node("txt-toc-editor-Replacement"))
        try { assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 8); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
        })) } finally { field.recycle() }
        fun selected(): Boolean {
            val native = state { it.focused == TxtTocEditorField.Replacement && it.cursors[2] == 8 && it.selectionEnds[2] == 2 }
            val node = driver.node("txt-toc-editor-Replacement")
            val actual = node?.let { it.isFocused && it.textSelectionStart == 8 && it.textSelectionEnd == 2 } ?: false
            node?.recycle(); return native && actual
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        driver.await("Reverse native and rendered selection acknowledged across frame") {
            if (!selected()) return@await false
            val frame = java.util.concurrent.CountDownLatch(1); val callback = Runnable { frame.countDown() }
            scenario.onActivity { it.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback); it.window.decorView.postInvalidateOnAnimation() }
            try { val remaining = deadline - SystemClock.uptimeMillis(); remaining > 0 && frame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) && selected() }
            finally { scenario.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) } }
        }
        scenario.recreate()
        driver.await("Native draft restored") { state { !it.loading && it.replacement == "draft selection" && it.cursors[2] == 8 && it.selectionEnds[2] == 2 } }
        closeSoftKeyboard(); reveal("Replacement")
        driver.await("Actual restored raw selection and focus") { selected() && driver.text("txt-toc-editor-Replacement") == "draft selection" }
        assertEquals("@js:return result", row()?.replacement)
        driver.click("txt-toc-editor-close"); driver.click("txt-toc-editor-discard"); closed()
    }
    @Test fun invalidRegexKeepsActualDraftOpenWithoutCallbackOrRoomWrite() {
        input("Regex", "[")
        driver.click("txt-toc-editor-save")
        driver.await("Native regex validation error") { state { it.error != null && !it.saving && !it.finished && !it.pendingCallback } }
        driver.awaitIdentifier("txt-toc-editor-error"); assertEquals("^Chapter", row()?.rule)
        reveal("Regex", false); assertEquals("[", driver.text("txt-toc-editor-Regex"))
        driver.click("txt-toc-editor-close"); driver.click("txt-toc-editor-discard"); closed()
    }
    @Test fun loadFailureRetryUsesTheOriginalRequestedIdAndMetadata() {
        scenario.onActivity { dialog(it)!!.dismissAllowingStateLoss(); it.supportFragmentManager.executePendingTransactions() }
        show(otherId)
        driver.await("Missing rule load error") { state { it.loadFailed } }
        driver.awaitIdentifier("txt-toc-editor-retry")
        runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.insert(TxtTocRule(otherId, "restored", "^Retry", "rep", "sample", 123, false)) }
        assertEquals("restored", runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.get(otherId)?.name })
        // Retry is an action of this button, never of a clickable ancestor such as the backdrop.
        // Wait for its actual advertised descendant and dispatch the business action only once.
        driver.await("Actual owned Retry button advertises CLICK") {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            driver.node("txt-toc-editor-retry")?.let(queue::add) ?: return@await false
            var dispatched = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!dispatched && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    dispatched = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                node.recycle()
            }
            dispatched
        }
        var receipt = "missing dialog"
        try {
            driver.await("Reloaded original rule") {
                var identity = "dialog missing"
                scenario.onActivity { host -> dialog(host)?.let { editor ->
                    identity = "requestedId=${editor.arguments?.getLong("id")},nativeId=${editor.viewModel.currentRule().id}"
                } }
                state {
                    receipt = "$identity,loading=${it.loading},loadFailed=${it.loadFailed},finished=${it.finished}," +
                        "confirmExit=${it.confirmExit},nameMatches=${it.name == "restored"},error=${it.error}"
                    !it.loading && !it.loadFailed && !it.finished && it.name == "restored"
                }
            }
        } catch (failure: AssertionError) {
            throw AssertionError("Retry Room id=$otherId; native receipt: $receipt", failure)
        }
        driver.await("Actual retry field") { driver.text("txt-toc-editor-Name") == "restored" }
        driver.click("txt-toc-editor-save"); closed()
        val saved = runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.get(otherId)!! }
        assertEquals(123, saved.serialNumber); assertFalse(saved.enable); assertEquals("^Retry", saved.rule)
    }
    @Test fun defaultConstructorCreatesANewStableRoomRuleWithDefaultMetadata() {
        scenario.onActivity { dialog(it)!!.dismissAllowingStateLoss(); it.supportFragmentManager.executePendingTransactions() }
        show(ruleId = null)
        var created = 0L
        scenario.onActivity { created = dialog(it)!!.viewModel.currentRule().id; createdIds += created }
        input("Name", renamed); input("Regex", "^New")
        scenario.onActivity { assertEquals(created, dialog(it)!!.viewModel.currentRule().id) }
        driver.click("txt-toc-editor-save"); closed()
        val saved = runBlocking(Dispatchers.IO) { appDb.txtTocRuleDao.get(created)!! }
        assertEquals(renamed, saved.name); assertEquals("^New", saved.rule); assertEquals(-1, saved.serialNumber); assertTrue(saved.enable)
        assertEquals(original, row()?.name)
    }

}
