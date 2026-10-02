package io.legado.app.ui.book.manage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SourcePickerDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realDialogReadsFullSourceCallsParentBeforeDismissAndKeepsSearchAfterRecreation() {
        val source = BookSource(bookSourceUrl = "https://picker-${UUID.randomUUID()}.invalid", bookSourceName = "Unique picker source", bookSourceGroup = "Picker group", jsLib = "library", mainJs = "script", coverDecodeJs = "cover", header = "headers")
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val parent = Host(); it.supportFragmentManager.beginTransaction().add(parent, "picker-host").commitNow()
                    SourcePickerDialog().show(parent.childFragmentManager, "picker")
                }
                compose.onNodeWithTag("source-picker-search").performTextReplacement(source.bookSourceUrl)
                compose.waitUntil { compose.onAllNodesWithTag("source-picker-row:${source.bookSourceUrl}").fetchSemanticsNodes().isNotEmpty() }
                scenario.recreate()
                compose.onNodeWithTag("source-picker-search").assertTextEquals(source.bookSourceUrl)
                compose.waitUntil { compose.onAllNodesWithTag("source-picker-row:${source.bookSourceUrl}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("source-picker-row:${source.bookSourceUrl}").performClick()
                compose.waitUntil { var delivered = false; scenario.onActivity { delivered = (it.supportFragmentManager.findFragmentByTag("picker-host") as Host).delivered != null }; delivered }
                scenario.onActivity {
                    val parent = it.supportFragmentManager.findFragmentByTag("picker-host") as Host
                    assertEquals(GSON.toJson(source), GSON.toJson(parent.delivered)); assertTrue(parent.presentDuringCallback)
                    parent.childFragmentManager.executePendingTransactions(); assertNull(parent.childFragmentManager.findFragmentByTag("picker"))
                }
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source) } }
    }
    @Test fun actualDelayDraftRestoresAndCancelLeavesPreferenceUntouched() {
        val previous = AppConfig.batchChangeSourceDelay
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { SourcePickerDialog().show(it.supportFragmentManager, "picker") }
                compose.onNodeWithTag("source-picker-menu").performClick(); compose.onNodeWithTag("source-picker-delay-menu").performClick()
                compose.waitUntil { compose.onAllNodesWithTag("source-picker-delay").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("source-picker-delay").performTextReplacement("7654")
                scenario.recreate(); compose.onNodeWithTag("source-picker-delay").assertTextEquals("7654")
                compose.onNodeWithTag("source-picker-delay-cancel").performClick(); assertEquals(previous, AppConfig.batchChangeSourceDelay)
            }
        } finally { AppConfig.batchChangeSourceDelay = previous }
    }
    class Host : Fragment(), SourcePickerDialog.Callback {
        var delivered: BookSource? = null; var presentDuringCallback = false
        override fun sourceOnClick(source: BookSource) {
            presentDuringCallback = childFragmentManager.findFragmentByTag("picker")?.isAdded == true
            delivered = source
        }
    }
}
