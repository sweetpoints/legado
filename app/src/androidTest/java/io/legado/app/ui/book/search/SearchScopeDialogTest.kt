package io.legado.app.ui.book.search

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SearchScopeDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realDisabledSourceSelectionAndFilterRestoreCallParentBeforeDismiss() {
        val source = BookSource(bookSourceUrl = "https://scope-${UUID.randomUUID()}.invalid", bookSourceName = "Test:Scope", enabled = false)
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val parent = Host(); it.supportFragmentManager.beginTransaction().add(parent, "scope-host").commitNow()
                    SearchScopeDialog().show(parent.childFragmentManager, "scope")
                }
                compose.onNodeWithTag("search-scope-tab-Sources").performClick()
                compose.onNodeWithTag("search-scope-filter").performClick()
                compose.onNodeWithTag("search-scope-query").performTextReplacement(source.bookSourceUrl)
                compose.waitUntil { compose.onAllNodesWithTag("search-scope-source-${source.bookSourceUrl}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("search-scope-source-${source.bookSourceUrl}").performClick()
                scenario.recreate()
                compose.onNodeWithTag("search-scope-tab-Sources").assertIsSelected()
                compose.onNodeWithTag("search-scope-query").assertTextEquals(source.bookSourceUrl)
                compose.waitUntil { compose.onAllNodesWithTag("search-scope-source-${source.bookSourceUrl}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("search-scope-source-${source.bookSourceUrl}").assertIsSelected()
                compose.onNodeWithTag("search-scope-confirm").performClick()
                compose.waitUntil { var done = false; scenario.onActivity { done = (it.supportFragmentManager.findFragmentByTag("scope-host") as Host).delivered != null }; done }
                scenario.onActivity {
                    val parent = it.supportFragmentManager.findFragmentByTag("scope-host") as Host
                    assertEquals("TestScope::${source.bookSourceUrl}", parent.delivered); assertTrue(parent.presentDuringCallback)
                    assertEquals(1, parent.calls); parent.childFragmentManager.executePendingTransactions()
                    assertNull(parent.childFragmentManager.findFragmentByTag("scope"))
                }
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source) } }
    }
    @Test fun cancelDismissesWithoutCallbackAndAllSourcesCallsParentWithEmptyScope() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                val parent = Host(); it.supportFragmentManager.beginTransaction().add(parent, "scope-host").commitNow()
                SearchScopeDialog().show(parent.childFragmentManager, "scope")
            }
            compose.onNodeWithTag("search-scope-cancel").performClick()
            scenario.onActivity {
                val parent = it.supportFragmentManager.findFragmentByTag("scope-host") as Host
                parent.childFragmentManager.executePendingTransactions(); assertEquals(0, parent.calls)
                assertNull(parent.childFragmentManager.findFragmentByTag("scope"))
                SearchScopeDialog().show(parent.childFragmentManager, "scope")
            }
            compose.onNodeWithTag("search-scope-all").performClick()
            compose.waitUntil { var done = false; scenario.onActivity { done = (it.supportFragmentManager.findFragmentByTag("scope-host") as Host).calls == 1 }; done }
            scenario.onActivity { assertEquals("", (it.supportFragmentManager.findFragmentByTag("scope-host") as Host).delivered) }
        }
    }
    class Host : Fragment(), SearchScopeDialog.Callback {
        var calls = 0; var delivered: String? = null; var presentDuringCallback = false
        override fun onSearchScopeOk(searchScope: SearchScope) {
            presentDuringCallback = childFragmentManager.findFragmentByTag("scope")?.isAdded == true
            calls++; delivered = searchScope.toString()
        }
    }
}
