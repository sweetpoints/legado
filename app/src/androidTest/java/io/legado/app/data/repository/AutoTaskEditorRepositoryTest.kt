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
import org.junit.*
import org.junit.Assert.*

class AutoTaskEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private var refreshes = 0

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "task-editor-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo() =
        RoomAutoTaskEditorRepository(
            context,
            database,
            {},
            {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                refreshes++
            },
            directory,
        )

    private fun draft(name: String = "Task") =
        AutoTaskEditorDraft.from(AutoTaskRule(name = name, cron = "0 * * * *", script = "42"))

    @Test
    fun saveExistingPreservesFreshOrderAndRuntimeWhileUpdatingEveryEditableField() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        val initial =
            AutoTaskRule(
                "old",
                "Before",
                lastRunAt = 10,
                lastResult = "old result",
                customOrder = 4,
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(initial) }
        val loaded = repository.load("old")!!
        withContext(Dispatchers.IO) {
            database.autoTaskRuleDao.upsert(
                initial.copy(
                    customOrder = 17,
                    lastRunAt = 90,
                    lastLog = "fresh log",
                    lastError = "fresh error",
                )
            )
        }
        val edited =
            AutoTaskRule(
                "ignored",
                " Changed ",
                false,
                " 0 * * * * ",
                "login",
                "form",
                "check",
                " comment ",
                "@js:42",
                " header ",
                " lib ",
                " 3 ",
                false,
                999,
                999,
                "foreign",
                "foreign",
                "foreign",
            )
        val result =
            repository.save(
                session,
                AutoTaskEditorDocument("old", AutoTaskEditorDraft.from(edited), loaded, true),
                AutoTaskEditorSaveAction.Debug,
            )
        val row = withContext(Dispatchers.IO) { database.autoTaskRuleDao.getById("old")!! }
        assertEquals("old", row.id)
        assertEquals(17, row.customOrder)
        assertEquals(90L, row.lastRunAt)
        assertEquals("fresh log", row.lastLog)
        assertEquals("fresh error", row.lastError)
        assertEquals("Changed", row.name)
        assertFalse(row.enable)
        assertFalse(row.enabledCookieJar)
        assertEquals("0 * * * *", row.cron)
        assertEquals("comment", row.comment)
        assertEquals("@js:42", row.script)
        assertEquals("header", row.header)
        assertEquals("lib", row.jsLib)
        assertEquals("3", row.concurrentRate)
        assertEquals("login", row.loginUrl)
        assertEquals("form", row.loginUi)
        assertEquals("check", row.loginCheckJs)
        assertTrue(result.delivery!!.loginAvailable)
        assertEquals(result, repo().readDraft(session))
        assertEquals(1, refreshes)
    }

    @Test
    fun deletedExistingCannotBeResurrectedAndSaveFailureDoesNotQueueDelivery() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        val initial = AutoTaskRule("old", "Before", script = "42")
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(initial) }
        val loaded = repository.load("old")!!
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.deleteByIds(listOf("old")) }
        try {
            repository.save(
                session,
                AutoTaskEditorDocument("old", loaded, existing = true),
                AutoTaskEditorSaveAction.Close,
            )
            fail("must reject deleted task")
        } catch (_: IllegalStateException) {}
        withContext(Dispatchers.IO) { assertNull(database.autoTaskRuleDao.getById("old")) }
        assertNull(repository.readDraft(session))
        assertEquals(0, refreshes)
    }

    @Test
    fun newTaskUsesStableIdNextOrderAndFutureSavesProtectDeletedRow() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            database.autoTaskRuleDao.upsert(AutoTaskRule("other", "Other", customOrder = 20))
        }
        val first =
            repository.save(
                session,
                AutoTaskEditorDocument("new", draft()),
                AutoTaskEditorSaveAction.Debug,
            )
        val second =
            repository.save(
                session,
                first.copy(draft = draft("Second")),
                AutoTaskEditorSaveAction.Login,
            )
        assertEquals("new", second.id)
        assertTrue(second.existing)
        assertEquals(2, refreshes)
        withContext(Dispatchers.IO) {
            assertEquals(21, database.autoTaskRuleDao.getById("new")!!.customOrder)
            assertEquals(2, database.autoTaskRuleDao.all().size)
            database.autoTaskRuleDao.deleteByIds(listOf("new"))
        }
        try {
            repository.save(session, second, AutoTaskEditorSaveAction.Close)
            fail("must reject")
        } catch (_: IllegalStateException) {}
        assertEquals(2, refreshes)
    }

    @Test
    fun fullJsonObjectOrSingleArrayPasteRetainsEditableMetadataAndExportDropsRuntime() =
        runBlocking {
            val repository = repo()
            val rule =
                AutoTaskRule(
                    "foreign",
                    "Task",
                    false,
                    "0 * * * *",
                    "login",
                    "ui",
                    "check",
                    "comment",
                    "script",
                    "header",
                    "lib",
                    "2",
                    false,
                    15,
                    999,
                    "private",
                    "private",
                    "private",
                )
            val objectDraft = repository.parse(GSON.toJson(rule))!!
            assertEquals(objectDraft, repository.parse(GSON.toJson(listOf(rule))))
            assertNull(repository.parse(GSON.toJson(listOf(rule, rule))))
            assertNull(repository.parse("bad"))
            val json = repository.export("original", objectDraft)
            val copied = GSON.fromJsonArray<AutoTaskRule>(json).getOrThrow().single()
            assertEquals("original", copied.id)
            assertEquals("check", copied.loginCheckJs)
            assertEquals("header", copied.header)
            assertFalse(copied.enabledCookieJar)
            listOf("lastRunAt", "lastResult", "lastError", "lastLog", "customOrder").forEach {
                assertFalse(json.contains(it))
            }
        }

    @Test
    fun millionCharacterDraftCursorAndBaselineRestoreFromNewRepositoryAndOldWriteCannotOverwrite() =
        runBlocking {
            val repository = repo()
            val session = UUID.randomUUID().toString()
            val large =
                draft()
                    .with(
                        AutoTaskEditorField.LoginUrl,
                        AutoTaskEditorText("large".repeat(200000), 80000, 90000),
                    )
            val document = AutoTaskEditorDocument("id", large, draft(), true, 4)
            repository.writeDraft(session, document)
            repository.writeDraft(session, document.copy(draft = draft("old"), revision = 3))
            assertEquals(document, repo().readDraft(session))
            try {
                repository.readDraft("../outside")
                fail("invalid session")
            } catch (_: IllegalArgumentException) {}
        }

    @Test
    fun transferFileRoundTripUsesNativeProtocolAndOnlyOwnedPathsAreRemoved() = runBlocking {
        val repository = repo()
        val path = repository.editorInput("line\nbody".repeat(100000))
        val file = File(path)
        assertEquals(context.cacheDir.canonicalFile, file.parentFile.canonicalFile)
        assertTrue(file.name.startsWith("code-text-"))
        assertEquals("line\nbody".repeat(100000), repository.editorText(path))
        val foreign = File(directory, "foreign.txt")
        foreign.parentFile.mkdirs()
        foreign.writeText("foreign")
        repository.clearEditor(path, foreign.absolutePath)
        assertFalse(file.exists())
        assertTrue(foreign.exists())
    }
}
