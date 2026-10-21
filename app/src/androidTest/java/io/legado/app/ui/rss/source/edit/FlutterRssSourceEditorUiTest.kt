package io.legado.app.ui.rss.source.edit

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import io.legado.app.ui.code.CodeEditorComposeViewModel
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RssSourceEditorDraft
import io.legado.app.data.repository.RssSourceEditorField
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.*
import org.junit.Assert.*

/** Normal Android entry, rendered Dart controls, real child Activities and durable Room rows. */
class FlutterRssSourceEditorUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val robot = RssEditorFlutterRobot()
    private val driver get() = robot.driver
    private val instrumentation get() = robot.instrumentation
    private val prefs get() = robot.context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedLocal = LocalConfig.all
    private val source = RssSource("https://rss-editor-${UUID.randomUUID()}.invalid/", "RSS fixture",
        customOrder = -873, lastUpdateTime = 50)
    private val replacement = source.sourceUrl + "renamed/"
    private val children = mutableListOf<Activity>()
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && (activity.callingActivity?.className == RssSourceEditActivity::class.java.name ||
                activity is RssSourceDebugActivity && activity.intent.getStringExtra("key") == source.sourceUrl))
            children.add(activity)
    }

    @Before fun prepare() {
        prefs.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putInt("ruleHelpVersion", 1).commit()
        appDb.rssSourceDao.insert(source)
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            children.reversed().filterNot { it.isFinishing || it.isDestroyed }.forEach { child ->
                if (child is CodeEditActivity)
                    ViewModelProvider(child)[CodeEditorComposeViewModel::class.java].discard()
                else child.finish()
            }
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        }
        robot.close()
        for (key in listOf(source.sourceUrl, replacement)) {
            RssSource(key).setVariable(null)
            appDb.rssSourceDao.delete(key); appDb.rssStarDao.delete(key); appDb.rssArticleDao.delete(key)
        }
        prefs.edit().clear().apply { savedPrefs.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
    }

    @Test fun normalFlutterEntryEditsAll33FieldsEightFlagsAndBothEnumsAndSavesRoom() {
        robot.launch(source.sourceUrl)
        driver.click("rss-options-toggle")
        robot.commitViewportFrame()
        // Wait for the first chip's real semantics before scrolling this new row.
        driver.awaitIdentifier("rss-option-enabled")
        driver.click("rss-option-enabled")
        listOf("singleUrl", "cookieJar", "preload").forEach {
            robot.reveal("rss-option-$it", "rss-options"); driver.click("rss-option-$it")
        }
        robot.reveal("rss-type", "rss-option-enums"); driver.click("rss-type"); driver.click("rss-type-2")
        robot.reveal("rss-article-style", "rss-option-enums"); driver.click("rss-article-style"); driver.click("rss-article-style-4")
        driver.click("rss-options-toggle")
        robot.selectTab(3)
        listOf("enableJs", "loadWithBaseUrl", "showWebLog", "cacheFirst").forEach {
            robot.reveal("rss-option-$it", "rss-web-options"); driver.click("rss-option-$it")
        }
        val values = RssSourceEditorField.entries.associateWith { field ->
            if (field == RssSourceEditorField.SourceUrl) source.sourceUrl else "fixture-${field.key}"
        }
        assertEquals(33, values.size)
        values.forEach { (field, value) -> robot.input(field, value) }
        driver.click("rss-save"); robot.finished()
        val saved = requireNotNull(appDb.rssSourceDao.getByKey(source.sourceUrl))
        val actual = RssSourceEditorDraft.from(saved)
        values.forEach { (field, value) -> assertEquals("Saved ${field.key}", value, actual[field].text) }
        assertFalse(actual.enabled); assertTrue(actual.singleUrl); assertFalse(actual.cookieJar)
        assertTrue(actual.preload); assertFalse(actual.enableJs); assertFalse(actual.loadWithBaseUrl)
        assertTrue(actual.showWebLog); assertTrue(actual.cacheFirst)
        assertEquals(2, actual.type); assertEquals(4, actual.articleStyle)
        assertEquals(source.customOrder, saved.customOrder)
        assertTrue(saved.lastUpdateTime > source.lastUpdateTime)
    }

    @Test fun changedUrlMovesRelatedOriginsAndPreservesNonFormMetadata() {
        appDb.rssStarDao.insert(RssStar(origin = source.sourceUrl, link = "star-fixture", title = "Keep star"))
        appDb.rssArticleDao.insert(RssArticle(origin = source.sourceUrl, link = "article-fixture", sort = "News", content = "Keep article"))
        robot.launch(source.sourceUrl)
        robot.input(RssSourceEditorField.SourceUrl, replacement)
        robot.input(RssSourceEditorField.SourceName, "Moved RSS source")
        driver.click("rss-save"); robot.finished()
        assertNull(appDb.rssSourceDao.getByKey(source.sourceUrl))
        val moved = requireNotNull(appDb.rssSourceDao.getByKey(replacement))
        assertEquals("Moved RSS source", moved.sourceName); assertEquals(source.customOrder, moved.customOrder)
        assertNull(appDb.rssStarDao.get(source.sourceUrl, "star-fixture"))
        assertEquals("Keep star", appDb.rssStarDao.get(replacement, "star-fixture")?.title)
        assertNull(appDb.rssArticleDao.get(source.sourceUrl, "article-fixture", "News"))
        assertEquals("Keep article", appDb.rssArticleDao.get(replacement, "article-fixture", "News")?.content)
    }

    @Test fun privateDraftTabSelectionAndDirtyBackSurviveRecreationWithoutWritingRoom() {
        robot.launch(source.sourceUrl)
        robot.input(RssSourceEditorField.SourceName, "Private RSS name")
        robot.input(RssSourceEditorField.StartJs, "retained-script()")
        robot.select(RssSourceEditorField.StartJs, 2, 7)
        robot.recreate()
        driver.awaitIdentifier("rss-field-startJs")
        assertEquals("retained-script()", driver.text("rss-field-startJs"))
        robot.scenario!!.onActivity {
            val state = it.viewModel.state.value
            assertEquals(1, state.tab); assertEquals(2, state.draft[RssSourceEditorField.StartJs].start)
            assertEquals(7, state.draft[RssSourceEditorField.StartJs].end)
        }
        robot.hideIme()
        driver.click("rss-menu")
        driver.awaitIdentifier("rss-menu-Debug")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        driver.await("Back closes only actual Flutter popup") {
            driver.node("rss-menu-Debug")?.let { it.recycle(); false } ?: true
        }
        assertNull(driver.node("rss-discard-confirm"))
        assertEquals(source.sourceName, appDb.rssSourceDao.getByKey(source.sourceUrl)?.sourceName)
        driver.click("rss-close")
        driver.click("rss-discard-cancel")
        robot.scenario!!.onActivity { assertFalse(it.viewModel.state.value.exit) }
        driver.click("rss-close")
        driver.click("rss-discard-confirm")
        robot.finished()
        assertEquals(source.sourceName, appDb.rssSourceDao.getByKey(source.sourceUrl)?.sourceName)
        assertNull(appDb.rssSourceDao.getByKey(source.sourceUrl)?.startJs)
    }

    @Test fun eachTabScrollOffsetSurvivesSwitchingAndActualActivityRecreation() {
        robot.launch(source.sourceUrl)
        robot.select(RssSourceEditorField.JsLib, 0, 0)
        driver.awaitIdentifier("rss-field-jsLib")
        assertNull("Top field must actually be offscreen before testing restored scroll", driver.node("rss-field-sourceName"))
        robot.selectTab(1)
        driver.awaitIdentifier("rss-field-startHtml")
        robot.selectTab(0)
        driver.awaitIdentifier("rss-field-jsLib")
        assertNull("Switching tabs must preserve the base tab scroll offset", driver.node("rss-field-sourceName"))
        robot.recreate()
        driver.awaitIdentifier("rss-field-jsLib")
        assertNull("Recreated Flutter view must restore the actual scroll offset", driver.node("rss-field-sourceName"))
        assertEquals(source.sourceName, appDb.rssSourceDao.getByKey(source.sourceUrl)?.sourceName)
    }

    @Test fun oversizedRuleOpensCompletePrivateFileAndCancelPreservesDraftAndRoom() {
        val original = "/* fixture */\n" + "a".repeat(30_000)
        source.startJs = original; appDb.rssSourceDao.insert(source)
        robot.launch(source.sourceUrl)
        val launched = codeLaunch { robot.field(RssSourceEditorField.StartJs); driver.click("rss-field-startJs") }
        val child = awaitCode(original)
        assertTrue(launched.getBooleanExtra("useTextFile", false))
        assertNull("Large rule must not be duplicated into a Binder text extra", launched.getStringExtra("text"))
        assertTrue(launched.getBooleanExtra("returnUnchangedText", false))
        val path = requireNotNull(launched.getStringExtra("textFile"))
        assertEquals(original, File(path).readText())
        driver.click("code-close")
        driver.awaitIdentifier("rss-field-startJs")
        robot.scenario!!.onActivity { assertEquals(original, it.viewModel.state.value.draft[RssSourceEditorField.StartJs].text) }
        assertEquals(original, appDb.rssSourceDao.getByKey(source.sourceUrl)?.startJs)
        driver.await("Canceled private handoff file is removed") { !File(path).exists() }
        assertTrue(child.isFinishing || child.isDestroyed)
    }

    @Test fun nativeFullscreenSaveReturnsDraftAndCursorButRoomWaitsForWholeSourceSave() {
        robot.launch(source.sourceUrl)
        robot.input(RssSourceEditorField.StartJs, "initial body")
        robot.select(RssSourceEditorField.StartJs, 4, 4)
        val launched = codeLaunch { driver.click("rss-fullscreen") }
        val child = awaitCode("initial body")
        assertEquals(4, launched.getIntExtra("cursorPosition", -1))
        instrumentation.runOnMainSync {
            child.findViewById<CodeEditor>(R.id.editText).apply { setText("returned body"); setSelection(0, 7) }
        }
        driver.click("code-save")
        driver.await("Actual RSS fullscreen result reaches private draft") {
            var returned = false
            robot.scenario!!.onActivity {
                val state = it.viewModel.state.value
                returned = !state.editorPending && state.draft[RssSourceEditorField.StartJs].text == "returned body" &&
                    state.draft[RssSourceEditorField.StartJs].start == 7
            }
            returned
        }
        assertNull(appDb.rssSourceDao.getByKey(source.sourceUrl)?.startJs)
        driver.awaitIdentifier("rss-field-startJs")
        assertEquals("returned body", driver.text("rss-field-startJs"))
        assertFalse(File(requireNotNull(launched.getStringExtra("textFile"))).exists())
        driver.click("rss-save"); robot.finished()
        assertEquals("returned body", appDb.rssSourceDao.getByKey(source.sourceUrl)?.startJs)
    }

    @Test fun debugPersistsBeforeRealLaunchAndReturnDoesNotDuplicateSaveOrLaunch() {
        robot.launch(source.sourceUrl)
        robot.input(RssSourceEditorField.SourceName, "Saved before RSS debug")
        robot.menu("Debug")
        driver.awaitIdentifier("source-debug-query")
        val debugger = awaitResumed<RssSourceDebugActivity>()
        instrumentation.runOnMainSync { assertEquals(source.sourceUrl, debugger.intent.getStringExtra("key")) }
        driver.await("Actual RSS debugger loaded the saved source") {
            var loaded = false
            instrumentation.runOnMainSync { loaded = debugger.viewModel.state.value.name == "Saved before RSS debug" }
            loaded
        }
        assertEquals("Saved before RSS debug", appDb.rssSourceDao.getByKey(source.sourceUrl)?.sourceName)
        val savedTime = appDb.rssSourceDao.getByKey(source.sourceUrl)!!.lastUpdateTime
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        driver.click("source-debug-close")
        driver.awaitIdentifier("rss-field-sourceName")
        robot.recreate()
        driver.awaitIdentifier("rss-field-sourceName")
        assertEquals("Saved before RSS debug", driver.text("rss-field-sourceName"))
        assertEquals(savedTime, appDb.rssSourceDao.getByKey(source.sourceUrl)?.lastUpdateTime)
        assertNull(resumed<RssSourceDebugActivity>())
    }

    @Test fun savedLoginAndVariableUseCurrentKeyAndNoLoginDoesNotLaunch() {
        robot.launch(source.sourceUrl)
        var launches = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className?.endsWith("SourceLoginActivity") == true) {
                    assertEquals(replacement, intent.getStringExtra("key")); assertEquals("rssSource", intent.getStringExtra("type"))
                    assertEquals("https://login.invalid/", appDb.rssSourceDao.getByKey(replacement)?.loginUrl)
                    launches++
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            robot.menu("Login")
            driver.await("No-login issue reaches native state without launching") {
                var ready = false
                robot.scenario!!.onActivity { ready = it.viewModel.state.value.issue == RssSourceEditorIssue.NoLogin }
                ready
            }
            assertEquals(0, launches)
            robot.input(RssSourceEditorField.SourceUrl, replacement)
            robot.input(RssSourceEditorField.LoginUrl, "https://login.invalid/")
            robot.menu("Login")
            driver.await("Saved login action delivered once") { launches == 1 }
            assertNull(appDb.rssSourceDao.getByKey(source.sourceUrl))
            robot.menu("Variable")
            driver.await("Actual native VariableDialog owns renamed RSS key") {
                var ready = false
                robot.scenario!!.onActivity { host ->
                    val dialog = host.supportFragmentManager.fragments.filterIsInstance<VariableDialog>().singleOrNull()
                    ready = dialog?.isResumed == true && dialog.arguments?.getString("key") == replacement
                }
                ready
            }
            compose.onNodeWithTag("variable-input").performTextReplacement("rss fixture variable")
            compose.onNodeWithTag("variable-save").performClick()
            driver.await("Variable saves through actual dialog callback for new key") {
                appDb.rssSourceDao.getByKey(replacement)?.getVariable() == "rss fixture variable"
            }
            robot.recreate()
            assertEquals(1, launches)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun codeLaunch(action: () -> Unit): Intent {
        val captured = AtomicReference<Intent>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is CodeEditActivity && stage == Stage.PRE_ON_CREATE &&
                activity.callingActivity?.className == RssSourceEditActivity::class.java.name)
                captured.compareAndSet(null, Intent(activity.intent))
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        try {
            action(); driver.await("Actual fullscreen launch captured before private input cleanup") { captured.get() != null }
            return requireNotNull(captured.get())
        } finally { instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) } }
    }
    private fun awaitCode(text: String): CodeEditActivity {
        var ready: CodeEditActivity? = null
        driver.await("Actual editable native code surface has complete text") {
            instrumentation.runOnMainSync {
                // Already on main: read the lifecycle registry directly, not resumed(),
                // which dispatches its own runOnMainSync for off-main callers.
                val child = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>()
                    .firstOrNull { it in children }
                val editor = child?.findViewById<CodeEditor>(R.id.editText)
                if (editor?.isShown == true && editor.isEditable && editor.text.toString() == text) ready = child
            }
            ready != null
        }
        driver.awaitIdentifier("code-search-toggle")
        return requireNotNull(ready)
    }
    private inline fun <reified T : Activity> resumed(): T? {
        var found: T? = null
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull() }
        return found
    }
    private inline fun <reified T : Activity> awaitResumed(): T {
        var found: T? = null
        driver.await("${T::class.java.simpleName} resumed") { found = resumed(); found != null }
        return requireNotNull(found)
    }
    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
            is Long -> putLong(key, value); is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet()); null -> remove(key)
            else -> error("Unsupported preference fixture: $key")
        }
    }
}
