package io.legado.app.data.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.commitBookshelfImport
import io.legado.app.data.repository.importBookshelfJson
import io.legado.app.data.repository.parseBookshelfImport
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationBookshelfImportRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun canceledAfterRealRoomAcceptanceRestoresReceiptWithoutSearchingOrSavingAgain() =
        runBlocking {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val returnGate = CompletableDeferred<Unit>()
            val engine =
                Engine(fixture.book).apply {
                    afterAccepted = {
                        entered.complete(Unit)
                        returnGate.await()
                    }
                }
            val repository = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            var delivered = false
            val delivery =
                launch(Dispatchers.Main) {
                    repository.import(fixture.ticket, fixture.token, fixture.source)
                    delivered = true
                }
            try {
                withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
                delivery.cancel()
                returnGate.complete(Unit)
                delivery.join()
                assertFalse(delivered)
                withContext(Dispatchers.IO) {
                    assertEquals(fixture.book, appDb.bookDao.getBook(fixture.book.bookUrl))
                    appDb.bookDao.insert(
                        fixture.book.copy(durChapterPos = 99, customCoverUrl = "external cover")
                    )
                }
                val restored =
                    AssociationBookshelfImportRepository(context, fixture.sessions, engine)
                assertEquals(1, restored.import(fixture.ticket, fixture.token, fixture.source))
                assertEquals(1, engine.importCalls)
                withContext(Dispatchers.IO) {
                    val latest = checkNotNull(appDb.bookDao.getBook(fixture.book.bookUrl))
                    assertEquals(99, latest.durChapterPos)
                    assertEquals("external cover", latest.customCoverUrl)
                }
            } finally {
                returnGate.complete(Unit)
                delivery.cancelAndJoin()
                fixture.close()
            }
        }

    @Test
    fun acceptedThenExternallyDeletedBookIsNeverRecreatedOnRestoredExecution() = runBlocking {
        val fixture = fixture()
        val engine = Engine(fixture.book)
        try {
            val repository = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            assertEquals(1, repository.import(fixture.ticket, fixture.token, fixture.source))
            withContext(Dispatchers.IO) { appDb.bookDao.delete(fixture.book) }
            val restored = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            assertTrue(
                runCatching { restored.import(fixture.ticket, fixture.token, fixture.source) }
                    .isFailure
            )
            assertEquals(1, engine.importCalls)
            withContext(Dispatchers.IO) { assertFalse(appDb.bookDao.has(fixture.book.bookUrl)) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun privateAcceptedWriteFailureRollsBackRealRoomAndKeepsPendingReceiptForRetry() = runBlocking {
        val fixture = fixture()
        val engine = Engine(fixture.book)
        try {
            val failing =
                AssociationBookshelfImportRepository(
                    context,
                    fixture.sessions,
                    engine,
                    beforeJournalWrite = { journal ->
                        if (journal.entries.any { it.accepted && !it.previouslyPresent })
                            throw IOException("accepted receipt unavailable")
                    },
                )
            assertTrue(
                runCatching { failing.import(fixture.ticket, fixture.token, fixture.source) }
                    .isFailure
            )
            withContext(Dispatchers.IO) { assertFalse(appDb.bookDao.has(fixture.book.bookUrl)) }
            val file =
                File(
                    File(fixture.directory, fixture.ticket),
                    "bookshelf-import-${fixture.token}.json",
                )
            val pending =
                GSON.fromJsonObject<AssociationBookshelfImportJournal>(file.readText()).getOrThrow()
            assertFalse(pending.entries.single().accepted)
            val restored = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            assertEquals(1, restored.import(fixture.ticket, fixture.token, fixture.source))
            assertEquals(2, engine.importCalls)
            withContext(Dispatchers.IO) {
                assertEquals(fixture.book, appDb.bookDao.getBook(fixture.book.bookUrl))
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelWhileWaitingForRemoteStageDoesNotCrossAcceptedRoomBoundary() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        val engine =
            Engine(fixture.book).apply {
                beforeMutation = {
                    entered.complete(Unit)
                    remoteGate.await()
                }
            }
        val repository = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
        val delivery =
            launch(Dispatchers.Main) {
                repository.import(fixture.ticket, fixture.token, fixture.source)
            }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            delivery.cancelAndJoin()
            withContext(Dispatchers.IO) { assertFalse(appDb.bookDao.has(fixture.book.bookUrl)) }
            remoteGate.complete(Unit)
            val restored = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            assertEquals(1, restored.import(fixture.ticket, fixture.token, fixture.source))
            assertEquals(2, engine.importCalls)
        } finally {
            remoteGate.complete(Unit)
            delivery.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun alreadyPresentBookKeepsLegacySkipAndProducesNoAcceptedMutationCallback() = runBlocking {
        val fixture = fixture()
        val engine = Engine(fixture.book)
        var callbackCalled = false
        try {
            withContext(Dispatchers.IO) {
                appDb.bookDao.insert(fixture.book)
                val json = File(checkNotNull(android.net.Uri.parse(fixture.source).path)).readText()
                importBookshelfJson(json, 0) { _, _, _ -> callbackCalled = true }
            }
            val repository = AssociationBookshelfImportRepository(context, fixture.sessions, engine)
            assertEquals(1, repository.import(fixture.ticket, fixture.token, fixture.source))
            assertEquals(0, engine.importCalls)
            assertFalse(callbackCalled)
            withContext(Dispatchers.IO) {
                assertEquals(fixture.book, appDb.bookDao.getBook(fixture.book.bookUrl))
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun fixture(): Fixture {
        val directory =
            File(context.cacheDir, "association-bookshelf-${UUID.randomUUID()}").apply { mkdirs() }
        val book =
            Book(
                bookUrl = "accepted-${UUID.randomUUID()}",
                name = "Book ${UUID.randomUUID()}",
                author = "Fixture author",
            )
        val sourceFile =
            File(directory, "incoming.json").apply {
                writeText(GSON.toJson(listOf(mapOf("name" to book.name, "author" to book.author))))
            }
        val source = android.net.Uri.fromFile(sourceFile).toString()
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.File, AssociationInputKind.View))
        val token = UUID.randomUUID().toString()
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(
                revision = 1,
                importType = "bookshelf",
                importSource = source,
                operation =
                    AssociationOperation(token, initial.generation, "data-import", accepted = true),
            ),
        )
        return Fixture(directory, sessions, ticket, token, source, book)
    }

    private class Engine(private val book: Book) : AssociationBookshelfImportEngine {
        var importCalls = 0
        var afterAccepted: suspend () -> Unit = {}
        var beforeMutation: suspend () -> Unit = {}

        override fun existing(name: String, author: String): Book? =
            appDb.bookDao.getBook(name, author)

        override fun current(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

        override suspend fun import(
            json: String,
            accepted: suspend (String, String, String) -> Unit,
        ) {
            importCalls++
            beforeMutation()
            for ((name, author) in parseBookshelfImport(json)) {
                commitBookshelfImport(book, name, author) { acceptedName, acceptedAuthor, url ->
                    accepted(acceptedName, acceptedAuthor, url)
                    afterAccepted()
                }
            }
        }
    }

    private data class Fixture(
        val directory: File,
        val sessions: FileAssociationSessionRepository,
        val ticket: String,
        val token: String,
        val source: String,
        val book: Book,
    ) {
        suspend fun close() {
            sessions.release(ticket)
            withContext(Dispatchers.IO) { appDb.bookDao.delete(book) }
            directory.deleteRecursively()
        }
    }
}
