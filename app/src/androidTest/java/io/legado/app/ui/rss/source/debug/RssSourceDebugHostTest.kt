package io.legado.app.ui.rss.source.debug

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.model.Debug
import io.legado.app.ui.widget.dialog.TextDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RssSourceDebugHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun intent(key: String) = Intent(context, RssSourceDebugActivity::class.java).putExtra("key", key)
    private fun loaded(scenario: ActivityScenario<RssSourceDebugActivity>) { compose.waitUntil { var ready = false; scenario.onActivity { ready = it.viewModel.state.value.loaded }; ready } }
    @Test fun actualKeyIntentStartsOnlyOnSubmitAndTerminalLogsSurviveRecreationWithoutRerunningOrChangingSource() {
        val key = "rss-debug-host-${UUID.randomUUID()}"; val source = RssSource(key, "Fixture", enabled = false, customOrder = 11, lastUpdateTime = 25)
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        try {
            ActivityScenario.launch<RssSourceDebugActivity>(intent(key)).use { scenario ->
                loaded(scenario); scenario.onActivity { assertTrue(it.viewModel.state.value.help); assertFalse(it.viewModel.state.value.running); assertEquals("", it.viewModel.state.value.output) }
                compose.onNodeWithTag("rss-debug-example-my").performClick()
                compose.waitUntil { var done = false; scenario.onActivity { done = !it.viewModel.state.value.running && it.viewModel.state.value.output.contains("搜索URL为空") }; done }
                assertNull(Debug.callback); var output = ""; scenario.onActivity { output = it.viewModel.state.value.output }
                scenario.recreate(); loaded(scenario); scenario.onActivity { assertEquals(output, it.viewModel.state.value.output); assertFalse(it.viewModel.state.value.running); assertEquals("我的", it.viewModel.state.value.query) }
                assertNull(Debug.callback); closeSoftKeyboard(); pressBack()
            }
            runBlocking(Dispatchers.IO) { val row = appDb.rssSourceDao.getByKey(key)!!; assertEquals(11, row.customOrder); assertEquals(25L, row.lastUpdateTime); assertFalse(row.enabled) }
        } finally { runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(key) } }
    }
    @Test fun actualContentQueryCompletesAndHtmlMenuOpensExistingTextDialogContract() {
        val key = "rss-debug-host-${UUID.randomUUID()}"; runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(RssSource(key, "Fixture", enabled = false, ruleArticles = ".entry")) }
        try {
            ActivityScenario.launch<RssSourceDebugActivity>(intent(key)).use { scenario ->
                loaded(scenario); compose.onNodeWithTag("rss-debug-query").performTextReplacement("https://example.invalid/content")
                compose.onNodeWithTag("rss-debug-search").performClick()
                compose.waitUntil { var done = false; scenario.onActivity { done = !it.viewModel.state.value.running && it.viewModel.state.value.output.contains("内容规则为空") }; done }
                compose.onNodeWithTag("rss-debug-menu").performClick(); compose.onNodeWithTag("rss-debug-content-html").performClick()
                compose.waitUntil { var opened = false; scenario.onActivity { opened = it.supportFragmentManager.fragments.any { child -> child is TextDialog && child.isAdded } }; opened }
                compose.onNodeWithText("Html").assertExists(); assertNull(Debug.callback)
                scenario.onActivity { it.supportFragmentManager.fragments.filterIsInstance<TextDialog>().forEach { child -> child.dismissAllowingStateLoss() } }
                closeSoftKeyboard(); pressBack()
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(key) } }
    }
}
