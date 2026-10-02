package io.legado.app.ui.autoTask

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.repository.AutoTaskEditorField
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class AutoTaskEditorHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun row(id: String) = runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.getById(id) }
    private fun fixture(id: String) = AutoTaskRule(id, "Task $id", false, "0 * * * *", script = "42", customOrder = 17, lastRunAt = 50, lastLog = "runtime")
    private fun navigate(field: AutoTaskEditorField) {
        compose.onNodeWithTag("task-editor-navigation").performClick()
        compose.onNodeWithTag("task-editor-navigate-" + field.name).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithTag("task-editor-" + field.name).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitLoaded(scenario: ActivityScenario<AutoTaskEditActivity>) {
        compose.waitUntil { var loaded = false; scenario.onActivity { loaded = !it.viewModel.state.value.loading }; loaded }
    }
    @Test fun pasteKeepsOriginalIdAndMetadataThenSaveIsAwaitedBeforeDebugAndLoginLaunch() {
        val id = UUID.randomUUID().toString(); val original = fixture(id)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val prior = clipboard.primaryClip; val intents = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val destination = intent.component?.className.orEmpty()
                if (destination.endsWith("AutoTaskDebugActivity") || destination.endsWith("SourceLoginActivity")) {
                    intents += Intent(intent); return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }; return null
            }
        }
        runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.upsert(original) }
        try {
            ActivityScenario.launch<AutoTaskEditActivity>(AutoTaskEditActivity.intent(context, id)).use { scenario ->
                waitLoaded(scenario); instrumentation.addMonitor(monitor)
                val pasted = AutoTaskRule("foreign", "Pasted", false, "0 * * * *", "https://login.invalid", "ui", "check", "comment", "43", "headers", "lib", "3", false, 999, 999, "foreign", "foreign", "foreign")
                instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("rule", GSON.toJson(pasted))) }
                compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.paste_rule)).performClick()
                compose.waitUntil { var ready = false; scenario.onActivity { ready = it.viewModel.state.value.draft[AutoTaskEditorField.Name].text == "Pasted" }; ready }
                assertEquals(original, row(id)); assertNull(row("foreign"))
                compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.copy_rule)).performClick()
                compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("Pasted") == true }
                val copied = GSON.fromJsonArray<AutoTaskRule>(clipboard.primaryClip!!.getItemAt(0).text.toString()).getOrThrow().single()
                assertEquals(id, copied.id); assertEquals("headers", copied.header); assertEquals("check", copied.loginCheckJs)
                compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.auto_task_debug)).performClick()
                compose.waitUntil { intents.size == 1 }
                assertEquals(id, intents.last().getStringExtra("autoTaskId"))
                val saved = row(id)!!; assertEquals("43", saved.script); assertEquals(17, saved.customOrder); assertEquals("runtime", saved.lastLog); assertEquals(50L, saved.lastRunAt)
                assertEquals("headers", saved.header); assertFalse(saved.enabledCookieJar)
                compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.login)).performClick()
                compose.waitUntil { intents.size == 2 }; assertEquals("autoTask", intents.last().getStringExtra("type")); assertEquals(id, intents.last().getStringExtra("key"))
                scenario.recreate(); waitLoaded(scenario); assertEquals(2, intents.size)
                compose.onNodeWithTag("task-editor-back").performClick()
                compose.onNodeWithText(context.getString(R.string.exit_no_save)).assertDoesNotExist()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { if (prior != null) clipboard.setPrimaryClip(prior) else clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
            runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) }
        }
    }
    @Test fun realNativeCodeEditorFileResultAndCursorReturnToFocusedFieldWithoutSavingTask() {
        ActivityScenario.launch(AutoTaskEditActivity::class.java).use { scenario ->
            waitLoaded(scenario); navigate(AutoTaskEditorField.Script)
            compose.onNodeWithTag("task-editor-Script").performTextReplacement("initial body")
            compose.onNodeWithTag("task-editor-Script").performTextInputSelection(androidx.compose.ui.text.TextRange(4))
            scenario.recreate(); waitLoaded(scenario)
            compose.onNodeWithTag("task-editor-fullscreen").performClick()
            var child: CodeEditActivity? = null
            compose.waitUntil(10000) {
                instrumentation.runOnMainSync {
                    child = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                }
                child != null
            }
            val editor = requireNotNull(child)
            assertTrue(editor.intent.getBooleanExtra("useTextFile", false)); assertEquals(4, editor.intent.getIntExtra("cursorPosition", -1))
            assertTrue(editor.intent.getBooleanExtra("returnUnchangedText", false))
            compose.waitUntil(10000) { var ready = false; instrumentation.runOnMainSync { ready = editor.findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText).text.toString() == "initial body" }; ready }
            val path = editor.intent.getStringExtra("textFile")!!
            instrumentation.runOnMainSync { editor.findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText).setText("returned native body"); editor.findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText).setSelection(0, 7) }
            closeSoftKeyboard(); onView(withId(R.id.menu_save)).perform(click())
            compose.waitUntil(10000) { var returned = false; scenario.onActivity { returned = !it.viewModel.state.value.editorPending && it.viewModel.state.value.draft[AutoTaskEditorField.Script].text == "returned native body" }; returned }
            scenario.onActivity { assertEquals(7, it.viewModel.state.value.draft[AutoTaskEditorField.Script].start) }
            assertFalse(java.io.File(path).exists())
            compose.onNodeWithTag("task-editor-Script").assertTextEquals("returned native body")
            closeSoftKeyboard(); pressBack(); compose.onNodeWithText(context.getString(R.string.exit_no_save)).assertExists()
            compose.onNodeWithTag("task-editor-keep").performClick(); compose.onNodeWithTag("task-editor-back").performClick()
            compose.onNodeWithTag("task-editor-discard").performClick()
        }
    }
    @Test fun missingLoginStillPersistsAndActualSaveCloseReportsResultOk() {
        val id = UUID.randomUUID().toString(); runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.upsert(fixture(id)) }
        try {
            ActivityScenario.launchActivityForResult<AutoTaskEditActivity>(AutoTaskEditActivity.intent(context, id)).use { scenario ->
                waitLoaded(scenario); navigate(AutoTaskEditorField.Name); compose.onNodeWithTag("task-editor-Name").performTextReplacement("Saved without login")
                compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(context.getString(R.string.login)).performClick()
                compose.waitUntil { row(id)?.name == "Saved without login" }
                compose.onNodeWithText(context.getString(R.string.source_no_login)).assertExists()
                compose.onNodeWithTag("task-editor-save").performClick()
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) } }
    }
}
