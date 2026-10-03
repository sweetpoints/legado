package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.bookshelf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookshelfManagementDraftRepositoryTest {
    @Test
    fun largeSelectionAndPendingConfirmationRestorePrivatelyAndClosedOwnersRejectLateWrites() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = FileBookshelfManagementDraftRepository(context)
            val id = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "bookshelf-management-drafts")
            val file = File(directory, "$id.json")
            val selected = "https://synthetic-source/" + "long-identifier".repeat(100000)
            try {
                repository.open(id)
                repository.open(other)
                val receipt =
                    ShelfManagementReceipt(
                        "pending",
                        ShelfManagementEffect.UpdateToc,
                        ids = listOf(selected),
                    )
                val confirmation =
                    ShelfManagementConfirmation(
                        ShelfManagementAction.CreateTasks,
                        listOf(selected),
                        cron = "unfinished expression",
                        selectionStart = 2,
                        selectionEnd = 8,
                    )
                val draft =
                    BookshelfManagementDraft(
                        8,
                        4,
                        "filter text",
                        listOf(selected),
                        confirmation,
                        effects = listOf(receipt),
                    )
                repository.write(id, draft)
                repository.write(id, draft.copy(revision = 7, query = "stale"))
                assertEquals(draft, repository.open(id))
                assertTrue(file.length() > 1_000_000)
                val backup = File(file.path + ".bak")
                file.copyTo(backup, overwrite = true)
                file.writeText("interrupted primary")
                assertEquals(draft, repository.open(id))
                repository.release(id)
                assertFalse(file.exists())
                assertFalse(backup.exists())
                assertTrue(runCatching { repository.write(id, draft.copy(revision = 9)) }.isFailure)
                assertTrue(runCatching { repository.open(id) }.isFailure)
                assertEquals(BookshelfManagementDraft(), repository.open(other))
            } finally {
                listOf(id, other).forEach { owner ->
                    AtomicFile(File(directory, "$owner.json")).delete()
                    AtomicFile(File(directory, "$owner.json.closed")).delete()
                }
            }
        }

    @Test
    fun closingSessionDeletesOnlyItsRegisteredExportAndKeepsAnIndependentPreparedFile() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = FileBookshelfManagementDraftRepository(context)
            val session = UUID.randomUUID().toString()
            val directory =
                File(context.filesDir, "bookshelf-management-exports").apply {
                    check(isDirectory || mkdirs())
                }
            val owned =
                File(directory, "${UUID.randomUUID()}.json").apply {
                    writeText("owned synthetic export")
                }
            val other =
                File(directory, "${UUID.randomUUID()}.json").apply {
                    writeText("independent synthetic export")
                }
            val drafts = File(context.filesDir, "bookshelf-management-drafts")
            try {
                repository.open(session)
                repository.write(
                    session,
                    BookshelfManagementDraft(8, exports = listOf(owned.absolutePath)),
                )
                repository.release(session)
                assertFalse(owned.exists())
                assertTrue(other.exists())
                assertEquals("independent synthetic export", other.readText())
                assertTrue(
                    runCatching { repository.write(session, BookshelfManagementDraft(9)) }.isFailure
                )
            } finally {
                owned.delete()
                other.delete()
                AtomicFile(File(drafts, "$session.json")).delete()
                AtomicFile(File(drafts, "$session.json.closed")).delete()
            }
        }
}
