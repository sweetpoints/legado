package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.http.StrResponse
import io.legado.app.model.rss.Rss
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssReaderRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRssReaderRepository
    private val source =
        RssSource(
            sourceUrl = "https://reader-${UUID.randomUUID()}.invalid",
            sourceName = "Source name",
            ruleContent = "Content rule",
            header = "{\"User-Agent\":\"Owned agent\",\"X-Reader\":\"Owned header\"}",
            style = "Normal CSS",
        )
    private val article =
        RssArticle(
            origin = source.sourceUrl,
            sort = "Category",
            link = "${source.sourceUrl}/article",
            title = "Article title",
            variable = "{\"key\":\"value\"}",
            content = "Owned content",
            description = null,
            image = "Image",
            pubDate = "Date",
            durPos = 41,
        )
    private val request =
        RssReaderRequest(source.sourceUrl, link = article.link, sort = article.sort)
    private var inputs: Triple<RssArticle, String, RssSource>? = null
    private var result = "Parsed body"
    private var parse: (suspend () -> Unit)? = null

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository =
            AppRssReaderRepository(database) { value, rule, current ->
                inputs = Triple(value.copy(), rule, current.copy())
                parse?.invoke()
                result
            }
        runBlocking(Dispatchers.IO) {
            database.rssSourceDao.insert(source)
            database.rssArticleDao.insert(article)
        }
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun cachedFavoriteDescriptionHasPriorityAndRetainsFullMetadataWithoutParsing() = runBlocking {
        val favorite =
            article
                .copy(
                    title = "Favorite title",
                    description = "Favorite body",
                    variable = "Favorite variable",
                )
                .toStar()
        withContext(Dispatchers.IO) {
            database.rssArticleDao.update(article.copy(description = "Article body"))
            database.rssStarDao.insert(favorite)
        }
        val value = repository.load(request)!!
        assertNull(inputs)
        assertEquals("Favorite variable", value.article!!.variable)
        assertEquals("Favorite title", value.article!!.title)
        assertEquals(41, value.article!!.durPos)
        val document = value.document as RssReaderDocument.Html
        assertTrue(document.html.contains("Favorite body"))
        assertEquals(article.link, document.baseUrl)
        assertEquals("Source name", value.title)
        assertEquals("Owned agent", value.headers["User-Agent"])
    }

    @Test
    fun ruleUsesFullArticleAndSavesDescriptionWithoutOverwritingConcurrentMetadata() = runBlocking {
        withContext(Dispatchers.IO) {
            database.rssStarDao.insert(article.toStar().copy(starTime = 123L))
        }
        parse = {
            database.rssArticleDao.update(
                article.copy(title = "Concurrent title", variable = "Concurrent variable")
            )
            val favorite = database.rssStarDao.get(article.origin, article.link)!!
            database.rssStarDao.update(
                favorite.copy(title = "Concurrent favorite", starTime = 321L)
            )
        }
        val value = repository.load(request)!!
        assertEquals(article.variable, inputs!!.first.variable)
        assertEquals("Content rule", inputs!!.second)
        assertEquals("Owned agent", inputs!!.third.getHeaderMap()["User-Agent"])
        assertEquals("Concurrent title", value.article!!.title)
        assertEquals("Concurrent variable", value.article!!.variable)
        assertEquals("Parsed body", value.article!!.description)
        withContext(Dispatchers.IO) {
            assertEquals(
                "Concurrent title",
                database.rssArticleDao.get(article.origin, article.link, article.sort)!!.title,
            )
            val favorite = database.rssStarDao.get(article.origin, article.link)!!
            assertEquals("Concurrent favorite", favorite.title)
            assertEquals(321L, favorite.starTime)
            assertEquals("Parsed body", favorite.description)
        }
    }

    @Test
    fun missingArticleLoadsExactHistoryUrlAndPreservesExplicitEmptyTitle() = runBlocking {
        withContext(Dispatchers.IO) {
            database.rssSourceDao.update(source.copy(ruleContent = null))
        }
        val value =
            repository.load(request.copy(link = "https://reader.invalid/missing", title = ""))!!
        assertEquals("", value.title)
        assertNull(value.article)
        val document = value.document as RssReaderDocument.Url
        assertEquals("https://reader.invalid/missing", document.url)
        assertEquals("Owned agent", document.userAgent)
        assertEquals("Owned header", document.headers["X-Reader"])
        assertFalse(document.headers.keys.any { it.equals("User-Agent", true) })
    }

    @Test
    fun sourceStartHtmlUsesStartScriptAndStartStyleAndBaseUrlPreference() = runBlocking {
        withContext(Dispatchers.IO) {
            database.rssSourceDao.update(
                source.copy(startJs = "Start JS", startStyle = "Start CSS", loadWithBaseUrl = false)
            )
        }
        val value =
            repository.load(
                RssReaderRequest(source.sourceUrl, startHtml = "<body>Owned HTML</body>")
            )!!
        val document = value.document as RssReaderDocument.Html
        assertTrue(document.html.contains("<script>Start JS</script></body>"))
        assertTrue(document.html.contains("<style>Start CSS</style>"))
        assertFalse(document.html.contains("Normal CSS"))
        assertNull(document.baseUrl)
        assertEquals(source.sourceUrl, document.historyUrl)
        assertNull(inputs)
    }

    @Test
    fun singleUrlWithoutLinkLoadsOpenUrlEvenWhenContentRuleExists() = runBlocking {
        withContext(Dispatchers.IO) { database.rssSourceDao.update(source.copy(singleUrl = true)) }
        val value = repository.load(RssReaderRequest(source.sourceUrl, openUrl = article.link))!!
        assertTrue(value.document is RssReaderDocument.Url)
        assertNull(inputs)
    }

    @Test
    fun missingSourceStillLoadsHistoryAndMissingOriginDoesNotCreateDocument() = runBlocking {
        withContext(Dispatchers.IO) { database.rssSourceDao.delete(source.sourceUrl) }
        val value = repository.load(request.copy(link = "https://missing.invalid"))!!
        assertEquals(source.sourceUrl, value.title)
        assertNull(value.source)
        assertTrue(value.document is RssReaderDocument.Url)
        assertNull(repository.load(RssReaderRequest()))
    }

    @Test
    fun favoriteOperationsPreserveExistingEntityAndEditOnlyRequestedFields() = runBlocking {
        val favorite = repository.addFavorite(article)
        withContext(Dispatchers.IO) {
            database.rssStarDao.update(
                favorite.copy(
                    title = "External",
                    content = "External body",
                    variable = "External variable",
                )
            )
        }
        assertEquals("External", repository.addFavorite(article).title)
        val updated = repository.updateFavorite(article, "Edited", "Owned group")
        assertEquals("Edited", updated.first.title)
        assertEquals("Owned group", updated.second.group)
        assertEquals("External variable", updated.first.variable)
        assertEquals("External body", updated.second.content)
        repository.deleteFavorite(article.origin, article.link)
        withContext(Dispatchers.IO) {
            assertNull(database.rssStarDao.get(article.origin, article.link))
        }
    }

    @Test
    fun nonCooperativeContentReturningAfterCancellationCannotWriteDescription() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        parse = {
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
        }
        val job = async { repository.load(request) }
        try {
            entered.await()
            job.cancel()
            gate.complete(Unit)
            job.join()
            withContext(Dispatchers.IO) {
                assertNull(
                    database.rssArticleDao
                        .get(article.origin, article.link, article.sort)!!
                        .description
                )
            }
        } finally {
            gate.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun ruleFailureKeepsLegacyErrorContentWithoutCachingFailedBody() = runBlocking {
        parse = { error("Owned failure") }
        val value = repository.load(request)!!
        assertTrue((value.document as RssReaderDocument.Html).html.contains("加载正文失败"))
        assertTrue((value.document as RssReaderDocument.Html).html.contains("Owned failure"))
        withContext(Dispatchers.IO) {
            assertNull(
                database.rssArticleDao.get(article.origin, article.link, article.sort)!!.description
            )
        }
    }

    @Test
    fun actualJavaPutParserVariablesSurviveSavingBody() = runBlocking {
        val real =
            AppRssReaderRepository(database) { value, _, current ->
                Rss.analyzeContentPage(
                        value,
                        current,
                        "@js:java.put('parsed', 'owned'); 'Body'",
                        value.link,
                        StrResponse(value.link, "Fixture"),
                        getNextPageUrl = false,
                        printLog = false,
                    )
                    .first
            }
        val value = real.load(request)!!
        assertEquals("owned", value.article!!.getVariable("parsed"))
        assertEquals("value", value.article!!.getVariable("key"))
        withContext(Dispatchers.IO) {
            assertEquals(
                "owned",
                database.rssArticleDao
                    .get(article.origin, article.link, article.sort)!!
                    .getVariable("parsed"),
            )
        }
    }

    @Test
    fun parserVariableChangesMergeWithoutOverwritingConcurrentChangesAndAdditions() = runBlocking {
        val real =
            AppRssReaderRepository(database) { value, _, current ->
                val body =
                    Rss.analyzeContentPage(
                            value,
                            current,
                            "@js:java.put('key', 'parser'); java.put('parsed', 'owned'); 'Body'",
                            value.link,
                            StrResponse(value.link, "Fixture"),
                            getNextPageUrl = false,
                            printLog = false,
                        )
                        .first
                database.rssArticleDao.update(
                    article.copy(
                        title = "Concurrent",
                        variable = "{\"key\":\"external\",\"concurrent\":\"addition\"}",
                    )
                )
                body
            }
        val value = real.load(request)!!
        assertEquals("Concurrent", value.article!!.title)
        assertEquals("external", value.article!!.getVariable("key"))
        assertEquals("addition", value.article!!.getVariable("concurrent"))
        assertEquals("owned", value.article!!.getVariable("parsed"))
    }
}
