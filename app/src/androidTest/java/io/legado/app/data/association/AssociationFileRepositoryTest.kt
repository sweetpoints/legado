package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationFileRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun readableOwnedProviderNeedsNoExplicitGrantButMissingProviderContentIsRejected() =
        runBlocking {
            val directory = File(context.cacheDir, "association-provider-${UUID.randomUUID()}")
            val sessions = FileAssociationSessionRepository(context, directory)
            val ticket =
                sessions.create(
                    AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedUri)
                )
            val incoming =
                File(context.cacheDir, "association-provider-input-${UUID.randomUUID()}.json")
            try {
                incoming.writeText("[{\"pattern\":\"needle\",\"replacement\":\"new\"}]")
                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileProvider",
                        incoming,
                    )
                val input =
                    AssociationInput(
                        AssociationHostKind.File,
                        AssociationInputKind.SharedUri,
                        listOf(uri.toString()),
                    )
                assertEquals(0, input.intentFlags)
                val repository = LocalAssociationFileRepository(context, sessions)
                assertEquals("replaceRule", repository.inspect(ticket, input).importType)
                assertTrue(incoming.delete())
                assertTrue(runCatching { repository.inspect(ticket, input) }.isFailure)
            } finally {
                sessions.release(ticket)
                incoming.delete()
                directory.deleteRecursively()
            }
        }

    @Test
    fun fileInspectionPreservesJsonBeforeExtensionAndUnsupportedConfirmation() = runBlocking {
        val directory = File(context.cacheDir, "association-files-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.File, AssociationInputKind.View))
        val incoming =
            File(context.cacheDir, "association-input-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val repository = LocalAssociationFileRepository(context, sessions)
            val jsonNamedBook =
                File(incoming, "replacement.txt").apply {
                    writeText("[{\"pattern\":\"needle\",\"replacement\":\"new\"}]")
                }
            val jsonInput =
                AssociationInput(
                    AssociationHostKind.File,
                    AssociationInputKind.View,
                    listOf(Uri.fromFile(jsonNamedBook).toString()),
                )
            val classified = repository.inspect(ticket, jsonInput)
            assertEquals("replaceRule", classified.importType)
            assertEquals(null, classified.staging)
            val unsupported = File(incoming, "unrecognized.bin").apply { writeText("unsupported") }
            val result =
                repository.inspect(
                    ticket,
                    jsonInput.copy(uris = listOf(Uri.fromFile(unsupported).toString())),
                )
            assertEquals(unsupported.name, result.unsupportedName)
            assertEquals(Uri.fromFile(unsupported).toString(), result.unsupportedUri)
            assertFalse(result.openSingleBook)
            sessions.release(ticket)
            assertTrue(jsonNamedBook.exists())
            assertTrue(unsupported.exists())
        } finally {
            sessions.release(ticket)
            directory.deleteRecursively()
            incoming.deleteRecursively()
        }
    }

    @Test
    fun sharedTextKeepsJsonPrivateAndSingleHttpUrlRoutesWithoutJsonParsing() = runBlocking {
        val directory = File(context.cacheDir, "association-files-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(
                AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedText)
            )
        try {
            val repository = LocalAssociationFileRepository(context, sessions)
            val json = "{\"bookSourceUrl\":\"https://example.test/source\"}"
            val input =
                AssociationInput(
                    AssociationHostKind.File,
                    AssociationInputKind.SharedText,
                    text = json,
                )
            val classified = repository.inspect(ticket, input)
            assertEquals("bookSource", classified.importType)
            val privateFile = File(checkNotNull(Uri.parse(classified.source).path))
            assertTrue(
                privateFile.canonicalPath.startsWith(
                    File(directory, ticket).canonicalPath + File.separator
                )
            )
            assertEquals(json, privateFile.readText())
            val online =
                repository.inspect(
                    ticket,
                    input.copy(text = "请导入（https://example.test/rules.json）。"),
                )
            val uri = Uri.parse(online.onlineUri)
            assertEquals("legado", uri.scheme)
            assertEquals("/auto", uri.path)
            assertEquals("https://example.test/rules.json", uri.getQueryParameter("src"))
            assertEquals(null, online.importType)
            assertTrue(
                runCatching {
                    repository.inspect(
                        ticket,
                        input.copy(
                            kind = AssociationInputKind.SharedUri,
                            uris = listOf("file:///tmp/not-a-provider"),
                        ),
                    )
                }
                    .isFailure
            )
            sessions.release(ticket)
            assertFalse(privateFile.exists())
        } finally {
            sessions.release(ticket)
            directory.deleteRecursively()
        }
    }
}
