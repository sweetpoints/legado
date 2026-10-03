package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RssSourceManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private lateinit var repository: AppRssSourceManagementRepository
    private var deleted = emptyList<RssSource>()
    private var defaults = 0
    private val first = RssSource(sourceUrl = "https://first.invalid", sourceName = "First", sourceGroup = "A", customOrder = 10,
        enabled = true, header = "{\"token\":\"Owned\"}", ruleContent = "Owned rule", loginUrl = "login", variableComment = "{\"Owned\":1}")
    private val second = first.copy(sourceUrl = "https://second.invalid", sourceName = "Second", sourceGroup = "AB", customOrder = 20, enabled = false, loginUrl = null)
    private val third = first.copy(sourceUrl = "https://third.invalid", sourceName = "Third", sourceGroup = null, customOrder = 30, loginUrl = null)
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "rss-management-${UUID.randomUUID()}")
        repository = AppRssSourceManagementRepository(database, directory, { sources ->
            deleted = sources.map { it.copy() }; database.rssSourceDao.delete(*sources.toTypedArray())
        }, { defaults++ })
        runBlocking(Dispatchers.IO) { database.rssSourceDao.insert(first, second, third) }
    }
    @After fun cleanup() { database.close(); directory.deleteRecursively() }
    private fun id(source: RssSource) = rssSourceManagementId(source.sourceUrl)
    private suspend fun latest(source: RssSource) = withContext(Dispatchers.IO) { database.rssSourceDao.getByKey(source.sourceUrl)!! }
    private suspend fun update(source: RssSource) = withContext(Dispatchers.IO) { database.rssSourceDao.update(source) }
    @Test fun actualDaoFiltersKeepExactGroupsAndOriginalEnabledLoginAndNoGroupSemantics() = runBlocking {
        assertEquals(listOf(id(first), id(second), id(third)), repository.rows(RssSourceManagementFilter.All).first().map { it.id })
        assertEquals(listOf(id(first), id(third)), repository.rows(RssSourceManagementFilter.Enabled).first().map { it.id })
        assertEquals(listOf(id(second)), repository.rows(RssSourceManagementFilter.Disabled).first().map { it.id })
        assertEquals(listOf(id(first)), repository.rows(RssSourceManagementFilter.Login).first().map { it.id })
        assertEquals(listOf(id(third)), repository.rows(RssSourceManagementFilter.NoGroup).first().map { it.id })
        assertEquals(listOf(id(first)), repository.rows(RssSourceManagementFilter.Group("A")).first().map { it.id })
        assertEquals(listOf(id(second)), repository.rows(RssSourceManagementFilter.Search("Second")).first().map { it.id })
        assertEquals(listOf("A", "AB"), repository.groups().first())
    }
    @Test fun enabledMutationRereadsCurrentMetadataAndDropsInvalidOrRepeatedIds() = runBlocking {
        val edited = first.copy(sourceName = "Concurrent editor", header = "New header", ruleContent = "New rule", customOrder = 77)
        update(edited); repository.enabled(listOf(id(first), id(first), "missing"), false)
        assertEquals(GSON.toJson(edited.copy(enabled = false)), GSON.toJson(latest(first)))
        assertEquals(GSON.toJson(second), GSON.toJson(latest(second)))
    }
    @Test fun groupChangesAreExactAndPreserveLatestUnrelatedFields() = runBlocking {
        val edited = first.copy(ruleContent = "Concurrent rule", sourceGroup = "A,AB")
        update(edited); repository.group(listOf(id(first), "missing"), "A", add = false)
        assertEquals(GSON.toJson(edited.copy(sourceGroup = "AB")), GSON.toJson(latest(first)))
        repository.group(listOf(id(first)), "New", add = true)
        assertEquals("AB,New", latest(first).sourceGroup); assertEquals("Concurrent rule", latest(first).ruleContent)
    }
    @Test fun moveUsesGlobalOrderingAndCurrentMetadataEvenWhenVisibleRowsWereFiltered() = runBlocking {
        val edited = second.copy(header = "Concurrent metadata", ruleContent = "Latest rule")
        update(edited); repository.move(id(third), id(first), after = false)
        val sources = withContext(Dispatchers.IO) { database.rssSourceDao.all }
        assertEquals(listOf(third.sourceUrl, first.sourceUrl, second.sourceUrl), sources.map { it.sourceUrl })
        assertEquals(listOf(0, 1, 2), sources.map { it.customOrder })
        assertEquals(GSON.toJson(edited.copy(customOrder = 2)), GSON.toJson(latest(second)))
        repository.move("missing", id(first), false)
        assertEquals(sources.map { GSON.toJson(it) }, withContext(Dispatchers.IO) { database.rssSourceDao.all }.map { GSON.toJson(it) })
    }
    @Test fun topAndBottomKeepOriginalLegacyOrderingForMultipleSelections() = runBlocking {
        repository.edge(listOf(id(second), id(third)), top = true)
        assertEquals(9, latest(second).customOrder); assertEquals(8, latest(third).customOrder)
        repository.edge(listOf(id(third), id(second)), top = false)
        assertEquals(11, latest(third).customOrder); assertEquals(12, latest(second).customOrder)
        assertEquals("Owned rule", latest(third).ruleContent)
    }
    @Test fun exportRereadsFullCurrentMetadataInSelectionOrderAndUsesDistinctPrivateFiles() = runBlocking {
        val edited = first.copy(variableComment = "{\"latest\":true}", ruleContent = "Concurrent export rule")
        update(edited)
        val one = repository.export(listOf(id(first), "missing", id(first)))
        assertEquals("rssSource_First.json", one.name)
        assertEquals(GSON.toJson(listOf(edited)), File(one.path).readText())
        val two = repository.export(listOf(id(second), id(first)))
        assertNotEquals(one.path, two.path); assertEquals(GSON.toJson(listOf(second, edited)), File(two.path).readText())
        assertTrue(runCatching { repository.export(listOf("missing")) }.isFailure)
    }
    @Test fun exportReleaseAcceptsOnlyOwnedUuidFilesAndCannotRemoveUnrelatedContent() = runBlocking {
        val exported = repository.export(listOf(id(first)))
        val unrelated = File(directory, "unrelated.json").apply { writeText("Keep") }
        assertTrue(runCatching { repository.releaseExport(unrelated.path) }.isFailure)
        assertEquals("Keep", unrelated.readText())
        repository.releaseExport(exported.path); repository.releaseExport(exported.path)
        assertFalse(File(exported.path).exists()); assertTrue(unrelated.exists())
    }
    @Test fun cancellationAtIoReturnCleansOnlyUndeliveredExportFile() = runBlocking {
        assertTrue(directory.mkdirs())
        val previous = File(directory, "${UUID.randomUUID()}.json").apply { writeText("Keep previous export") }
        val entered = CompletableDeferred<Unit>(); val gate = java.util.concurrent.CountDownLatch(1)
        val exporting = AppRssSourceManagementRepository(database, directory, afterExport = {
            entered.complete(Unit)
            check(gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
        })
        val job = async { exporting.export(listOf(id(first))) }
        try {
            entered.await(); job.cancel(); gate.countDown(); job.join()
            assertEquals(listOf(previous.name), directory.listFiles()!!.map { it.name })
            assertEquals("Keep previous export", previous.readText())
        } finally { gate.countDown(); job.cancelAndJoin() }
    }
    @Test fun deleteDelegatesLatestEntitiesOnceAndDefaultImportUsesEstablishedPipeline() = runBlocking {
        val edited = first.copy(sourceName = "Latest before delete", ruleContent = "Preserved rule")
        update(edited); repository.delete(listOf(id(first), id(first), "missing"))
        assertEquals(GSON.toJson(listOf(edited)), GSON.toJson(deleted))
        assertNull(withContext(Dispatchers.IO) { database.rssSourceDao.getByKey(first.sourceUrl) })
        assertEquals(GSON.toJson(second), GSON.toJson(latest(second)))
        repository.importDefault(); assertEquals(1, defaults)
    }
}
