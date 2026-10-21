package io.legado.app.ui.rss.source.edit

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RssSourceEditorField
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.*
import org.junit.Assert.*

/** Existing host contracts now drive the normal Flutter entry rather than legacy Compose tags. */
class RssSourceEditorHostTest {
    private val robot = RssEditorFlutterRobot()
    private val driver get() = robot.driver
    private val instrumentation get() = robot.instrumentation
    private val context get() = robot.context
    private var priorHelp = 0
    private val key = "https://rss-host-${UUID.randomUUID()}.invalid/"
    private val renamed = "$key/new"
    private val children = mutableListOf<Activity>()
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && activity.callingActivity?.className == RssSourceEditActivity::class.java.name) children.add(activity)
    }
    @Before fun before() {
        priorHelp = LocalConfig.getInt("ruleHelpVersion", 0)
        LocalConfig.edit().putInt("ruleHelpVersion", 1).commit()
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
    }
    @After fun after() {
        instrumentation.runOnMainSync {
            children.reversed().forEach { if (!it.isFinishing && !it.isDestroyed) it.finish() }
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        }
        robot.close()
        for (url in listOf(key, renamed)) { appDb.rssSourceDao.delete(url); appDb.rssStarDao.delete(url) }
        LocalConfig.edit().putInt("ruleHelpVersion", priorHelp).commit()
    }
    private fun row(url: String) = appDb.rssSourceDao.getByKey(url)
    private fun fixture() = RssSource(key, "Name", enabled = false, customOrder = 17, lastUpdateTime = 50)

    @Test fun realPasteCopyAndRenameSaveAreAwaitedBeforeDebugLoginLaunchAndRelatedOriginsMoveOnce() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val prior = clipboard.primaryClip
        val intents = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className?.let { it.endsWith("RssSourceDebugActivity") || it.endsWith("SourceLoginActivity") } == true) {
                    assertEquals("Pasted", row(renamed)!!.sourceName); assertNull(row(key))
                    intents += Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        appDb.rssSourceDao.insert(fixture())
        appDb.rssStarDao.insert(RssStar(origin = key, link = "fixture"))
        try {
            robot.launch(key)
            instrumentation.addMonitor(monitor)
            val pasted = RssSource(renamed, "Pasted", enabled = false,
                loginUrl = "https://login.invalid", loginUi = "ui", loginCheckJs = "check",
                header = "header", jsLib = "lib", contentWhitelist = "allow", contentBlacklist = "deny",
                shouldOverrideUrlLoading = "@js:url", startHtml = "html", ruleImage = "img", nextContentUrl = ".next",
                customOrder = 999, lastUpdateTime = 999)
            instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("source", GSON.toJson(pasted))) }
            robot.menu("Paste")
            driver.await("Actual paste updates private RSS draft") {
                var ready = false
                robot.scenario!!.onActivity { ready = it.viewModel.state.value.draft[RssSourceEditorField.SourceName].text == "Pasted" }
                ready
            }
            assertEquals("Name", row(key)!!.sourceName); assertNull(row(renamed))
            robot.menu("Clipboard")
            driver.await("Actual copied RSS JSON reaches Android clipboard") { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("Pasted") == true }
            val copied = GSON.fromJsonObject<RssSource>(clipboard.primaryClip!!.getItemAt(0).text.toString()).getOrThrow()
            assertEquals(renamed, copied.sourceUrl); assertEquals(17, copied.customOrder)
            assertEquals("allow", copied.contentWhitelist); assertEquals("lib", copied.jsLib)
            robot.menu("Debug")
            driver.await("Saved debug launches once") { intents.size == 1 }
            assertEquals(renamed, intents.single().getStringExtra("key")); assertEquals(17, row(renamed)!!.customOrder)
            assertNotNull(appDb.rssStarDao.get(renamed, "fixture")); assertNull(appDb.rssStarDao.get(key, "fixture"))
            robot.menu("Login")
            driver.await("Saved login launches once") { intents.size == 2 }
            assertEquals("rssSource", intents.last().getStringExtra("type")); assertEquals(renamed, intents.last().getStringExtra("key"))
            robot.recreate()
            assertEquals(2, intents.size)
            robot.scenario!!.onActivity { assertEquals(renamed, it.viewModel.sourceUrl) }
            driver.click("rss-close"); robot.finished()
            assertNull(driver.node("rss-discard-confirm"))
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { clipboard.setPrimaryClip(prior ?: ClipData.newPlainText("", "")) }
        }
    }

    @Test fun realCodeFileEditorCursorAndBackUnsavedConfirmationSurviveActivityRecreation() {
        robot.launch()
        robot.input(RssSourceEditorField.StartJs, "initial body")
        robot.select(RssSourceEditorField.StartJs, 4, 4)
        robot.recreate()
        driver.awaitIdentifier("rss-field-startJs")
        val captured = AtomicReference<Intent>()
        val capture = ActivityLifecycleCallback { activity, stage ->
            if (activity is CodeEditActivity && stage == Stage.PRE_ON_CREATE) captured.compareAndSet(null, Intent(activity.intent))
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(capture) }
        try {
            driver.click("rss-fullscreen")
            driver.await("Actual code launch") { captured.get() != null }
        } finally { instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(capture) } }
        val launch = requireNotNull(captured.get())
        assertTrue(launch.getBooleanExtra("useTextFile", false)); assertEquals(4, launch.getIntExtra("cursorPosition", -1))
        val path = requireNotNull(launch.getStringExtra("textFile"))
        var child: CodeEditActivity? = null
        driver.await("Complete input on real native code surface") {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                val editor = resumed?.findViewById<CodeEditor>(R.id.editText)
                if (editor?.isShown == true && editor.isEditable && editor.text.toString() == "initial body") child = resumed
            }
            child != null
        }
        driver.awaitIdentifier("code-save")
        instrumentation.runOnMainSync { child!!.findViewById<CodeEditor>(R.id.editText).apply { setText("returned body"); setSelection(0, 7) } }
        driver.click("code-save")
        driver.await("Actual private editor result returns") {
            var returned = false
            robot.scenario!!.onActivity { returned = !it.viewModel.state.value.editorPending && it.viewModel.state.value.draft[RssSourceEditorField.StartJs].text == "returned body" }
            returned
        }
        robot.scenario!!.onActivity { assertEquals(7, it.viewModel.state.value.draft[RssSourceEditorField.StartJs].start); assertEquals(1, it.viewModel.state.value.tab) }
        assertFalse(File(path).exists())
        driver.awaitIdentifier("rss-field-startJs")
        driver.click("rss-close"); driver.awaitIdentifier("rss-discard-confirm")
        driver.click("rss-discard-cancel")
        driver.click("rss-close"); driver.click("rss-discard-confirm"); robot.finished()
    }

    @Test fun actualSaveCloseReportsResultOkAndPersistsChangedRules() {
        appDb.rssSourceDao.insert(fixture())
        robot.launch(key, forResult = true)
        robot.input(RssSourceEditorField.NextContentUrl, ".next@href")
        driver.click("rss-save"); robot.finished()
        assertEquals(Activity.RESULT_OK, robot.scenario!!.result.resultCode)
        assertEquals(".next@href", row(key)!!.nextContentUrl)
    }
}
