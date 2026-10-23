package io.legado.app.ui.dict.rule

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Public dialog -> real Flutter controls -> native draft/ActivityResult -> real Room. */
class DictionaryRuleEditScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = FlutterUiTestDriver(instrumentation)
    private val original = "dictionary-original-${UUID.randomUUID()}"
    private val renamed = "dictionary-renamed-${UUID.randomUUID()}"
    private lateinit var scenario: ActivityScenario<AboutActivity>
    @Before fun setup() {
        driver.enableAccessibility()
        runBlocking(Dispatchers.IO) { appDb.dictRuleDao.insert(DictRule(original, "url", "@js:return 1", false, 431)) }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario.onActivity { DictRuleEditDialog(original).showNow(it.supportFragmentManager, "dictionary-edit-test") }
        driver.await("Dictionary rule loaded") { state { !it.loading && !it.loadFailed } }
        driver.awaitIdentifier("dictionary-rule-Name")
        scenario.onActivity { assertTrue(dialog(it).childFragmentManager.fragments.any { child -> child is DictionaryRuleEditFlutterFragment }) }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<CodeEditActivity>().forEach { it.finish() }
        }
        scenario.close(); driver.close()
        runBlocking(Dispatchers.IO) { listOf(original, renamed).mapNotNull { appDb.dictRuleDao.getByName(it) }.forEach { appDb.dictRuleDao.delete(it) } }
    }
    private fun dialog(activity: AboutActivity) = activity.supportFragmentManager.findFragmentByTag("dictionary-edit-test") as DictRuleEditDialog
    private fun state(check: (DictionaryRuleEditUiState) -> Boolean): Boolean {
        var matches = false
        scenario.onActivity { activity -> (activity.supportFragmentManager.findFragmentByTag("dictionary-edit-test") as? DictRuleEditDialog)?.let { matches = check(it.viewModel.state.value) } }
        return matches
    }
    private fun closed() = driver.await("Dictionary dialog closed") {
        var closed = false
        scenario.onActivity { closed = it.supportFragmentManager.findFragmentByTag("dictionary-edit-test") == null }
        closed
    }
    private fun reveal(key: String, forward: Boolean = true) {
        driver.await("Real dictionary field visible: $key") {
            driver.node("dictionary-rule-$key")?.let { it.recycle(); return@await true }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    node.recycle(); queue.forEach { it.recycle() }; return@await false
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                node.recycle()
            }
            false
        }
    }
    private fun input(key: String, text: String) {
        reveal(key)
        driver.setText("dictionary-rule-$key", text)
        driver.await("Native dictionary field acknowledgement") { state { it[DictionaryRuleField.valueOf(key)] == text } }
        closeSoftKeyboard()
    }
    private fun row() = runBlocking(Dispatchers.IO) { appDb.dictRuleDao.getByName(original) }

    @Test fun savesAllEditedFieldsAndClosesAfterRepositorySuccess() {
        input("Name", renamed); input("UrlRule", "https://example.org/?q={{key}}")
        input("ShowRule", "@js:\nreturn 1")
        driver.click("dictionary-rule-save"); closed()
        val saved = runBlocking(Dispatchers.IO) { appDb.dictRuleDao.getByName(renamed) }
        assertEquals("https://example.org/?q={{key}}", saved?.urlRule)
        assertEquals("@js:\nreturn 1", saved?.showRule)
        assertEquals(false, saved?.enabled); assertEquals(431, saved?.sortNumber)
        assertNull(row())
    }
    @Test fun backdropRequestsConfirmationAndKeepLeavesDraftIntact() {
        input("Name", renamed)
        driver.touchDown(8f, driver.bounds("dictionary-rule-backdrop").centerY().toFloat()).close()
        driver.click("dictionary-rule-keep")
        assertTrue(state { it.name == renamed && !it.confirmExit })
        assertEquals(original, row()?.name)
    }
    @Test fun discardClosesWithoutSaving() {
        input("Name", renamed)
        driver.click("dictionary-rule-close"); driver.click("dictionary-rule-discard"); closed()
        assertEquals(original, row()?.name)
        assertNull(runBlocking(Dispatchers.IO) { appDb.dictRuleDao.getByName(renamed) })
    }
    @Test fun copyExportsUnsavedFieldsAsActualJson() {
        input("Name", renamed)
        driver.click("dictionary-rule-menu"); driver.click("dictionary-rule-copy")
        var copied: String? = null
        driver.await("Actual clipboard contains the acknowledged unsaved rule") {
            scenario.onActivity { copied = it.getClipText() }
            runCatching { JsonParser.parseString(copied).asJsonObject["name"].asString == renamed }.getOrDefault(false)
        }
        val json = JsonParser.parseString(copied).asJsonObject
        assertEquals(renamed, json["name"].asString)
        assertFalse(json["enabled"].asBoolean); assertEquals(431, json["sortNumber"].asInt)
    }
    @Test fun pasteImportsEditableFieldsThroughMenu() {
        scenario.onActivity { it.sendToClip("{\"name\":\"$renamed\",\"urlRule\":\"import-url\",\"showRule\":\"@js:return 2\",\"enabled\":true,\"sortNumber\":0}") }
        driver.click("dictionary-rule-menu"); driver.click("dictionary-rule-paste")
        driver.await("Imported fields acknowledged") { state { it.name == renamed && it.urlRule == "import-url" && it.showRule == "@js:return 2" } }
        reveal("ShowRule")
        driver.await("Imported rule rendered") { driver.text("dictionary-rule-ShowRule") == "@js:return 2" }
        driver.click("dictionary-rule-save"); closed()
        val saved = runBlocking(Dispatchers.IO) { appDb.dictRuleDao.getByName(renamed) }
        assertEquals(false, saved?.enabled); assertEquals(431, saved?.sortNumber)
    }
    @Test fun fullscreenActionUsesFocusedFieldAndCursor() {
        input("ShowRule", "@js:return 2")
        val field = requireNotNull(driver.node("dictionary-rule-ShowRule"))
        try { assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 3)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
        })) } finally { field.recycle() }
        driver.await("Fullscreen outgoing native and rendered cursor acknowledged") {
            val native = state { it.selections[2] == 3 && it.selectionEnds[2] == 3 }
            val actual = driver.node("dictionary-rule-ShowRule")
            val rendered = actual?.let { it.textSelectionStart == 3 && it.textSelectionEnd == 3 } ?: false
            actual?.recycle(); native && rendered
        }
        driver.click("dictionary-rule-fullscreen")
        var child: CodeEditActivity? = null
        driver.await("Real code ActivityResult editor loaded") {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                val editor = activity?.findViewById<CodeEditor>(R.id.editText)
                if (editor?.text?.toString() == "@js:return 2" && editor.cursor.left == 3) child = activity
            }
            child != null
        }
        assertEquals(3, child!!.intent.getIntExtra("cursorPosition", -1))
        instrumentation.runOnMainSync { child!!.findViewById<CodeEditor>(R.id.editText).apply { setText("returned rule"); setSelection(0, 4) } }
        driver.click("code-save")
        driver.await("Actual fullscreen result returns to native draft") { state { it.showRule == "returned rule" && it.selections[2] == 4 } }
        assertEquals("@js:return 1", row()?.showRule)
        reveal("ShowRule")
        driver.await("Fullscreen result rendered") { driver.text("dictionary-rule-ShowRule") == "returned rule" }
        driver.click("dictionary-rule-save"); closed()
        assertEquals("returned rule", row()?.showRule)
    }
    @Test fun recreationKeepsDraftSelectionAndFocusWithoutSaving() {
        input("ShowRule", "draft selection")
        val field = requireNotNull(driver.node("dictionary-rule-ShowRule"))
        try { assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
        })) } finally { field.recycle() }
        val deadline = SystemClock.uptimeMillis() + 30_000
        fun selected(): Boolean {
            val native = state { it.focused == DictionaryRuleField.ShowRule && it.selections[2] == 2 && it.selectionEnds[2] == 5 }
            val node = driver.node("dictionary-rule-ShowRule")
            val rendered = node?.let { it.isFocused && it.textSelectionStart == 2 && it.textSelectionEnd == 5 } ?: false
            node?.recycle()
            return native && rendered
        }
        driver.await("Native and rendered selection acknowledged across a real frame") {
            if (!selected()) return@await false
            val frame = java.util.concurrent.CountDownLatch(1)
            val callback = Runnable { frame.countDown() }
            scenario.onActivity { it.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback); it.window.decorView.postInvalidateOnAnimation() }
            try {
                val remaining = deadline - SystemClock.uptimeMillis()
                remaining > 0 && frame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) && selected()
            } finally { scenario.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) } }
        }
        scenario.recreate()
        driver.await("Restored native draft") { state { it.showRule == "draft selection" && it.focused == DictionaryRuleField.ShowRule && it.selections[2] == 2 && it.selectionEnds[2] == 5 } }
        closeSoftKeyboard(); reveal("ShowRule")
        driver.await("Restored actual Flutter selection") {
            driver.node("dictionary-rule-ShowRule")?.let { node ->
                (node.text?.toString() == "draft selection" && node.isFocused && node.textSelectionStart == 2 && node.textSelectionEnd == 5).also { node.recycle() }
            } ?: false
        }
        assertEquals("@js:return 1", row()?.showRule)
        driver.click("dictionary-rule-close"); driver.click("dictionary-rule-discard"); closed()
    }
}
