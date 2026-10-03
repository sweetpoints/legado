package io.legado.app.ui.book.changesource

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class BookSourceDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun publicConstructorShowsCachedComposeRowsAndSearchDraftSurvivesNativeActivityRotation() = runBlocking {
        val token = UUID.randomUUID().toString(); val name = "Compose native book $token"
        val row = SearchBook(bookUrl = "book-$token", origin = "source-$token", originName = "Compose native source", name = name, author = "Author", latestChapterTitle = "Latest")
        val source = BookSource(bookSourceUrl = row.origin, bookSourceName = row.originName)
        val oldGroup = withContext(Dispatchers.IO) { AppConfig.searchGroup }
        val original = withContext(Dispatchers.IO) {
            val snapshot = Triple(AppConfig.changeSourceLoadInfo, AppConfig.changeSourceLoadToc, AppConfig.changeSourceLoadWordCount)
            AppConfig.changeSourceLoadInfo = false; AppConfig.changeSourceLoadToc = false; AppConfig.changeSourceLoadWordCount = false
            AppConfig.searchGroup = ""; appDb.bookSourceDao.insert(source); appDb.searchBookDao.insert(row); snapshot
        }
        try { ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ChangeBookSourceDialog(name, "Author").show(it.supportFragmentManager, "book-source-native") }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("book-source-row-${row.bookUrl}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("book-source-row-${row.bookUrl}").assertIsDisplayed()
            compose.onNodeWithTag("book-source-search-open").performClick()
            compose.onNodeWithTag("book-source-query").performTextReplacement("Compose native")
            compose.waitForIdle(); scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("book-source-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("book-source-query").assertTextContains("Compose native")
            compose.onNodeWithTag("book-source-row-${row.bookUrl}").assertExists()
            compose.onNodeWithTag("book-source-close").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("book-source-screen").fetchSemanticsNodes().isEmpty() }
            assertNotNull(withContext(Dispatchers.IO) { appDb.searchBookDao.changeSourceSearch(name, "Author", "", "").firstOrNull() })
        } } finally { withContext(Dispatchers.IO) {
            appDb.searchBookDao.delete(row); appDb.bookSourceDao.delete(source); AppConfig.searchGroup = oldGroup
            AppConfig.changeSourceLoadInfo = original.first; AppConfig.changeSourceLoadToc = original.second; AppConfig.changeSourceLoadWordCount = original.third
        } }
    }
}
