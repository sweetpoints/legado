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

class RssArticlesReadRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRssArticlesReadRepository
    private val parameters = RssArticlesParameters("source", "Category", "url")
    private val article =
        RssArticle(
            origin = "source",
            sort = "Category",
            link = "link",
            title = "Before",
            content = "Body",
            description = "Description",
            image = "Image",
            pubDate = "Date",
            variable = "{\"key\":\"value\"}",
            durPos = 47,
        )

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = AppRssArticlesReadRepository(database)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun allThreeTypesResolveLatestFullMetadataAndSourceAndPersistReadRecord() = runBlocking {
        repeat(3) { type ->
            withContext(Dispatchers.IO) {
                database.rssSourceDao.insert(
                    RssSource(
                        sourceUrl = "source",
                        header = "Latest header $type",
                        ruleContent = "Content rule $type",
                    )
                )
                database.rssArticleDao.insert(article.copy(title = "Latest $type", type = type))
            }
            val value = repository.prepare(parameters, rssArticleRowKey(article))!!
            assertEquals(type, value.article.type)
            assertEquals("Latest $type", value.article.title)
            assertEquals(article.variable, value.article.variable)
            assertEquals("Body", value.article.content)
            assertEquals("Description", value.article.description)
            assertEquals(47, value.article.durPos)
            assertEquals("Date", value.article.pubDate)
            assertEquals("Image", value.article.image)
            assertEquals("Latest header $type", value.source!!.header)
            assertEquals("Content rule $type", value.source.ruleContent)
            assertTrue(
                database.rssArticleDao.flowByOriginSort("source", "Category").first().single().read
            )
            withContext(Dispatchers.IO) {
                assertNotNull(database.rssReadRecordDao.getRecord("link", "source"))
            }
        }
    }

    @Test
    fun preparingAgainPreservesFirstReadTimestampAndExistingProgress() = runBlocking {
        withContext(Dispatchers.IO) {
            database.rssArticleDao.insert(article)
            database.rssReadRecordDao.insertRecord(
                article.toRecord().copy(readTime = 123L, durPos = 91)
            )
        }
        repeat(2) { assertNotNull(repository.prepare(parameters, rssArticleRowKey(article))) }
        withContext(Dispatchers.IO) {
            val record = database.rssReadRecordDao.getRecord("link", "source")!!
            assertEquals(123L, record.readTime)
            assertEquals(91, record.durPos)
            assertEquals(1, database.rssReadRecordDao.countRecords)
        }
    }

    @Test
    fun deletedOrWrongScopeTicketCannotCreateReadRecord() = runBlocking {
        withContext(Dispatchers.IO) {
            database.rssArticleDao.insert(
                article.copy(sort = "Other"),
                article.copy(origin = "other"),
            )
        }
        assertNull(repository.prepare(parameters, rssArticleRowKey(article)))
        assertNull(repository.prepare(parameters, rssArticleRowKey(article.copy(sort = "Other"))))
        withContext(Dispatchers.IO) { assertEquals(0, database.rssReadRecordDao.countRecords) }
    }

    @Test
    fun missingSourceStillPreservesFullArticleForWebAndVideoReaders() = runBlocking {
        withContext(Dispatchers.IO) { database.rssArticleDao.insert(article.copy(type = 2)) }
        val value = repository.prepare(parameters, rssArticleRowKey(article))!!
        assertNull(value.source)
        assertEquals(article.variable, value.article.variable)
        assertEquals(2, value.article.type)
    }
}
