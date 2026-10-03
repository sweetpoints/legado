package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutoTaskManagementRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private lateinit var repository: RoomAutoTaskManagementRepository
    private var refreshes = 0
    private var history = listOf("https://old.invalid")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "auto-task-management-${UUID.randomUUID()}")
        repository = instance()
    }

    private fun instance() =
        RoomAutoTaskManagementRepository(
            context,
            database,
            {},
            {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                refreshes++
            },
            { history },
            { history = it },
            directory,
        )

    @After
    fun teardown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun liveProjectionIncludesDisabledRowsAndPreservesSummaryLogPriorityAndLogin() = runBlocking {
        val alpha =
            AutoTaskRule(
                "a",
                "Alpha",
                false,
                "0 * * * *",
                lastRunAt = 1234,
                lastError = "error",
                lastResult = "result",
                lastLog = "",
                customOrder = 1,
            )
        val beta =
            AutoTaskRule(
                "b",
                "Beta",
                true,
                "*/30 * * * *",
                loginUrl = "https://login.invalid",
                customOrder = 0,
                lastResult = "result",
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(alpha, beta) }
        val rows = repository.observe().first()
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertTrue(rows.first().hasLogin)
        assertFalse(rows.last().hasLogin)
        assertFalse(rows.last().enabled)
        assertEquals("0 * * * * | error", rows.last().summary)
        assertEquals("", rows.last().log)
        assertEquals("result", rows.first().log)
        val updates = Channel<List<AutoTaskListItem>>(Channel.UNLIMITED)
        val job = launch { repository.observe().collect { updates.send(it) } }
        try {
            withTimeout(10000) { updates.receive() }
            withContext(Dispatchers.IO) {
                database.autoTaskRuleDao.updateRunState(
                    "b",
                    4567,
                    "fresh result",
                    null,
                    "fresh log",
                )
            }
            val current =
                withTimeout(10000) {
                    var rows = updates.receive()
                    while (rows.first().log != "fresh log") rows = updates.receive()
                    rows
                }
            assertEquals("fresh log", current.first().log)
        } finally {
            job.cancelAndJoin()
            updates.close()
        }
    }

    @Test
    fun enabledAndCronBatchesOver900PreserveConcurrentEditsAndRunStateAndRefreshOnce() =
        runBlocking {
            val rules =
                (0..900).map {
                    AutoTaskRule(
                        "task-$it",
                        "Task $it",
                        customOrder = it,
                        script = "script $it",
                        lastRunAt = 5,
                        lastResult = "result",
                        lastError = "error",
                        lastLog = "log",
                    )
                }
            withContext(Dispatchers.IO) {
                database.autoTaskRuleDao.upsert(*rules.toTypedArray())
                database.autoTaskRuleDao.upsert(
                    rules
                        .first()
                        .copy(
                            name = "Edited elsewhere",
                            script = "new script",
                            loginCheckJs = "login metadata",
                            lastLog = "new log",
                        )
                )
            }
            repository.enabled(rules.map { it.id }, false)
            assertEquals(1, refreshes)
            repository.cron(rules.map { it.id }, "0 * * * *")
            assertEquals(2, refreshes)
            val current = withContext(Dispatchers.IO) { database.autoTaskRuleDao.all() }
            assertEquals(901, current.size)
            assertTrue(current.all { !it.enable && it.cron == "0 * * * *" })
            assertEquals("Edited elsewhere", current.first().name)
            assertEquals("new script", current.first().script)
            assertEquals("login metadata", current.first().loginCheckJs)
            assertEquals("new log", current.first().lastLog)
            assertEquals(5L, current.first().lastRunAt)
            assertEquals("result", current.first().lastResult)
            repository.enabled(listOf("deleted"), true)
            assertEquals(2, refreshes)
            assertNull(withContext(Dispatchers.IO) { database.autoTaskRuleDao.getById("deleted") })
        }

    @Test
    fun reorderMergesFilteredSlotsAndDoesNotOverwriteHiddenTasksOrRuntimeMetadata() = runBlocking {
        val rules =
            (0..4).map {
                AutoTaskRule(
                    "$it",
                    "Task $it",
                    customOrder = it,
                    lastLog = "log $it",
                    script = "script $it",
                )
            }
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(*rules.toTypedArray()) }
        repository.reorder(listOf("4", "0", "2", "gone", "4"))
        val current = withContext(Dispatchers.IO) { database.autoTaskRuleDao.all() }
        assertEquals(listOf("4", "1", "0", "3", "2"), current.map { it.id })
        assertEquals("log 4", current.first().lastLog)
        assertEquals("script 4", current.first().script)
        assertEquals(1, refreshes)
        repository.reorder(listOf("4", "0", "2"))
        assertEquals(1, refreshes)
    }

    @Test
    fun deletingAndClearingLogsCannotRecreateMissingTasksAndDoNotEraseOtherFields() = runBlocking {
        val rule =
            AutoTaskRule(
                "a",
                "Task",
                lastRunAt = 12,
                lastResult = "result",
                lastError = "error",
                lastLog = "log",
                header = "header",
                jsLib = "library",
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(rule) }
        repository.clearLog("a")
        val current = withContext(Dispatchers.IO) { database.autoTaskRuleDao.getById("a")!! }
        assertEquals(rule.copy(lastResult = null, lastError = null, lastLog = null), current)
        assertEquals(0, refreshes)
        repository.delete(listOf("a", "missing", "a"))
        assertEquals(1, refreshes)
        repository.cron(listOf("a"), "new")
        repository.clearLog("a")
        assertEquals(1, refreshes)
        assertNull(withContext(Dispatchers.IO) { database.autoTaskRuleDao.getById("a") })
    }

    @Test
    fun exportUsesFreshFullMetadataAndRemovesOnlyOrderingAndRuntimeFields() = runBlocking {
        val rule =
            AutoTaskRule(
                "a",
                "Task",
                cron = "0 * * * *",
                script = "large".repeat(10000),
                header = "headers",
                jsLib = "library",
                loginUi = "login form",
                loginCheckJs = "check",
                loginUrl = "https://login.invalid",
                concurrentRate = "3",
                enabledCookieJar = false,
                comment = "comment",
                customOrder = 9,
                lastRunAt = 44,
                lastLog = "private log",
                lastResult = "result",
                lastError = "error",
            )
        withContext(Dispatchers.IO) {
            database.autoTaskRuleDao.upsert(rule, AutoTaskRule("b", "Other"))
        }
        val ticket = repository.export(listOf("a", "missing"))
        assertEquals("exportAutoTaskSelection.json", ticket.name)
        val text = instance().exportText(ticket)
        val objects = GSON.fromJsonArray<com.google.gson.JsonObject>(text).getOrThrow()
        assertEquals(1, objects.size)
        val json = objects.single()
        listOf("customOrder", "lastRunAt", "lastResult", "lastError", "lastLog").forEach {
            assertFalse(json.has(it))
        }
        assertEquals(rule.script, json.get("script").asString)
        assertEquals("library", json.get("jsLib").asString)
        assertEquals("headers", json.get("header").asString)
        assertEquals("check", json.get("loginCheckJs").asString)
        assertEquals("login form", json.get("loginUi").asString)
        assertFalse(json.get("enabledCookieJar").asBoolean)
        assertEquals("comment", json.get("comment").asString)
        repository.releaseExport(ticket)
        assertTrue(runCatching { repository.exportText(ticket) }.isFailure)
    }

    @Test
    fun onlineHistoryAndLargeAtomicDraftRestoreAcrossInstancesAndRejectOlderWrites() = runBlocking {
        repository.remember("not a url")
        repository.remember("https://new.invalid")
        repository.remember("https://new.invalid")
        assertEquals(listOf("https://new.invalid", "https://old.invalid"), repository.history())
        repository.removeHistory("https://old.invalid")
        assertEquals(listOf("https://new.invalid"), repository.history())
        val session = UUID.randomUUID().toString()
        val draft = AutoTaskOnlineDraft("large script".repeat(100000), 5)
        repository.writeDraft(session, draft)
        val restored = instance()
        assertEquals(draft, restored.readDraft(session))
        restored.writeDraft(session, AutoTaskOnlineDraft("older", 4))
        assertEquals(draft, restored.readDraft(session))
        assertTrue(runCatching { restored.readDraft("../not-a-session") }.isFailure)
        val notice = repository.exportNotice("content://export/result")
        assertEquals("", notice.summary)
        assertNull(notice.passphrase)
    }
}
