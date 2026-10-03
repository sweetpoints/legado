package io.legado.app.ui.book.source.edit

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookSourceEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private val invalidated = mutableListOf<Pair<String?, String>>()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "book-editor-test-${UUID.randomUUID()}")
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repository(
        beforeJournal: suspend () -> Unit = {},
        beforeDraft: suspend () -> Unit = {},
    ) =
        RoomBookSourceEditorRepository(
            context,
            database,
            directory,
            now = { 100L },
            beforeJournalWrite = beforeJournal,
            beforeDraftWrite = beforeDraft,
            invalidate = { previous, source ->
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                invalidated += previous?.bookSourceUrl to source.bookSourceUrl
            },
        )

    private suspend fun insert(source: BookSource) =
        withContext(Dispatchers.IO) {
            database.bookSourceDao.insert(source)
        }

    private suspend fun source(key: String): BookSource? =
        withContext(Dispatchers.IO) {
            database.bookSourceDao.getBookSource(key)
        }

    @Test
    fun renamePreservesFreshMetadataAndAllFieldsWithoutMutatingSnapshots() = runBlocking {
        val repository = repository()
        insert(BookSource("old", "name", customOrder = 3, lastUpdateTime = 9))
        val initial = repository.load("old")
        insert(BookSource("old", "name", customOrder = 27, lastUpdateTime = 99))
        val updated =
            initial.copy(
                form =
                    initial.form
                        .updateField(0, "bookSourceUrl", "new", 1, 2)
                        .updateField(1, "bookList", ".books", 2, 5),
                autoComplete = true,
            )
        val sessionId = UUID.randomUUID().toString()
        val saved = repository.save(sessionId, updated, BookSourceSaveAction.DEBUG)
        assertNull(source("old"))
        assertEquals(27, source("new")!!.customOrder)
        assertEquals(100L, source("new")!!.lastUpdateTime)
        assertEquals(".books", source("new")!!.ruleSearch!!.bookList)
        assertEquals(2, saved.form.field(1, "bookList")!!.selectionStart)
        assertEquals(5, saved.form.field(1, "bookList")!!.selectionEnd)
        assertEquals(saved, repository().readDraft(sessionId))
        assertEquals(listOf("old" to "new"), invalidated)
        assertFalse(saved.dirty())
    }

    @Test
    fun acceptedRoomWriteRecoversSameReceiptWithoutRepeatingPlan() = runBlocking {
        insert(BookSource("old", "name"))
        var failReceipt = true
        val repository = repository(beforeDraft = { if (failReceipt) error("disk failed") })
        val original = repository.load("old")
        val sessionId = UUID.randomUUID().toString()
        val edited = original.copy(form = original.form.updateField(0, "bookSourceName", "changed"))
        assertTrue(
            runCatching { repository.save(sessionId, edited, BookSourceSaveAction.LOGIN) }.isFailure
        )
        assertEquals("changed", source("old")!!.bookSourceName)
        assertTrue(File(directory, "$sessionId-save.json").exists())
        failReceipt = false
        val recovered = repository.readDraft(sessionId)!!
        assertEquals(BookSourceSaveAction.LOGIN, recovered.delivery!!.action)
        assertEquals(100L, recovered.original().lastUpdateTime)
        assertFalse(File(directory, "$sessionId-save.json").exists())
        assertEquals(recovered, repository.readDraft(sessionId))
    }

    @Test
    fun recoveryCannotOverwriteConcurrentChangeAfterAcceptedWrite() = runBlocking {
        insert(BookSource("old", "name"))
        val repository = repository(beforeDraft = { error("disk failed") })
        val original = repository.load("old")
        val sessionId = UUID.randomUUID().toString()
        assertTrue(
            runCatching { repository.save(sessionId, original, BookSourceSaveAction.DEBUG) }
                .isFailure
        )
        insert(source("old")!!.copy(bookSourceName = "external"))
        assertTrue(runCatching { repository().readDraft(sessionId) }.isFailure)
        assertEquals("external", source("old")!!.bookSourceName)
        assertTrue(File(directory, "$sessionId-save.json").exists())
    }

    @Test
    fun cancellationBeforeJournalDoesNotAcceptRoomWrite() = runBlocking {
        insert(BookSource("old", "name"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repository =
            repository(
                beforeJournal = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        val original = repository.load("old")
        val sessionId = UUID.randomUUID().toString()
        val operation = async {
            repository.save(sessionId, original, BookSourceSaveAction.FINISH)
        }
        entered.await()
        operation.cancel()
        operation.join()
        assertEquals("name", source("old")!!.bookSourceName)
        assertEquals(0L, source("old")!!.lastUpdateTime)
        assertFalse(File(directory, "$sessionId-save.json").exists())
    }

    @Test
    fun cancellationAfterRoomAcceptStillPersistsPrivateReceipt() = runBlocking {
        insert(BookSource("old", "name"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repository =
            repository(
                beforeDraft = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        val original = repository.load("old")
        val sessionId = UUID.randomUUID().toString()
        val operation =
            async(Dispatchers.IO) {
                repository.save(sessionId, original, BookSourceSaveAction.VARIABLE)
            }
        entered.await()
        operation.cancel()
        gate.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(
            BookSourceSaveAction.VARIABLE,
            repository().readDraft(sessionId)!!.delivery!!.action,
        )
        assertFalse(File(directory, "$sessionId-save.json").exists())
    }

    @Test
    fun actualAtomicBackupAndStrictRevisionRetainAcceptedDraftAndClose() = runBlocking {
        val repository = repository()
        val sessionId = UUID.randomUUID().toString()
        val original = repository.load(null).copy(revision = 4)
        repository.writeDraft(sessionId, original)
        repository.writeDraft(sessionId, original.copy(focusedKey = "other"))
        assertEquals(original, repository.readDraft(sessionId))
        withContext(Dispatchers.IO) {
            AtomicFile(File(directory, "$sessionId.json")).startWrite().use {
                it.write("partial".toByteArray())
            }
        }
        assertEquals(original, repository.readDraft(sessionId))
        val closed = original.copy(finished = true, revision = 5)
        repository.writeDraft(sessionId, closed)
        repository.writeDraft(sessionId, original.copy(revision = 99))
        assertEquals(closed, repository.readDraft(sessionId))
    }

    @Test
    fun parserRetainsFirstArrayItemAndExportPreservesCompleteMetadata() = runBlocking {
        val repository = repository()
        val source = BookSource("foreign", "name", customOrder = 37, lastUpdateTime = 91)
        val objectJson = GSON.toJson(source)
        assertEquals(objectJson, repository.parse(objectJson))
        assertEquals(
            objectJson,
            repository.parse(GSON.toJson(listOf(source, source.copy(bookSourceUrl = "ignored")))),
        )
        val document = BookSourceEditDocument.from(source)
        val exported = repository.export(document)
        assertTrue(exported.contains("\"customOrder\":37"))
        assertTrue(exported.contains("\"lastUpdateTime\":91"))
        assertTrue(runCatching { repository.parse("invalid") }.isFailure)
        assertTrue(runCatching { repository.readDraft("../outside") }.isFailure)
    }
}
