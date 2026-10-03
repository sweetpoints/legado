package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class RssArticlesPageRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRssArticlesPageRepository
    private val params = RssArticlesParameters("source", "Category", "configured", "Exact query")
    private val source = RssSource(sourceUrl = "source", sourceName = "Name", ruleNextPage = "next-rule", ruleArticles = "list-rule", header = "Header")
    private val article = RssArticle(origin = "source", sort = "Category", link = "one", title = "One", pubDate = "Date", description = "Description",
        content = "Body", image = "Image", variable = "{\"key\":\"value\"}", type = 2, durPos = 77)
    private var parserInput: List<Any?>? = null
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repository = AppRssArticlesPageRepository(database) { name, url, value, page, key ->
            parserInput = listOf(name, url, value.header, value.ruleArticles, page, key)
            mutableListOf(article.copy()) to "next-url"
        }
        runBlocking(Dispatchers.IO) { database.rssSourceDao.insert(source) }
    }
    @After fun cleanup() { database.close() }
    @Test fun fetchPassesExactLegacyParserInputsAndKeepsAllReturnedMetadata() = runBlocking {
        val value = repository.fetch(params, "requested-next-url", 4)
        assertEquals(listOf("Category", "requested-next-url", "Header", "list-rule", 4, "Exact query"), parserInput)
        val full = value.articles.single(); assertEquals("Body", full.content); assertEquals("Description", full.description)
        assertEquals(article.variable, full.variable); assertEquals(77, full.durPos); assertEquals(2, full.type)
        assertEquals("next-url", value.nextUrl); assertTrue(value.clearOld)
    }
    @Test fun refreshOrdersNewRowsAndPrunesOnlyOldCurrentCategoryWhenRuleExists() = runBlocking {
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(article.copy(link = "stale", order = 10), article.copy(link = "other", sort = "Other", order = 10)) }
        val batch = RssArticlesBatch(listOf(article.copy(), article.copy(link = "two", title = "Two")), "next", true)
        val commit = repository.refresh(params, batch, 100)
        assertEquals(98L, commit.order); assertTrue(commit.added)
        withContext(Dispatchers.IO) {
            assertNull(database.rssArticleDao.get("source", "stale", "Category")); assertNotNull(database.rssArticleDao.get("source", "other", "Other"))
            assertEquals(100L, database.rssArticleDao.get("source", "one", "Category")!!.order)
            assertEquals(99L, database.rssArticleDao.get("source", "two", "Category")!!.order)
        }
        assertEquals(0L, batch.articles.first().order)
    }
    @Test fun refreshWithoutNextRuleKeepsExistingCacheAndEmptyBatchPreservesOriginalClearPolicy() = runBlocking {
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(article.copy(link = "stale", order = 10)) }
        repository.refresh(params, RssArticlesBatch(listOf(article), null, false), 100)
        withContext(Dispatchers.IO) { assertNotNull(database.rssArticleDao.get("source", "stale", "Category")) }
        val result = repository.refresh(params, RssArticlesBatch(emptyList(), null, true), 200)
        assertFalse(result.added); assertEquals(200L, result.order)
        assertTrue(database.rssArticleDao.flowByOriginSort("source", "Category").first().isEmpty())
    }
    @Test fun appendKeepsExistingRowsAndUsesOriginalFirstLastDuplicateGuard() = runBlocking {
        repository.refresh(params, RssArticlesBatch(listOf(article), "next", false), 100)
        val batch = RssArticlesBatch(listOf(article.copy(title = "Overwrite denied"), article.copy(link = "two", title = "Two")), "next", false)
        val added = repository.append(params, batch, 99); assertTrue(added.added); assertEquals(97L, added.order)
        withContext(Dispatchers.IO) { assertEquals("One", database.rssArticleDao.get("source", "one", "Category")!!.title) }
        val duplicate = repository.append(params, batch.copy(articles = listOf(article, article.copy(link = "middle"), article.copy(link = "two"))), 97)
        assertFalse(duplicate.added); assertEquals(97L, duplicate.order)
        withContext(Dispatchers.IO) { assertNull(database.rssArticleDao.get("source", "middle", "Category")) }
    }
    @Test fun immutableProjectionUsesJoinedReadStateAndThreeColumnKeys() = runBlocking {
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(article.copy(order = 10), article.copy(origin = "other"), article.copy(sort = "Other")); database.rssReadRecordDao.insertRecord(article.toRecord()) }
        val rows = repository.observe(params).first(); assertEquals(1, rows.size); assertTrue(rows.single().read)
        assertEquals("One", rows.single().title); assertEquals("Image", rows.single().image)
        assertNotEquals(rssArticleRowKey(article), rssArticleRowKey(article.copy(origin = "other")))
        assertNotEquals(rssArticleRowKey(article), rssArticleRowKey(article.copy(sort = "Other")))
        assertNotEquals(rssArticleRowKey(article.copy(origin = "ab", link = "c")), rssArticleRowKey(article.copy(origin = "a", link = "bc")))
    }
    @Test fun resolveReadsLatestScopedFullArticleAndRejectsOtherOriginOrCategory() = runBlocking {
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(article, article.copy(sort = "Other"), article.copy(origin = "other")); database.rssArticleDao.update(article.copy(title = "Latest", content = "Latest body", variable = "Latest variable")) }
        val resolved = repository.resolve(params, rssArticleRowKey(article))!!
        assertEquals("Latest", resolved.title); assertEquals("Latest body", resolved.content); assertEquals("Latest variable", resolved.variable)
        assertNull(repository.resolve(params, rssArticleRowKey(article.copy(sort = "Other"))))
        assertNull(repository.resolve(params, rssArticleRowKey(article.copy(origin = "other"))))
        withContext(Dispatchers.IO) { database.rssArticleDao.delete("source") }
        assertNull(repository.resolve(params, rssArticleRowKey(article)))
    }
}
