package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.utils.FileDoc
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationLocalBooksRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun copySkipsExistingDestinationAndDatabaseIdentityWithoutOverwritingEither() = runBlocking {
        withContext(Dispatchers.IO) {
            val directory =
                File(context.cacheDir, "association-copy-${UUID.randomUUID()}").apply { mkdirs() }
            val source =
                File(directory, "source/shared.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("incoming content")
                }
            val destination = File(directory, "destination").apply { mkdirs() }
            val original = File(destination, "shared.txt").apply { writeText("existing content") }
            val recordedFile = File(destination, "shared (2).txt")
            val recordedBook =
                Book(
                    bookUrl = recordedFile.path,
                    name = "Name ${UUID.randomUUID()}",
                    customCoverUrl = "preserved-cover",
                    durChapterPos = 41,
                )
            appDb.bookDao.insert(recordedBook)
            try {
                val copied =
                    copyAssociationLocalBook(FileDoc.fromFile(source), Uri.fromFile(destination))
                assertEquals("shared (3).txt", File(checkNotNull(copied.path)).name)
                assertEquals("incoming content", File(checkNotNull(copied.path)).readText())
                assertEquals("existing content", original.readText())
                assertFalse(recordedFile.exists())
                assertEquals(recordedBook, appDb.bookDao.getBook(recordedBook.bookUrl))
                assertEquals("incoming content", source.readText())
            } finally {
                appDb.bookDao.delete(recordedBook)
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun archiveRuleBatchUsesOwnedMergedPayloadAndMixedBooksRequireConfirmationError() =
        runBlocking {
            val directory =
                File(context.cacheDir, "association-local-${UUID.randomUUID()}").apply { mkdirs() }
            val sessions = FileAssociationSessionRepository(context, File(directory, "sessions"))
            val ticket =
                sessions.create(
                    AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedUris)
                )
            val repository = AssociationLocalBooksRepository(sessions)
            fun archive(name: String, entries: Map<String, String>): File =
                File(directory, name).also { file ->
                    ZipOutputStream(file.outputStream()).use { output ->
                        for ((path, body) in entries) {
                            output.putNextEntry(ZipEntry(path))
                            output.write(body.toByteArray())
                            output.closeEntry()
                        }
                    }
                }
            try {
                val rules =
                    archive(
                        "rules.zip",
                        mapOf(
                            "first.json" to "[{\"pattern\":\"first\",\"replacement\":\"one\"}]",
                            "second.json" to "[{\"pattern\":\"second\",\"replacement\":\"two\"}]",
                        ),
                    )
                val merged = repository.stage(ticket, listOf(Uri.fromFile(rules).toString()))
                assertEquals("replaceRule", merged.importType)
                val payload = File(checkNotNull(Uri.parse(merged.importSource).path))
                assertTrue(
                    payload.canonicalPath.startsWith(
                        File(directory, "sessions/$ticket").canonicalPath + File.separator
                    )
                )
                assertTrue(payload.readText().contains("first"))
                assertTrue(payload.readText().contains("second"))
                assertTrue(merged.previews.isEmpty())
                val mixed =
                    archive(
                        "mixed.zip",
                        mapOf(
                            "rule.json" to "[{\"pattern\":\"x\",\"replacement\":\"y\"}]",
                            "book.txt" to "Chapter one\nText",
                        ),
                    )
                assertTrue(
                    repository.stage(ticket, listOf(Uri.fromFile(mixed).toString())).mixedTypes
                )
                sessions.release(ticket)
                assertFalse(payload.exists())
                assertTrue(rules.exists())
                assertTrue(mixed.exists())
                assertTrue(
                    runCatching { repository.stage(ticket, listOf(Uri.fromFile(rules).toString())) }
                        .exceptionOrNull() is AssociationSessionClosed
                )
            } finally {
                sessions.release(ticket)
                directory.deleteRecursively()
            }
        }
}
