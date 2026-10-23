package io.legado.app.ui.replace.edit

import android.app.Activity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.ui.code.CodeEditActivity
import java.util.concurrent.atomic.AtomicReference
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.flutter.embedding.android.FlutterFragment
import java.util.ArrayDeque
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceEditorField
import io.legado.app.data.repository.ReplaceEditorText
import io.legado.app.help.config.ReplacePreviewConfig
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReplaceEditorHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = FlutterUiTestDriver(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ReplaceEditActivity>? = null
    private lateinit var model: ReplaceEditorViewModel
    private val ids = mutableListOf<Long>()
    private val sessions = mutableListOf<String>()

    @After
    fun cleanup() {
        scenario?.close()
        driver.close()
        runBlocking(Dispatchers.IO) {
            ids.forEach { id ->
                appDb.replaceRuleDao.findById(id)?.let { appDb.replaceRuleDao.delete(it) }
                ReplacePreviewConfig.removeSample(id)
            }
        }
        sessions.forEach { File(context.filesDir, "replace-editor-drafts/$it.json").delete() }
    }

    private fun launch(rule: ReplaceRule? = null) {
        rule?.let {
            ids += it.id
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(it) }
        }
        driver.enableAccessibility()
        scenario =
            ActivityScenario.launchActivityForResult(
                ReplaceEditActivity.startIntent(
                    context,
                    rule?.id ?: -1,
                    pattern = if (rule == null) "a" else null,
                    isRegex = false,
                    scope = if (rule == null) "Book" else null,
                )
            )
        scenario!!.onActivity {
            model = it.viewModel
            sessions += model.session
        }
        driver.await("native replacement loaded") { model.state.value.loaded }
        scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment }) }
        driver.awaitIdentifier("replace-editor-Name")
    }

    private fun reveal(id: String) {
        driver.await("rendered field $id") {
            driver.node(id)?.let { it.recycle(); return@await true }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            var scrolled = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!scrolled && node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolled = true
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                node.recycle()
            }
            if (scrolled) SystemClock.sleep(150)
            false
        }
    }

    @Test
    fun savedRulePreservesHiddenMetadataAndReturnsSuccessOnlyAfterRoomReceipt() {
        val rule =
            ReplaceRule(
                id = System.currentTimeMillis(),
                name = "Original",
                pattern = "a",
                replacement = "b",
                isRegex = false,
                isEnabled = false,
                order = 42,
                scopeTitle = true,
                scopeSource = true,
                scopeContent = false,
            )
        launch(rule)
        driver.setText("replace-editor-Name", " Edited ")
        driver.await("name ACK") { model.state.value.draft[ReplaceEditorField.Name].text == " Edited " }
        reveal("replace-editor-Sample")
        driver.setText("replace-editor-Sample", "aaa")
        driver.await("sample ACK") { model.state.value.draft[ReplaceEditorField.Sample].text == "aaa" }
        driver.await("native literal preview") { model.state.value.preview == "bbb" }
        reveal("replace-editor-output-text")
        driver.await("rendered literal preview") { driver.text("replace-editor-output-text") == "bbb" }
        driver.click("replace-editor-save")
        driver.await("Room receipt completed") { model.state.value.finished && model.state.value.saved }
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        val saved = runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!! }
        assertEquals(" Edited ", saved.name)
        assertFalse(saved.isEnabled)
        assertEquals(42, saved.order)
        assertTrue(saved.scopeTitle)
        assertTrue(saved.scopeSource)
        assertFalse(saved.scopeContent)
        assertEquals("aaa", ReplacePreviewConfig.sample(rule.id))
    }

    @Test
    fun fullscreenUsesNativeTransferAndReturnsToFlutterBeforeRoomSave() {
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Original", pattern = "a", replacement = "b")
        launch(rule)
        driver.setText("replace-editor-Name", "Draft")
        driver.await("name field ACK before fullscreen") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        driver.click("replace-editor-fullscreen")
        val native = AtomicReference<CodeEditActivity>()
        try {
            driver.await("native fullscreen text transfer") {
                var ready = false
                instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<CodeEditActivity>().singleOrNull()
                    val editor = activity?.findViewById<CodeEditor>(R.id.editText)
                    ready = editor?.isShown == true && editor.isEditable && editor.text.toString() == "Draft"
                    if (ready) native.set(activity)
                }
                ready
            }
            instrumentation.runOnMainSync {
                val editor = native.get().findViewById<CodeEditor>(R.id.editText)
                editor.text.replace(0, editor.text.length, "Fullscreen returned")
            }
            driver.click("code-save")
            driver.await("returned actual Flutter draft") { driver.text("replace-editor-Name") == "Fullscreen returned" }
            assertEquals("Original", runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name })
            driver.click("replace-editor-save")
            driver.await("final Room save") { model.state.value.finished && model.state.value.saved }
            assertEquals("Fullscreen returned", runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name })
        } finally {
            instrumentation.runOnMainSync { native.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish() }
        }
    }

    @Test
    fun recreationRestoresRawSelectionAndScrollWithoutOverwritingDraftFromRoom() {
        launch()
        scenario!!.onActivity {
            model.field(
                ReplaceEditorField.Name,
                ReplaceEditorText("Long name\n".repeat(100), 27, 4),
            )
            model.focus(ReplaceEditorField.Name)
            model.scroll(500)
            runBlocking { model.flushDraft() }
        }
        scenario!!.recreate()
        scenario!!.onActivity { model = it.viewModel }
        driver.await("native replacement loaded") { model.state.value.loaded }
        scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment }) }
        driver.awaitIdentifier("replace-editor-Name")
        assertEquals(
            "Long name\n".repeat(100),
            model.state.value.draft[ReplaceEditorField.Name].text,
        )
        assertEquals(27, model.state.value.draft[ReplaceEditorField.Name].start)
        assertEquals(4, model.state.value.draft[ReplaceEditorField.Name].end)
        assertEquals("Book", model.state.value.draft[ReplaceEditorField.Scope].text)
        instrumentation.waitForIdleSync()
        assertEquals(500, model.state.value.scroll)
    }

    @Test
    fun actualBackShowsDirtyConfirmationAndNoDiscardsWithoutDatabaseWrite() {
        val rule =
            ReplaceRule(
                id = System.currentTimeMillis(),
                name = "Original",
                pattern = "a",
                isRegex = false,
                order = 9,
            )
        launch(rule)
        driver.setText("replace-editor-Name", "Draft")
        driver.await("draft ACK") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        driver.click("replace-editor-keep")
        assertFalse(model.state.value.finished)
        driver.await("draft ACK") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        driver.click("replace-editor-discard")
        driver.await("discard finished") { model.state.value.finished }
        assertFalse(model.state.value.saved)
        assertEquals(
            "Original",
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name },
        )
    }
}
