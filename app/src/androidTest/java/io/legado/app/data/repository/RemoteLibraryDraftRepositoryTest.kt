package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.remote.RemoteLibraryConfirmation
import io.legado.app.model.remote.RemoteLibraryDraft
import io.legado.app.model.remote.RemoteLibraryEffect
import io.legado.app.model.remote.RemoteLibraryEntry
import io.legado.app.model.remote.RemoteLibraryPrompt
import io.legado.app.model.remote.RemoteLibraryReceipt
import io.legado.app.model.remote.RemoteLibraryTask
import io.legado.app.model.remote.RemoteLibraryTaskKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLibraryDraftRepositoryTest {
    @Test
    fun actualPrivateAtomicDraftRestoresLargeUrlsAndTaskMetadataRejectsStaleWritersAndClosedOwners() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = FileRemoteLibraryDraftRepository(context)
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "remote-library-drafts")
            val large = "https://example.invalid/" + "large".repeat(250000)
            val row = RemoteLibraryEntry(large, "archive.zip", large, 123, 456, "zip", true)
            try {
                repository.open(owner)
                repository.open(other)
                val draft =
                    RemoteLibraryDraft(
                        revision = 999,
                        query = large,
                        directories = listOf(row.copy(type = "folder")),
                        rows = listOf(row),
                        selected = listOf(large),
                        confirmation =
                            RemoteLibraryConfirmation(
                                RemoteLibraryPrompt.ChooseArchive,
                                uri = large,
                                names = listOf("inside/a.txt"),
                            ),
                        task =
                            RemoteLibraryTask(
                                "accepted",
                                RemoteLibraryTaskKind.ImportBooks,
                                listOf(large),
                                readAfter = large,
                                completed = listOf(large),
                            ),
                        effects =
                            listOf(
                                RemoteLibraryReceipt(
                                    "effect",
                                    RemoteLibraryEffect.OpenBook,
                                    bookId = large,
                                )
                            ),
                        storageTicket = "small-ticket",
                    )
                repository.write(owner, draft)
                repository.write(owner, draft.copy(revision = 1, query = "stale"))
                val reattached = FileRemoteLibraryDraftRepository(context)
                assertEquals(draft, reattached.open(owner))
                val file = File(directory, "$owner.json")
                assertTrue(file.renameTo(File(directory, "$owner.json.bak")))
                assertEquals(draft, reattached.open(owner))
                reattached.release(owner)
                assertFalse(file.exists())
                assertTrue(File(directory, "$owner.json.closed").exists())
                assertTrue(File(directory, "$other.json").exists())
                try {
                    repository.write(owner, draft.copy(revision = 1000))
                    fail("released writer must be rejected")
                } catch (_: IllegalStateException) {}
                try {
                    repository.open(owner)
                    fail("released owner must not be resurrected")
                } catch (_: IllegalStateException) {}
            } finally {
                repository.release(other)
                listOf(owner, other).forEach { id ->
                    listOf(
                            "json",
                            "json.bak",
                            "json.new",
                            "json.closed",
                            "json.closed.bak",
                            "json.closed.new",
                        )
                        .forEach { suffix -> File(directory, "$id.$suffix").delete() }
                }
            }
        }
}
