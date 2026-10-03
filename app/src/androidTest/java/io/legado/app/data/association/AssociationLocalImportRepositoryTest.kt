package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationLocalImportRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun failedReceiptAfterRoomAcceptanceRestoresSameCopyAndPreservesFreshReaderMetadata() =
        runBlocking {
            val fixture = fixture()
            var importedUrl: String? = null
            try {
                val failing =
                    AssociationLocalImportRepository(
                        fixture.sessions,
                        beforeJournalWrite = { journal ->
                            if (journal.copies.any { it.imported })
                                throw IOException("receipt disk unavailable")
                        },
                    )
                assertTrue(
                    runCatching {
                        failing.import(fixture.ticket, fixture.token, fixture.destinationUri)
                    }
                        .isFailure
                )
                val copied = fixture.destination.listFiles()!!.single()
                importedUrl = copied.path
                withContext(Dispatchers.IO) {
                    val accepted = checkNotNull(appDb.bookDao.getBook(copied.path))
                    appDb.bookDao.insert(
                        accepted.copy(durChapterPos = 99, customCoverUrl = "fresh external cover")
                    )
                }
                val restored =
                    AssociationLocalImportRepository(fixture.sessions)
                        .import(fixture.ticket, fixture.token, fixture.destinationUri)
                val book = GSON.fromJsonObject<Book>(restored.bookJson.single()).getOrThrow()
                assertEquals(99, book.durChapterPos)
                assertEquals("fresh external cover", book.customCoverUrl)
                assertEquals(copied.path, book.bookUrl)
                assertEquals(1, fixture.destination.listFiles()!!.size)
                fixture.sessions.release(fixture.ticket)
                assertTrue(copied.exists())
                withContext(Dispatchers.IO) {
                    assertEquals(book, appDb.bookDao.getBook(copied.path))
                }
            } finally {
                importedUrl?.let { url ->
                    withContext(Dispatchers.IO) {
                        appDb.bookDao.getBook(url)?.let { book -> appDb.bookDao.delete(book) }
                    }
                }
                fixture.close()
            }
        }

    @Test
    fun failedCopyJournalRemovesOnlyNewDestinationAndNeverChangesExistingFile() = runBlocking {
        val fixture = fixture()
        val existing =
            File(fixture.destination, "source.txt").apply { writeText("existing content") }
        try {
            val failing =
                AssociationLocalImportRepository(
                    fixture.sessions,
                    beforeJournalWrite = { journal ->
                        if (journal.copies.isNotEmpty())
                            throw IOException("copy receipt unavailable")
                    },
                )
            assertTrue(
                runCatching {
                    failing.import(fixture.ticket, fixture.token, fixture.destinationUri)
                }
                    .isFailure
            )
            assertEquals(listOf(existing.name), fixture.destination.listFiles()!!.map { it.name })
            assertEquals("existing content", existing.readText())
            assertTrue(File(fixture.root, "input/source.txt").exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completedImportDoesNotRecreateAnExternallyDeletedBookOnRestoredExecution() = runBlocking {
        val fixture = fixture()
        try {
            val repository = AssociationLocalImportRepository(fixture.sessions)
            val imported = repository.import(fixture.ticket, fixture.token, fixture.destinationUri)
            val book = GSON.fromJsonObject<Book>(imported.bookJson.single()).getOrThrow()
            withContext(Dispatchers.IO) { appDb.bookDao.delete(book) }
            assertTrue(
                runCatching {
                    repository.import(fixture.ticket, fixture.token, fixture.destinationUri)
                }
                    .isFailure
            )
            withContext(Dispatchers.IO) { assertFalse(appDb.bookDao.has(book.bookUrl)) }
            assertEquals(1, fixture.destination.listFiles()!!.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun acceptedReceiptFailureStopsBatchBeforeFurtherImportsUntilRecovery() = runBlocking {
        val fixture = fixture()
        val accepted = linkedMapOf<String, Book>()
        var importCalls = 0
        val engine =
            object : AssociationBookImportEngine {
                override fun current(bookUrl: String): Book? = accepted[bookUrl]

                override fun import(uri: Uri, preview: Book): Book {
                    importCalls++
                    accepted[preview.bookUrl] = preview
                    return preview
                }
            }
        try {
            val original = fixture.sessions.read(fixture.ticket)
            val secondFile =
                File(fixture.root, "input/second.txt").apply {
                    writeText("第二本正文")
                }
            val secondBook = Book(bookUrl = secondFile.path, name = "Second fixture")
            val second =
                AssociationBookPreview(
                    "second",
                    Uri.fromFile(secondFile).toString(),
                    secondFile.name,
                    GSON.toJson(secondBook),
                )
            fixture.sessions.write(
                fixture.ticket,
                original.copy(
                    revision = original.revision + 1,
                    previews = original.previews + second,
                    selectedIds = original.selectedIds + second.id,
                ),
            )
            val failing =
                AssociationLocalImportRepository(
                    fixture.sessions,
                    engine,
                    beforeJournalWrite = { journal ->
                        if (journal.copies.any { it.imported })
                            throw IOException("accepted receipt unavailable")
                    },
                )
            assertTrue(
                runCatching {
                    failing.import(fixture.ticket, fixture.token, fixture.destinationUri)
                }
                    .isFailure
            )
            assertEquals(1, importCalls)
            assertEquals(1, accepted.size)
            val restored =
                AssociationLocalImportRepository(fixture.sessions, engine)
                    .import(fixture.ticket, fixture.token, fixture.destinationUri)
            assertEquals(2, restored.bookJson.size)
            assertEquals(2, importCalls)
            assertEquals(2, fixture.destination.listFiles()!!.size)
        } finally {
            fixture.close()
        }
    }

    private suspend fun fixture(): Fixture {
        val root =
            File(context.cacheDir, "association-import-${UUID.randomUUID()}").apply { mkdirs() }
        val source =
            File(root, "input/source.txt").apply {
                parentFile!!.mkdirs()
                writeText("第一章\n测试正文\n")
            }
        val destination = File(root, "destination").apply { mkdirs() }
        val sessions = FileAssociationSessionRepository(context, File(root, "sessions"))
        val ticket =
            sessions.create(
                AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedUri)
            )
        val token = UUID.randomUUID().toString()
        val book =
            withContext(Dispatchers.IO) {
                LocalBook.previewImportFile(Uri.fromFile(source))
                    .copy(
                        name = "Test ${UUID.randomUUID()}",
                        customCoverUrl = "initial cover",
                        durChapterPos = 17,
                    )
            }
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(
                revision = 1,
                previews =
                    listOf(
                        AssociationBookPreview(
                            "preview",
                            Uri.fromFile(source).toString(),
                            source.name,
                            GSON.toJson(book),
                        )
                    ),
                selectedIds = listOf("preview"),
                operation =
                    AssociationOperation(
                        token,
                        initial.generation,
                        "local-import",
                        accepted = true,
                    ),
            ),
        )
        return Fixture(root, destination, sessions, ticket, token)
    }

    private data class Fixture(
        val root: File,
        val destination: File,
        val sessions: FileAssociationSessionRepository,
        val ticket: String,
        val token: String,
    ) {
        val destinationUri: String
            get() = Uri.fromFile(destination).toString()

        suspend fun close() {
            sessions.release(ticket)
            root.deleteRecursively()
        }
    }
}
