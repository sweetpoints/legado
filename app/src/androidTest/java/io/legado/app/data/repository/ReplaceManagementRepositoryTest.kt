package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ReplaceManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppReplaceManagementRepository
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "replace-manager-${UUID.randomUUID()}",
        )
    private val id = System.nanoTime()
    private val first =
        ReplaceRule(
            id = id,
            name = "First",
            group = " A；甲 ",
            pattern = "old",
            replacement = "new",
            scope = "Book",
            excludeScope = "Other",
            order = 10,
        )
    private val second =
        first.copy(id = id + 1, name = "Second", group = "AA", isEnabled = false, order = 20)
    private val third = first.copy(id = id + 2, name = "Third", group = null, order = 30)

    @Before
    fun before() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = AppReplaceManagementRepository(database, directory)
        runBlocking(Dispatchers.IO) { database.replaceRuleDao.insert(first, second, third) }
    }

    @After
    fun after() {
        listOf(first, second, third).forEach { ReplacePreviewConfig.removeSample(it.id) }
        database.close()
        directory.deleteRecursively()
    }

    private suspend fun latest(id: Long) =
        withContext(Dispatchers.IO) { database.replaceRuleDao.findById(id)!! }

    @Test
    fun realFiltersKeepExactGroupMembersEnabledFlagsAndSearchOrder() = runBlocking {
        assertEquals(
            listOf(id, id + 1, id + 2),
            repository.rows(ReplaceManagementFilter.All).first().map { it.id },
        )
        assertEquals(
            listOf(id),
            repository.rows(ReplaceManagementFilter.Group("A")).first().map { it.id },
        )
        assertEquals(
            listOf(id + 1),
            repository.rows(ReplaceManagementFilter.Disabled).first().map { it.id },
        )
        assertEquals(
            listOf(id, id + 2),
            repository.rows(ReplaceManagementFilter.Enabled).first().map { it.id },
        )
        assertEquals(
            setOf(id + 2),
            repository.rows(ReplaceManagementFilter.NoGroup).first().map { it.id }.toSet(),
        )
        assertEquals(
            listOf(id + 1),
            repository.rows(ReplaceManagementFilter.Search("Second")).first().map { it.id },
        )
        assertEquals(
            "First ( A；甲 )",
            repository.rows(ReplaceManagementFilter.All).first().first().displayName,
        )
        assertEquals(setOf("A", "AA", "甲"), repository.groups().first().toSet())
    }

    @Test
    fun enableAndGroupEditsRereadAndPreserveConcurrentRuleMetadata() = runBlocking {
        val edited =
            second.copy(
                pattern = "Latest pattern",
                replacement = "Latest replacement",
                scopeTitle = true,
                timeoutMillisecond = 9876,
                group = "AA,Concurrent",
            )
        withContext(Dispatchers.IO) { database.replaceRuleDao.update(edited) }
        repository.enabled(listOf(second.id, second.id), true)
        repository.group(listOf(second.id), "A,New", true)
        repository.group(listOf(second.id), "A", false)
        assertEquals(
            GSON.toJson(edited.copy(isEnabled = true, group = "AA,Concurrent,New")),
            GSON.toJson(latest(second.id)),
        )
        assertEquals(GSON.toJson(first), GSON.toJson(latest(first.id)))
    }

    @Test
    fun bulkEdgesPreserveProvidedSelectionOrderAndGlobalRelativeMoveResolvesCurrentRows() =
        runBlocking {
            repository.edge(listOf(second.id, first.id), true)
            assertEquals(
                listOf(second.id, first.id, third.id),
                withContext(Dispatchers.IO) { database.replaceRuleDao.all.map { it.id } },
            )
            assertEquals(8, latest(second.id).order)
            assertEquals(9, latest(first.id).order)
            repository.edge(listOf(second.id, first.id), false)
            assertEquals(
                listOf(third.id, second.id, first.id),
                withContext(Dispatchers.IO) { database.replaceRuleDao.all.map { it.id } },
            )
            repository.move(first.id, third.id, false)
            assertEquals(
                listOf(first.id, third.id, second.id),
                withContext(Dispatchers.IO) { database.replaceRuleDao.all.map { it.id } },
            )
            assertEquals(
                listOf(0, 1, 2),
                withContext(Dispatchers.IO) { database.replaceRuleDao.all.map { it.order } },
            )
            val before = withContext(Dispatchers.IO) { GSON.toJson(database.replaceRuleDao.all) }
            repository.move(first.id, first.id, true)
            repository.move(Long.MIN_VALUE, third.id, true)
            assertEquals(
                before,
                withContext(Dispatchers.IO) { GSON.toJson(database.replaceRuleDao.all) },
            )
        }

    @Test
    fun deletionCleansOnlyOwnedSamplesAndIsSafeToRepeatAfterRulesDisappear() = runBlocking {
        ReplacePreviewConfig.saveSample(first.id, "Owned first")
        ReplacePreviewConfig.saveSample(second.id, "Owned second")
        repository.delete(listOf(first.id, first.id))
        repository.delete(listOf(first.id))
        assertNull(withContext(Dispatchers.IO) { database.replaceRuleDao.findById(first.id) })
        assertEquals("", ReplacePreviewConfig.sample(first.id))
        assertEquals("Owned second", ReplacePreviewConfig.sample(second.id))
        assertEquals(GSON.toJson(second), GSON.toJson(latest(second.id)))
    }

    @Test
    fun exportIncludesLatestCompleteMetadataAndPreviewSampleInSelectionOrder() = runBlocking {
        ReplacePreviewConfig.saveSample(first.id, "Exact preview")
        val edited = first.copy(replacement = "Latest", scopeSource = true)
        withContext(Dispatchers.IO) { database.replaceRuleDao.update(edited) }
        val output = repository.export(listOf(second.id, first.id, first.id, Long.MIN_VALUE))
        assertEquals("exportReplaceRule.json", output.name)
        val values =
            withContext(Dispatchers.IO) {
                GSON.fromJsonArray<ReplaceRule>(File(output.path).readText()).getOrThrow()
            }
        assertEquals(listOf(second.id, first.id), values.map { it.id })
        assertEquals("Latest", values.last().replacement)
        assertTrue(values.last().scopeSource)
        assertEquals("Exact preview", values.last().previewText)
        repository.releaseExport(output.path)
        assertFalse(File(output.path).exists())
    }

    @Test
    fun cancelAfterExportIoWriteReleasesOnlyUndeliveredOwnedFile() = runBlocking {
        val existing = repository.export(listOf(second.id))
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated =
            AppReplaceManagementRepository(
                database,
                directory,
                afterExport = {
                    written.countDown()
                    release.await(10, TimeUnit.SECONDS)
                },
            )
        val job = launch { gated.export(listOf(first.id)) }
        try {
            withContext(Dispatchers.IO) { assertTrue(written.await(10, TimeUnit.SECONDS)) }
            job.cancel()
            release.countDown()
            job.join()
            assertEquals(listOf(File(existing.path).name), directory.listFiles()!!.map { it.name })
        } finally {
            release.countDown()
            job.cancelAndJoin()
            repository.releaseExport(existing.path)
        }
    }

    @Test
    fun releaseRejectsPathsOutsideOwnedDirectoryAndKeepsOtherFiles() = runBlocking {
        val outside = File(directory.parentFile, "${UUID.randomUUID()}.json")
        try {
            withContext(Dispatchers.IO) { outside.writeText("Other owner") }
            try {
                repository.releaseExport(outside.path)
                fail("Must reject")
            } catch (_: IllegalArgumentException) {}
            assertTrue(outside.exists())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun cancellationAfterAcceptedDeletionStillFinishesSamplesAndKeepsOtherRules() = runBlocking {
        ReplacePreviewConfig.saveSample(first.id, "Owned first")
        ReplacePreviewConfig.saveSample(second.id, "Owned second")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated =
            AppReplaceManagementRepository(
                database,
                directory,
                removeSample = { id ->
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    ReplacePreviewConfig.removeSample(id)
                },
            )
        val job = launch { gated.delete(listOf(first.id)) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            assertNull(withContext(Dispatchers.IO) { database.replaceRuleDao.findById(first.id) })
            job.cancel()
            release.countDown()
            job.join()
            assertEquals("", ReplacePreviewConfig.sample(first.id))
            assertEquals("Owned second", ReplacePreviewConfig.sample(second.id))
            assertEquals(GSON.toJson(second), GSON.toJson(latest(second.id)))
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }
}
