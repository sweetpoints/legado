package io.legado.app.ui.rss.source.edit

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RssSourceEditorField
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RssSourceEditorHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private var priorHelp = 0

    @Before
    fun before() {
        priorHelp = LocalConfig.getInt("ruleHelpVersion", 0)
        LocalConfig.edit().putInt("ruleHelpVersion", 1).commit()
    }

    @After
    fun after() {
        LocalConfig.edit().putInt("ruleHelpVersion", priorHelp).commit()
    }

    private fun row(key: String) = runBlocking(Dispatchers.IO) { appDb.rssSourceDao.getByKey(key) }

    private fun fixture(key: String) =
        RssSource(key, "Name", enabled = false, customOrder = 17, lastUpdateTime = 50)

    private fun intent(key: String) =
        Intent(context, RssSourceEditActivity::class.java).putExtra("sourceUrl", key)

    private fun waitLoaded(scenario: ActivityScenario<RssSourceEditActivity>) {
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.viewModel.state.value.loaded }
            loaded
        }
    }

    private fun navigate(field: RssSourceEditorField) {
        compose.onNodeWithTag("rss-editor-navigation").performClick()
        compose.onNodeWithTag("rss-editor-navigate-" + field.name).performScrollTo().performClick()
        compose.waitUntil {
            compose.onAllNodesWithTag("rss-editor-" + field.name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun realPasteCopyAndRenameSaveAreAwaitedBeforeDebugLoginLaunchAndRelatedOriginsMoveOnce() {
        val original = "rss-editor-${UUID.randomUUID()}"
        val renamed = "$original/new"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val prior = clipboard.primaryClip
        val intents = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    val destination = intent.component?.className.orEmpty()
                    if (
                        destination.endsWith("RssSourceDebugActivity") ||
                            destination.endsWith("SourceLoginActivity")
                    ) {
                        assertEquals("Pasted", row(renamed)!!.sourceName)
                        assertNull(row(original))
                        intents += Intent(intent)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        runBlocking(Dispatchers.IO) {
            appDb.rssSourceDao.insert(fixture(original))
            appDb.rssStarDao.insert(RssStar(origin = original, link = "fixture"))
        }
        try {
            ActivityScenario.launch<RssSourceEditActivity>(intent(original)).use { scenario ->
                waitLoaded(scenario)
                instrumentation.addMonitor(monitor)
                val pasted =
                    RssSource(
                        renamed,
                        "Pasted",
                        enabled = false,
                        loginUrl = "https://login.invalid",
                        loginUi = "ui",
                        loginCheckJs = "check",
                        header = "header",
                        jsLib = "lib",
                        contentWhitelist = "allow",
                        contentBlacklist = "deny",
                        shouldOverrideUrlLoading = "@js:url",
                        startHtml = "html",
                        ruleImage = "img",
                        nextContentUrl = ".next",
                        customOrder = 999,
                        lastUpdateTime = 999,
                    )
                instrumentation.runOnMainSync {
                    clipboard.setPrimaryClip(ClipData.newPlainText("source", GSON.toJson(pasted)))
                }
                compose.onNodeWithTag("rss-editor-menu").performClick()
                compose
                    .onNodeWithText(context.getString(R.string.paste_source))
                    .performScrollTo()
                    .performClick()
                compose.waitUntil {
                    var ready = false
                    scenario.onActivity {
                        ready =
                            it.viewModel.state.value.draft[RssSourceEditorField.SourceName].text ==
                                "Pasted"
                    }
                    ready
                }
                assertEquals("Name", row(original)!!.sourceName)
                assertNull(row(renamed))
                compose.onNodeWithTag("rss-editor-menu").performClick()
                compose
                    .onNodeWithText(context.getString(R.string.copy_source))
                    .performScrollTo()
                    .performClick()
                compose.waitUntil {
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("Pasted") ==
                        true
                }
                val copied =
                    GSON.fromJsonObject<RssSource>(
                            clipboard.primaryClip!!.getItemAt(0).text.toString()
                        )
                        .getOrThrow()
                assertEquals(renamed, copied.sourceUrl)
                assertEquals(17, copied.customOrder)
                assertEquals("allow", copied.contentWhitelist)
                assertEquals("lib", copied.jsLib)
                compose.onNodeWithTag("rss-editor-menu").performClick()
                compose.onNodeWithText(context.getString(R.string.debug_source)).performClick()
                compose.waitUntil { intents.size == 1 }
                assertEquals(renamed, intents.single().getStringExtra("key"))
                assertEquals(17, row(renamed)!!.customOrder)
                runBlocking(Dispatchers.IO) {
                    assertNotNull(appDb.rssStarDao.get(renamed, "fixture"))
                    assertNull(appDb.rssStarDao.get(original, "fixture"))
                }
                compose.onNodeWithTag("rss-editor-menu").performClick()
                compose.onNodeWithText(context.getString(R.string.login)).performClick()
                compose.waitUntil { intents.size == 2 }
                assertEquals("rssSource", intents.last().getStringExtra("type"))
                assertEquals(renamed, intents.last().getStringExtra("key"))
                scenario.recreate()
                waitLoaded(scenario)
                assertEquals(2, intents.size)
                scenario.onActivity { assertEquals(renamed, it.viewModel.sourceUrl) }
                compose.onNodeWithTag("rss-editor-back").performClick()
                compose
                    .onNodeWithText(context.getString(R.string.exit_no_save))
                    .assertDoesNotExist()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                clipboard.setPrimaryClip(prior ?: ClipData.newPlainText("", ""))
            }
            runBlocking(Dispatchers.IO) {
                appDb.rssSourceDao.delete(original)
                appDb.rssSourceDao.delete(renamed)
                appDb.rssStarDao.delete(original)
                appDb.rssStarDao.delete(renamed)
            }
        }
    }

    @Test
    fun realCodeFileEditorCursorAndBackUnsavedConfirmationSurviveActivityRecreation() {
        ActivityScenario.launch(RssSourceEditActivity::class.java).use { scenario ->
            waitLoaded(scenario)
            compose.onNodeWithTag("rss-editor-tab-1").performClick()
            navigate(RssSourceEditorField.StartJs)
            compose.onNodeWithTag("rss-editor-StartJs").performTextReplacement("initial body")
            compose
                .onNodeWithTag("rss-editor-StartJs")
                .performTextInputSelection(androidx.compose.ui.text.TextRange(4))
            scenario.recreate()
            waitLoaded(scenario)
            val launch = recordCodeLaunch {
                compose.onNodeWithTag("rss-editor-fullscreen").performClick()
            }
            var child: CodeEditActivity? = null
            compose.waitUntil(10000) {
                instrumentation.runOnMainSync {
                    child =
                        ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<CodeEditActivity>()
                            .firstOrNull()
                }
                child != null
            }
            val editor = requireNotNull(child)
            assertTrue(launch.getBooleanExtra("useTextFile", false))
            assertEquals(4, launch.getIntExtra("cursorPosition", -1))
            val path = launch.getStringExtra("textFile")!!
            compose.waitUntil(10000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val native =
                        editor.findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText)
                    ready = native?.text?.toString() == "initial body" && native?.isEditable == true
                }
                ready
            }
            instrumentation.runOnMainSync {
                editor
                    .findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText)
                    .setText("returned body")
                editor
                    .findViewById<io.github.rosemoe.sora.widget.CodeEditor>(R.id.editText)
                    .setSelection(0, 7)
            }
            closeSoftKeyboard()
            compose.onNodeWithTag("code-save").performClick()
            compose.waitUntil(10000) {
                var returned = false
                scenario.onActivity {
                    returned =
                        !it.viewModel.state.value.editorPending &&
                            it.viewModel.state.value.draft[RssSourceEditorField.StartJs].text ==
                                "returned body"
                }
                returned
            }
            scenario.onActivity {
                assertEquals(7, it.viewModel.state.value.draft[RssSourceEditorField.StartJs].start)
                assertEquals(1, it.viewModel.state.value.tab)
            }
            assertFalse(java.io.File(path).exists())
            closeSoftKeyboard()
            pressBack()
            compose.onNodeWithText(context.getString(R.string.exit_no_save)).assertExists()
            compose.onNodeWithTag("rss-editor-keep").performClick()
            compose.onNodeWithTag("rss-editor-back").performClick()
            compose.onNodeWithTag("rss-editor-discard").performClick()
        }
    }

    @Test
    fun actualSaveCloseReportsResultOkAndPersistsChangedRules() {
        val key = "rss-editor-${UUID.randomUUID()}"
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(fixture(key)) }
        try {
            ActivityScenario.launchActivityForResult<RssSourceEditActivity>(intent(key)).use {
                scenario ->
                waitLoaded(scenario)
                compose.onNodeWithTag("rss-editor-tab-3").performClick()
                navigate(RssSourceEditorField.NextContentUrl)
                compose
                    .onNodeWithTag("rss-editor-NextContentUrl")
                    .performTextReplacement(".next@href")
                closeSoftKeyboard()
                compose.onNodeWithTag("rss-editor-save").performClick()
                compose.waitUntil { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                assertEquals(".next@href", row(key)!!.nextContentUrl)
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(key) }
        }
    }

    private fun recordCodeLaunch(launch: () -> Unit): Intent {
        val captured = AtomicReference<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className == CodeEditActivity::class.java.name) {
                        // The child clears its carrier after accepting the private draft. Assert
                        // the actual launcher contract before that cleanup, never stale extras.
                        captured.set(Intent(intent))
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        return try {
            launch()
            compose.waitUntil(10_000) { captured.get() != null }
            requireNotNull(captured.get())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
