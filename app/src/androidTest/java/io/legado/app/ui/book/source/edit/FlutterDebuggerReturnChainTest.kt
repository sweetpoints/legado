package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Crosses the production ActivityResult owners; no intercepted launch or synthetic result. */
class FlutterDebuggerReturnChainTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    private var priorBookHelp = 0
    private var priorRuleHelp = 0

    @Before fun before() {
        driver.enableAccessibility()
        priorBookHelp = LocalConfig.getInt("bookSourceHelpVersion", 0)
        priorRuleHelp = LocalConfig.getInt("ruleHelpVersion", 0)
        LocalConfig.edit().putInt("bookSourceHelpVersion", 1).putInt("ruleHelpVersion", 1).commit()
    }
    @After fun after() {
        driver.close()
        LocalConfig.edit().putInt("bookSourceHelpVersion", priorBookHelp)
            .putInt("ruleHelpVersion", priorRuleHelp).commit()
    }

    @Test
    fun flutterSourceEditorSavesBeforeDebugAndRealResultClearsMatchingPrivateRequest() {
        val source = fixture()
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
        try {
            ActivityScenario.launch<BookSourceEditActivity>(
                Intent(context, BookSourceEditActivity::class.java).putExtra("sourceUrl", source.bookSourceUrl)
            ).use { scenario ->
                driver.awaitIdentifier("source-field-0-bookSourceName")
                driver.setText("source-field-0-bookSourceName", "Saved before debugger")
                driver.await("source editor acknowledges Flutter edit") {
                    var accepted = false
                    scenario.onActivity {
                        accepted = it.editorModel.state.value.document?.form?.field(0, "bookSourceName")?.value == "Saved before debugger"
                    }
                    accepted
                }
                closeSoftKeyboard()
                driver.click("source-menu")
                driver.click("source-menu-DEBUG")
                driver.awaitIdentifier("source-debug-query")
                val debugger = requireNotNull(resumed<BookSourceDebugActivity>())
                instrumentation.runOnMainSync {
                    assertEquals(source.bookSourceUrl, debugger.intent.getStringExtra("key"))
                    assertNull(debugger.intent.getStringExtra("text"))
                    assertNull(debugger.intent.getStringExtra("textFile"))
                    assertEquals("Saved before debugger", debugger.viewModel.state.value.name)
                    assertFalse(debugger.viewModel.state.value.running)
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals("Saved before debugger", appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
                }
                scenario.onActivity {
                    val request = requireNotNull(it.editorModel.state.value.document?.nativeRequest)
                    assertEquals(BookSourceNativeAction.DEBUG, request.action)
                    assertTrue(request.delivered)
                    assertTrue(request.handedOff)
                }
                closeSoftKeyboard()
                driver.click("source-debug-close")
                driver.await("real debugger result acknowledged by original editor") {
                    var returned = false
                    scenario.onActivity {
                        val state = it.editorModel.state.value
                        returned = state.document?.nativeRequest == null && !state.busy && state.error == null
                    }
                    resumed<BookSourceEditActivity>() != null && returned
                }
                driver.awaitIdentifier("source-field-0-bookSourceName")
                assertEquals("Saved before debugger", driver.text("source-field-0-bookSourceName"))
                assertNull(Debug.callback)
                driver.click("source-close")
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source.bookSourceUrl) }
        }
    }

    @Test
    fun jsEditorDebugActionReturnsThroughRealDebuggerResultAndReopensActualFlutterCodeEditor() {
        val source = fixture()
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
        var child: Activity? = null
        try {
            ActivityScenario.launch<JsSourceEditActivity>(
                Intent(context, JsSourceEditActivity::class.java).putExtra("sourceUrl", source.bookSourceUrl)
            ).use { scenario ->
                driver.awaitIdentifier("code-debug")
                val firstEditor = requireNotNull(resumed<CodeEditActivity>())
                child = firstEditor
                driver.click("code-debug")
                driver.awaitIdentifier("source-debug-query")
                val debugger = requireNotNull(resumed<BookSourceDebugActivity>())
                child = debugger
                instrumentation.runOnMainSync {
                    assertEquals(source.bookSourceUrl, debugger.intent.getStringExtra("key"))
                    assertFalse(debugger.viewModel.state.value.running)
                    assertEquals(source.bookSourceName, debugger.viewModel.state.value.name)
                }
                closeSoftKeyboard()
                driver.click("source-debug-close")
                driver.await("JS DEBUG_OPEN return launches a fresh actual code editor") {
                    val editor = resumed<CodeEditActivity>()
                    if (editor != null && editor !== firstEditor) { child = editor; true } else false
                }
                driver.awaitIdentifier("code-debug")
                scenario.onActivity { assertFalse(it.isFinishing || it.isDestroyed) }
                assertNull(Debug.callback)
                runBlocking(Dispatchers.IO) {
                    assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.bookSourceName)
                }
                driver.click("code-close")
                driver.await("JS coordinator finishes after unchanged editor cancellation") {
                    resumed<CodeEditActivity>() == null && resumed<JsSourceEditActivity>() == null
                }
                child = null
            }
        } finally {
            instrumentation.runOnMainSync { child?.takeUnless { it.isDestroyed || it.isFinishing }?.finish() }
            runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source.bookSourceUrl) }
        }
    }

    private fun fixture() = BookSource(
        bookSourceUrl = "https://debug-return-${UUID.randomUUID()}.invalid/",
        bookSourceName = "Debugger return fixture", enabled = false,
    )
    private inline fun <reified T : Activity> resumed(): T? {
        var found: T? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<T>().firstOrNull()
        }
        return found
    }
}
