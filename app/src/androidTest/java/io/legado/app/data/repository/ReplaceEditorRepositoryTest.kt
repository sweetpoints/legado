package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReplaceEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private lateinit var repository: RoomReplaceEditorRepository
    private val sampleIds = mutableListOf<Long>()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "replace-editor-test-${UUID.randomUUID()}")
        repository = RoomReplaceEditorRepository(context, database, directory)
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
        sampleIds.forEach(ReplacePreviewConfig::removeSample)
    }

    @Test
    fun diskRoundTripKeepsLargeRawFieldsAndSelectionAndRejectsStaleRevision() = runBlocking {
        val session = UUID.randomUUID().toString()
        val draft =
            ReplaceEditorDraft(id = 51)
                .with(ReplaceEditorField.Pattern, ReplaceEditorText("raw ".repeat(30000), 42, 5))
        val current = ReplaceEditorDocument(draft, revision = 7)
        repository.write(session, current)
        repository.write(session, current.copy(revision = 2))
        assertEquals(current, repository.read(session))
        assertTrue(withContext(Dispatchers.IO) { database.replaceRuleDao.all.isEmpty() })
        assertTrue(runCatching { repository.save(session, current.copy(revision = 1)) }.isFailure)
    }

    @Test
    fun realRoomSavePreservesMetadataAndOneReceiptCannotOverwriteLaterRule() = runBlocking {
        val id = System.currentTimeMillis()
        sampleIds += id
        val rule =
            ReplaceRule(
                id = id,
                name = "Original",
                group = " Group ",
                pattern = "old",
                replacement = "new",
                scope = " Book ",
                scopeTitle = true,
                scopeSource = true,
                scopeContent = false,
                excludeScope = " Except ",
                isEnabled = false,
                isRegex = false,
                timeoutMillisecond = 12,
                order = 7,
            )
        withContext(Dispatchers.IO) { database.replaceRuleDao.insert(rule) }
        ReplacePreviewConfig.saveSample(id, "old old")
        val loaded = repository.load(ReplaceEditorRequest(id))
        assertEquals("old old", loaded[ReplaceEditorField.Sample].text)
        val edited =
            loaded
                .with(ReplaceEditorField.Name, ReplaceEditorText(" Edited "))
                .with(ReplaceEditorField.Replacement, ReplaceEditorText(" raw "))
        val session = UUID.randomUUID().toString()
        val document = ReplaceEditorDocument(edited, loaded, 1)
        repository.write(session, document)
        val result = repository.save(session, document)
        assertNotNull(result.receipt)
        assertEquals(id, result.draft.id)
        assertEquals(7, result.draft.order)
        withContext(Dispatchers.IO) {
            val actual = database.replaceRuleDao.findById(id)!!
            assertEquals(" Edited ", actual.name)
            assertEquals(" raw ", actual.replacement)
            assertEquals(" Group ", actual.group)
            assertFalse(actual.isEnabled)
            assertTrue(actual.scopeTitle)
            assertTrue(actual.scopeSource)
            assertFalse(actual.scopeContent)
            assertEquals(" Book ", actual.scope)
            assertEquals(" Except ", actual.excludeScope)
            assertEquals(12L, actual.timeoutMillisecond)
            database.replaceRuleDao.insert(actual.copy(name = "Later"))
        }
        repository.write(session, document.copy(revision = 999))
        assertEquals(result, repository.save(session, document))
        assertEquals(
            "Later",
            withContext(Dispatchers.IO) { database.replaceRuleDao.findById(id)!!.name },
        )
    }

    @Test
    fun newRuleAssignsOrderAndExportPasteKeepsExactPreviewJsonContract() = runBlocking {
        withContext(Dispatchers.IO) {
            database.replaceRuleDao.insert(
                ReplaceRule(id = 91, name = "Existing", pattern = "x", order = 30)
            )
        }
        val loaded =
            repository.load(ReplaceEditorRequest(pattern = "a", regex = false, scope = "Book"))
        sampleIds += loaded.id
        val draft = loaded.with(ReplaceEditorField.Sample, ReplaceEditorText("aaa"))
        val session = UUID.randomUUID().toString()
        val document = ReplaceEditorDocument(draft)
        val saved = repository.save(session, document)
        assertEquals(31, saved.draft.order)
        assertEquals("aaa", ReplacePreviewConfig.sample(saved.draft.id))
        val json = repository.export(saved.draft)
        val exported = GSON.fromJsonObject<ReplaceRule>(json).getOrThrow()
        assertEquals("aaa", exported.previewText)
        assertEquals("Book", exported.scope)
        val parsed = repository.parse(json, loaded.id)
        assertEquals("aaa", parsed[ReplaceEditorField.Sample].text)
        assertEquals("a", parsed[ReplaceEditorField.Pattern].text)
        assertFalse(parsed.regex)
        assertEquals("", repository.preview(parsed))
    }

    @Test
    fun invalidRegexCannotWriteRoomAndInitialDiskFailureCanRetrySameSession() = runBlocking {
        val session = UUID.randomUUID().toString()
        val draft =
            ReplaceEditorDraft(regex = true)
                .with(ReplaceEditorField.Pattern, ReplaceEditorText("["))
        assertTrue(runCatching { repository.save(session, ReplaceEditorDocument(draft)) }.isFailure)
        assertTrue(withContext(Dispatchers.IO) { database.replaceRuleDao.all.isEmpty() })
        directory.writeText("Blocks directory creation")
        val valid = ReplaceEditorDocument(draft.copy(regex = false))
        assertTrue(runCatching { repository.write(session, valid) }.isFailure)
        assertTrue(directory.delete())
        repository.write(session, valid)
        assertEquals(valid, repository.read(session))
    }

    @Test
    fun journalFailureLeavesRoomUntouchedAndRetryCreatesOneFixedReceipt() = runBlocking {
        var fail = true
        val repo =
            RoomReplaceEditorRepository(
                context,
                database,
                directory,
                failureHook = { stage ->
                    if (fail && stage == "beforeJournal") error("journal failed")
                },
            )
        val session = UUID.randomUUID().toString()
        val draft =
            ReplaceEditorDraft(regex = false)
                .with(ReplaceEditorField.Pattern, ReplaceEditorText("a"))
        sampleIds += draft.id
        val document = ReplaceEditorDocument(draft)
        assertTrue(runCatching { repo.save(session, document) }.isFailure)
        assertTrue(withContext(Dispatchers.IO) { database.replaceRuleDao.all.isEmpty() })
        fail = false
        val saved = repo.save(session, document)
        assertNotNull(saved.receipt)
        assertEquals(saved.receipt, repo.read(session)!!.receipt)
        assertEquals(1, withContext(Dispatchers.IO) { database.replaceRuleDao.all.size })
    }

    @Test
    fun postDatabaseAndSampleAndDraftFailuresRecoverSameReceiptAndNeverAllocateNewOrder() =
        runBlocking {
            for ((index, stage) in
                listOf("afterDatabase", "beforeSample", "beforeDraft").withIndex()) {
                val localDirectory = File(directory, stage)
                var fail = true
                val repo =
                    RoomReplaceEditorRepository(
                        context,
                        database,
                        localDirectory,
                        failureHook = { current ->
                            if (fail && stage == current) error("injected $stage")
                        },
                    )
                val session = UUID.randomUUID().toString()
                val draft =
                    ReplaceEditorDraft(id = 62000L + index, regex = false)
                        .with(ReplaceEditorField.Pattern, ReplaceEditorText("a"))
                        .with(ReplaceEditorField.Sample, ReplaceEditorText(stage))
                sampleIds += draft.id
                val document = ReplaceEditorDocument(draft)
                assertTrue(runCatching { repo.save(session, document) }.isFailure)
                val journal =
                    com.google.gson.JsonParser.parseString(
                            File(localDirectory, "$session.save.json").readText()
                        )
                        .asJsonObject
                val receipt = journal.getAsJsonObject("document").get("receipt").asString
                val persistedOrder =
                    withContext(Dispatchers.IO) {
                        database.replaceRuleDao.findById(draft.id)!!.order
                    }
                fail = false
                val recovered = repo.read(session)!!
                assertEquals(receipt, recovered.receipt)
                assertEquals(persistedOrder, recovered.draft.order)
                assertEquals(stage, ReplacePreviewConfig.sample(draft.id))
                assertFalse(File(localDirectory, "$session.save.json").exists())
                assertEquals(receipt, repo.save(session, document).receipt)
            }
        }

    @Test
    fun recoveryRefusesToOverwriteAnExternallyChangedRule() = runBlocking {
        var fail = true
        val repo =
            RoomReplaceEditorRepository(
                context,
                database,
                directory,
                failureHook = { stage -> if (fail && stage == "afterDatabase") error("stopped") },
            )
        val session = UUID.randomUUID().toString()
        val draft =
            ReplaceEditorDraft(regex = false)
                .with(ReplaceEditorField.Pattern, ReplaceEditorText("a"))
        sampleIds += draft.id
        assertTrue(runCatching { repo.save(session, ReplaceEditorDocument(draft)) }.isFailure)
        withContext(Dispatchers.IO) {
            val actual = database.replaceRuleDao.findById(draft.id)!!
            database.replaceRuleDao.insert(actual.copy(name = "External update"))
        }
        fail = false
        assertTrue(runCatching { repo.read(session) }.isFailure)
        assertEquals(
            "External update",
            withContext(Dispatchers.IO) { database.replaceRuleDao.findById(draft.id)!!.name },
        )
        assertTrue(File(directory, "$session.save.json").exists())
    }
}
