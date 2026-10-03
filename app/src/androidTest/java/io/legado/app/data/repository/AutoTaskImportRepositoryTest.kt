package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.AutoTask
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AutoTaskImportRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private var refreshes = 0
    private val sessions = mutableListOf<String>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun cleanup() {
        database.close()
        sessions.forEach { java.io.File(context.filesDir, "auto-task-import/$it.json").delete() }
    }

    private fun stage(source: String) =
        FileAutoTaskImportRepository.stage(context, source).also { sessions += it }

    private fun repo(reader: suspend (String) -> String = { error("unexpected source $it") }) =
        FileAutoTaskImportRepository(context, database, { refreshes++ }, reader)

    @Test
    fun actualRoomComparisonPreservesMetadataAndIgnoresRuntimeWhileNormalizingCron() = runBlocking {
        val local =
            AutoTaskRule(
                "existing",
                "Existing",
                script = "run()",
                customOrder = 7,
                lastRunAt = 50,
                lastLog = "log",
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(local) }
        val fresh =
            AutoTaskRule(
                "new",
                " New ",
                enable = false,
                cron = " ",
                script = "@js: run()",
                loginUrl = "https://login",
                jsLib = "library",
                header = "headers",
                comment = "memo",
            )
        val update = local.copy(name = "Changed")
        val id =
            stage(
                GSON.toJson(
                    listOf(
                        local.copy(customOrder = 0, lastRunAt = 0, lastLog = null),
                        update,
                        fresh,
                    )
                )
            )
        val loaded = repo().load(id)
        assertEquals(
            listOf(
                AutoTaskImportStatus.Exists,
                AutoTaskImportStatus.Update,
                AutoTaskImportStatus.New,
            ),
            loaded.items.map { it.status },
        )
        val parsed = GSON.fromJsonObject<AutoTaskRule>(loaded.items.last().json).getOrThrow()
        assertEquals("New", parsed.name)
        assertEquals(AutoTask.DEFAULT_CRON, parsed.cron)
        assertFalse(parsed.enable)
        assertEquals("library", parsed.jsLib)
        assertEquals("headers", parsed.header)
        assertEquals("https://login", parsed.loginUrl)
    }

    @Test
    fun uriAndUrlResolveOnceAndPersistGeneratedIdsAndEditorChangesAcrossRepositoryInstances() =
        runBlocking {
            var reads = 0
            val id = stage("https://source#requestWithoutUA")
            val repository = repo { source ->
                reads++
                if (source.startsWith("https")) "content://rules"
                else "{\"name\":\"New\",\"script\":\"run()\"}"
            }
            val first = repository.load(id).items.single()
            assertEquals(2, reads)
            val task =
                GSON.fromJsonObject<AutoTaskRule>(first.json).getOrThrow().copy(name = "Edited")
            repository.edit(id, first.key, GSON.toJson(task))
            val restored = repo().load(id).items.single()
            assertEquals(first.id, restored.id)
            assertEquals("Edited", restored.name)
            assertEquals(2, reads)
            withContext(Dispatchers.IO) { assertTrue(database.autoTaskRuleDao.all().isEmpty()) }
        }

    @Test
    fun upsertMergesRealRoomOrderAndLatestRuntimeAndRefreshesOnlyOnce() = runBlocking {
        val old =
            AutoTaskRule(
                "old",
                "Old",
                script = "old()",
                customOrder = 9,
                lastRunAt = 12,
                lastResult = "result",
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(old) }
        val id =
            stage(
                GSON.toJson(
                    listOf(
                        old.copy(name = "Updated", customOrder = 0, lastRunAt = 0),
                        AutoTaskRule("new", "First", script = "run()"),
                        AutoTaskRule("new", "Last", enable = false, script = "run()"),
                    )
                )
            )
        val repository = repo()
        val loaded = repository.load(id)
        withContext(Dispatchers.IO) {
            database.autoTaskRuleDao.upsert(old.copy(lastRunAt = 88, lastLog = "latest"))
        }
        val selected = loaded.items.mapTo(hashSetOf()) { it.key }
        repository.commit(id, selected)
        repository.commit(id, selected)
        assertTrue(repo().load(id).committed)
        assertEquals(1, refreshes)
        val actual = withContext(Dispatchers.IO) { database.autoTaskRuleDao.all() }
        assertEquals(2, actual.size)
        assertEquals(9, actual[0].customOrder)
        assertEquals(88L, actual[0].lastRunAt)
        assertEquals("latest", actual[0].lastLog)
        assertEquals(10, actual[1].customOrder)
        assertEquals("Last", actual[1].name)
        assertFalse(actual[1].enable)
    }

    @Test
    fun invalidEditableFieldsCannotOverwriteSessionAndEmptySelectionDoesNotRefresh() = runBlocking {
        val id = stage(GSON.toJson(AutoTaskRule("id", "Valid", script = "run()")))
        val repository = repo()
        val row = repository.load(id).items.single()
        for (invalid in
            listOf(
                AutoTaskRule("id", " ", script = "run()"),
                AutoTaskRule("id", "Bad", cron = "invalid", script = "run()"),
                AutoTaskRule("id", "Bad", script = "<js> </js>"),
            )) {
            assertTrue(runCatching { repository.edit(id, row.key, GSON.toJson(invalid)) }.isFailure)
            assertEquals("Valid", repository.load(id).items.single().name)
        }
        repository.commit(id, emptySet())
        assertEquals(0, refreshes)
        withContext(Dispatchers.IO) { assertTrue(database.autoTaskRuleDao.all().isEmpty()) }
    }

    @Test
    fun editingAnIdUsesOriginalLocalSnapshotAndRecomputesItsStatus() = runBlocking {
        val local = AutoTaskRule("local", "Local", script = "run()")
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(local) }
        val id = stage(GSON.toJson(AutoTaskRule("new", "New", script = "run()")))
        val repository = repo()
        val row = repository.load(id).items.single()
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(local.copy(name = "Later")) }
        val edited = repository.edit(id, row.key, GSON.toJson(local))
        assertEquals(AutoTaskImportStatus.Exists, edited.status)
        assertEquals("local", edited.id)
    }
}
