package io.legado.app.ui.book.source.debug

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.model.Debug
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookSourceDebugHostTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun loaded(scenario:ActivityScenario<BookSourceDebugActivity>) { compose.waitUntil(10_000) { var ready=false;scenario.onActivity{ready=it.viewModel.state.value.loaded};ready } }
    @Test fun actualKeyLoadsCustomKeywordAndOnlyExplicitSubmitExecutesAndRestoresLogWithoutChangingRoomSource() {
        val key="book-debug-host-${UUID.randomUUID()}";val source=BookSource(bookSourceUrl=key,bookSourceName="Fixture",enabled=false,customOrder=11,lastUpdateTime=25,ruleSearch=SearchRule(checkKeyWord="Custom fixture keyword"))
        runBlocking(Dispatchers.IO){appDb.bookSourceDao.insert(source)}
        val directory=File(context.filesDir,"book-source-debug");val beforeFiles=directory.listFiles().orEmpty().map { it.name }.toSet()
        var ownedFiles=emptyList<File>()
        try { ActivityScenario.launch<BookSourceDebugActivity>(Intent(context,BookSourceDebugActivity::class.java).putExtra("key",key)).use { scenario ->
            loaded(scenario);scenario.onActivity{assertTrue(it.viewModel.state.value.help);assertFalse(it.viewModel.state.value.running);assertEquals("",it.viewModel.state.value.output)}
            compose.onNodeWithText("Custom fixture keyword").assertExists();compose.onNodeWithTag("book-debug-example-my").performClick()
            compose.waitUntil(10_000){var done=false;scenario.onActivity{done= !it.viewModel.state.value.running && it.viewModel.state.value.output.isNotBlank()};done};assertNull(Debug.callback)
            compose.waitUntil(10_000) { directory.listFiles().orEmpty().any { it.name.endsWith(".json") && it.name !in beforeFiles } };ownedFiles=directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in beforeFiles }
            var output="";scenario.onActivity{output=it.viewModel.state.value.output};scenario.recreate();loaded(scenario);scenario.onActivity{assertEquals(output,it.viewModel.state.value.output);assertFalse(it.viewModel.state.value.running);assertEquals("Custom fixture keyword",it.viewModel.state.value.query)};assertNull(Debug.callback)
            assertTrue(ownedFiles.all { it.exists() }) // Configuration recreation retains private draft.
            BookSourceDebugStage.entries.forEach { stage ->
                compose.onNodeWithTag("book-debug-menu").performClick();compose.onNodeWithTag("book-debug-html-${stage.name}").performClick()
                compose.waitUntil{var opened=false;scenario.onActivity{opened=it.supportFragmentManager.fragments.any{child->child is TextDialog && child.isAdded}};opened};compose.onNodeWithText("html").assertExists()
                scenario.onActivity{it.supportFragmentManager.fragments.filterIsInstance<TextDialog>().forEach{child->child.dismissAllowingStateLoss()}};compose.waitForIdle()
            }
            closeSoftKeyboard();pressBack()
            compose.waitUntil(10_000) { ownedFiles.all { !it.exists() && File(it.parentFile,it.name.removeSuffix(".json")+".closed").exists() } }
        };runBlocking(Dispatchers.IO){assertEquals(GSON.toJson(source),GSON.toJson(appDb.bookSourceDao.getBookSource(key)))} }
        finally {ownedFiles.forEach { body -> listOf(".json",".json.bak",".json.new",".closed").forEach { suffix -> File(body.parentFile,body.name.removeSuffix(".json")+suffix).delete() } };runBlocking(Dispatchers.IO){appDb.bookSourceDao.delete(key)}}
    }
}
