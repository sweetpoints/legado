package io.legado.app.ui.book.searchContent

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ContentSearchHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun intent(book: Book) = Intent(context, SearchContentActivity::class.java).putExtra("bookUrl", book.bookUrl)
        .putExtra("searchWord", "needle").putExtra("searchResultIndex", 0)
    @Test fun nativeRotationRetainsDraftAndResultMetadataThenReaderAbiReturnsWholeListAndCleansOwnedFiles() = runBlocking {
        val book = Book(bookUrl = "search-native-${UUID.randomUUID()}", name = "Native content search", author = "Author", durChapterIndex = 7)
        val original = SearchResult(resultCount = 99, resultCountWithinChapter = 2, resultText = "Some needle snippet",
            chapterTitle = "Chapter", query = "needle", pageSize = 12, chapterIndex = 7, pageIndex = 3, queryIndexInResult = 5, queryIndexInChapter = 123)
        val second = original.copy(chapterIndex = 8, resultText = "Another needle snippet", isRegex = true)
        var session: String? = null
        withContext(Dispatchers.IO) { appDb.bookDao.insert(book) }; IntentData.put("searchResultList", listOf(original, second))
        try { ActivityScenario.launchActivityForResult<SearchContentActivity>(intent(book)).use { scenario ->
            compose.waitUntil(5000) { compose.onAllNodesWithTag("content-search-result-incoming-0").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { session = ViewModelProvider(it)[ContentSearchViewModel::class.java].session }
            compose.onNodeWithTag("content-search-query").performTextReplacement("changed draft")
            compose.waitForIdle(); scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("content-search-result-incoming-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("content-search-query").assertTextContains("changed draft")
            compose.onNodeWithTag("content-search-count").assertTextContains(": 2")
            compose.onNodeWithTag("content-search-result-incoming-0").performClick()
            val result = scenario.result; assertEquals(Activity.RESULT_OK, result.resultCode)
            val key = result.resultData!!.getLongExtra("key", 0); assertTrue(key > 0)
            assertEquals(0, result.resultData!!.getIntExtra("index", -1)); assertEquals(original, IntentData.get<SearchResult>("searchResult$key"))
            assertEquals(listOf(original, second), IntentData.get<List<SearchResult>>("searchResultList$key"))
            withTimeout(5000) { while (withContext(Dispatchers.IO) { File(context.filesDir, "content-search-sessions/$session.json").exists() }) delay(20) }
            assertTrue(withContext(Dispatchers.IO) { File(context.filesDir, "content-search-sessions/$session.closed").exists() })
        } } finally { withContext(Dispatchers.IO) { appDb.bookDao.delete(book); cleanup(session) } }
    }
    @Test fun nativeBackCancelsWithoutReturningReaderResultAndRemovesItsLargeSession() = runBlocking {
        val book = Book(bookUrl = "search-back-${UUID.randomUUID()}", name = "Back content search", author = "Author")
        var session: String? = null
        withContext(Dispatchers.IO) { appDb.bookDao.insert(book) }
        IntentData.put("searchResultList", listOf(SearchResult(query = "needle".repeat(200000), resultText = "small display text")))
        try { ActivityScenario.launchActivityForResult<SearchContentActivity>(intent(book)).use { scenario ->
            compose.waitUntil(5000) { compose.onAllNodesWithTag("content-search-result-incoming-0").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { session = ViewModelProvider(it)[ContentSearchViewModel::class.java].session }
            compose.onNodeWithTag("content-search-back").performClick(); assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            withTimeout(5000) { while (withContext(Dispatchers.IO) { File(context.filesDir, "content-search-sessions/$session.json").exists() }) delay(20) }
            assertTrue(withContext(Dispatchers.IO) { File(context.filesDir, "content-search-sessions/$session.closed").exists() })
        } } finally { withContext(Dispatchers.IO) { appDb.bookDao.delete(book); cleanup(session) } }
    }
    private fun cleanup(session: String?) { session?.let { key -> listOf("json", "json.bak", "closed", "closed.bak").forEach { File(context.filesDir, "content-search-sessions/$key.$it").delete() } } }
}
