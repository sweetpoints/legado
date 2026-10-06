package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class MainRssRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repo: AppMainRssRepository
    private var deleted = emptyList<RssSource>()
    private val first =
        RssSource(
            sourceUrl = "https://first-main.invalid",
            sourceName = "First",
            sourceGroup = "A,Other",
            sourceIcon = "icon",
            customOrder = 10,
            header = "auth",
            ruleContent = "body@text",
            enabled = true,
        )
    private val similar =
        first.copy(
            sourceUrl = "https://similar-main.invalid",
            sourceName = "Similar",
            sourceGroup = "AA",
            customOrder = 20,
        )
    private val disabled =
        first.copy(
            sourceUrl = "https://disabled-main.invalid",
            sourceName = "Disabled",
            sourceGroup = "Hidden",
            customOrder = 30,
            enabled = false,
        )

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repo =
            AppMainRssRepository(
                database,
                { sources ->
                    deleted = sources.map { it.copy() }
                    database.rssSourceDao.delete(*sources.toTypedArray())
                },
            )
        runBlocking(Dispatchers.IO) { database.rssSourceDao.insert(first, similar, disabled) }
    }

    @After
    fun cleanup() {
        database.close()
    }

    private fun id(source: RssSource) = mainRssId(source.sourceUrl)

    private suspend fun latest(source: RssSource) =
        withContext(Dispatchers.IO) { database.rssSourceDao.getByKey(source.sourceUrl)!! }

    @Test
    fun realEnabledSearchAndExactGroupQueriesKeepGlobalOrderAndHeaderDataProjection() =
        runBlocking {
            assertEquals(listOf(id(first), id(similar)), repo.rows("").first().map { it.id })
            assertEquals(listOf(id(first)), repo.rows("group:A").first().map { it.id })
            assertEquals(listOf(id(similar)), repo.rows("Similar").first().map { it.id })
            assertEquals(listOf("A", "AA", "Other"), repo.groups().first())
            assertEquals("icon", repo.rows("group:A").first().single().icon)
        }

    @Test
    fun narrowTopAndDisableRereadCurrentMetadataRatherThanDisplayedSnapshot() = runBlocking {
        val edited =
            similar.copy(
                sourceComment = "Concurrent",
                ruleContent = "Latest body",
                header = "Latest auth",
            )
        withContext(Dispatchers.IO) { database.rssSourceDao.update(edited) }
        repo.top(id(similar))
        repo.disable(id(similar))
        assertEquals(
            GSON.toJson(edited.copy(customOrder = 9, enabled = false)),
            GSON.toJson(latest(similar)),
        )
        assertEquals(GSON.toJson(first), GSON.toJson(latest(first)))
        repo.top("missing")
        repo.disable("missing")
        assertEquals(9, latest(similar).customOrder)
    }

    @Test
    fun deleteUsesFreshCompleteEntityAndEstablishedSourceCleanupPipeline() = runBlocking {
        val edited =
            first.copy(
                sourceComment = "Edited",
                ruleContent = "Latest",
                header = "Private metadata",
            )
        withContext(Dispatchers.IO) { database.rssSourceDao.update(edited) }
        repo.delete(id(first))
        assertEquals(listOf(GSON.toJson(edited)), deleted.map { GSON.toJson(it) })
        assertNull(repo.source(id(first)))
        assertEquals(similar.sourceUrl, repo.source(id(similar))!!.sourceUrl)
    }

    @Test
    fun navigationUsesLatestSourceAndEvaluatesWithCompleteMetadataOffMain() = runBlocking {
        val edited =
            first.copy(
                sourceName = "Latest name",
                singleUrl = true,
                sortUrl = "@js:compute",
                header = "Complete auth",
                variableComment = "All vars",
            )
        withContext(Dispatchers.IO) { database.rssSourceDao.update(edited) }
        var thread: Looper? = Looper.getMainLooper()
        val repository =
            AppMainRssRepository(
                database,
                evaluate = { source, script ->
                    thread = Looper.myLooper()
                    assertEquals("compute", script)
                    assertEquals(GSON.toJson(edited), GSON.toJson(source))
                    "https://resolved.invalid"
                },
            )
        assertEquals(
            MainRssNavigation(
                MainRssDestination.ReaderLink,
                first.sourceUrl,
                "Latest name",
                "https://resolved.invalid",
            ),
            repository.prepare(id(first)),
        )
        assertNotSame(Looper.getMainLooper(), thread)
        assertNull(repository.prepare("missing"))
    }

    @Test
    fun realV8StartHtmlEvaluationRetainsRuleContextAndLegacyNavigationChoice() = runBlocking {
        val scripted = first.copy(startHtml = "@js:'<html>' + source.getTag() + '</html>'")
        withContext(Dispatchers.IO) { database.rssSourceDao.update(scripted) }
        val result = repo.prepare(id(first))!!
        assertEquals(MainRssDestination.ReaderHtml, result.destination)
        assertEquals("<html>${first.getTag()}</html>", result.value)
    }
}
