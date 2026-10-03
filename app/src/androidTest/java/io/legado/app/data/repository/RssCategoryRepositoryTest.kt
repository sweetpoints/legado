package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RssCategoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRssCategoryRepository
    private val source = RssSource(sourceUrl = "fixture://${UUID.randomUUID()}", sourceName = "Owned", searchUrl = "search", loginUrl = "login",
        sourceComment = "Keep metadata", ruleArticles = "Article rule", articleStyle = 4)
    private var categoryCalls = 0; private var invalidations = 0
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repository = AppRssCategoryRepository(database, { categoryCalls++; listOf("First" to "one", "Second" to "two") }, { invalidations++ })
        runBlocking(Dispatchers.IO) { database.rssSourceDao.insert(source) }
    }
    @After fun cleanup() { source.setVariable(null); database.close() }
    @Test fun databaseLoadReturnsOwnedSourceAndDeclaredCategoryOrder() = runBlocking {
        val first = repository.load(RssCategoryRequest(source.sourceUrl))
        assertEquals(listOf("First", "Second"), first.tabs.map { it.name }); assertEquals(listOf(0, 1), first.tabs.map { it.index })
        first.source!!.articleStyle = 99
        assertEquals(4, repository.load(RssCategoryRequest(source.sourceUrl)).source!!.articleStyle)
        assertEquals("Article rule", first.source!!.ruleArticles)
    }
    @Test fun explicitJsonPreservesMapOrderAndMalformedJsonKeepsOriginalUrl() = runBlocking {
        val raw = """{"Second":"two","First":"one"}"""
        val loaded = repository.load(RssCategoryRequest(source.sourceUrl, raw))
        assertEquals(listOf("Second" to "two", "First" to "one"), loaded.tabs.map { it.name to it.url }); assertEquals(0, categoryCalls)
        val invalid = "{broken}"
        assertEquals(listOf("" to invalid), repository.load(RssCategoryRequest(source.sourceUrl, invalid)).tabs.map { it.name to it.url })
    }
    @Test fun submittedEmptySearchUsesSearchUrlWhileNullQueryUsesCategories() = runBlocking {
        assertEquals("搜索", repository.load(RssCategoryRequest(source.sourceUrl, query = "")).tabs.single().name)
        assertEquals("search", repository.load(RssCategoryRequest(source.sourceUrl, query = "query")).tabs.single().url)
        assertEquals(0, categoryCalls)
        assertEquals(2, repository.load(RssCategoryRequest(source.sourceUrl)).tabs.size)
        withContext(Dispatchers.IO) { database.rssSourceDao.update(source.copy(searchUrl = null)) }
        assertTrue(repository.load(RssCategoryRequest(source.sourceUrl, query = "query")).tabs.isEmpty())
    }
    @Test fun missingSourceKeepsCallerUrlFallbackAndNullSourceProducesNoTabs() = runBlocking {
        val key = "missing://${UUID.randomUUID()}"
        val loaded = repository.load(RssCategoryRequest(key, "direct"))
        assertEquals(key, loaded.source!!.sourceUrl); assertEquals("direct", loaded.tabs.single().url)
        assertNull(repository.load(RssCategoryRequest()).source); assertTrue(repository.load(RssCategoryRequest()).tabs.isEmpty())
    }
    @Test fun styleCycleChangesOnlyCurrentStyleAndKeepsConcurrentMetadataEdits() = runBlocking {
        withContext(Dispatchers.IO) { database.rssSourceDao.update(source.copy(sourceComment = "External update")) }
        assertEquals(0, repository.switchStyle(source.sourceUrl).articleStyle)
        repeat(4) { assertEquals(it + 1, repository.switchStyle(source.sourceUrl).articleStyle) }
        val stored = withContext(Dispatchers.IO) { database.rssSourceDao.getByKey(source.sourceUrl)!! }
        assertEquals("External update", stored.sourceComment); assertEquals("Article rule", stored.ruleArticles)
        assertEquals("login", stored.loginUrl)
    }
    @Test fun refreshUsesExistingSortCacheInvalidationAndClearDeletesOnlyCurrentSourceArticles() = runBlocking {
        val first = RssArticle(origin = source.sourceUrl, sort = "First", link = "one")
        val other = RssArticle(origin = "other", sort = "First", link = "one")
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(first, other) }
        repository.load(RssCategoryRequest(source.sourceUrl), refresh = true); assertEquals(1, invalidations)
        repository.clearArticles(source.sourceUrl)
        withContext(Dispatchers.IO) { assertNull(database.rssArticleDao.get(source.sourceUrl, "one", "First")); assertNotNull(database.rssArticleDao.get("other", "one", "First")) }
    }
    @Test fun variableReadsLatestValueAndCommentAndNullDeletesOnlyOwnedFixture() = runBlocking {
        repository.variable(source.sourceUrl, "Exact variable")
        withContext(Dispatchers.IO) { database.rssSourceDao.update(source.copy(variableComment = "Latest comment")) }
        val value = repository.variable(source.sourceUrl)
        assertEquals(source.sourceUrl, value.key); assertEquals("Exact variable", value.value)
        assertTrue(value.comment.startsWith("Latest comment\n"))
        repository.variable(source.sourceUrl, null); assertEquals("", repository.variable(source.sourceUrl).value)
    }
}
