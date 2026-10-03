package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchEffect
import io.legado.app.model.webBook.BookSearchReceipt
import io.legado.app.model.webBook.BookSearchResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchDraftFileTest {
    @Test
    fun sameRevisionIsIdempotentAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = UUID.randomUUID().toString()
        val repository = FileBookSearchDraftRepository(context)
        val restoredRepository = FileBookSearchDraftRepository(context)
        val directory = File(context.filesDir, "book-search-drafts")
        try {
            repository.open(session)
            val acceptedDraft =
                BookSearchDraft(revision = 10, query = "accepted", filterDraft = "kept")
            repository.write(session, acceptedDraft)
            restoredRepository.write(
                session,
                acceptedDraft.copy(query = "late", filterDraft = "lost"),
            )
            assertEquals(acceptedDraft, repository.open(session))

            restoredRepository.write(session, acceptedDraft)
            assertEquals(acceptedDraft, restoredRepository.open(session))

            val nextDraft = acceptedDraft.copy(revision = 11, query = "newer")
            restoredRepository.write(session, nextDraft)
            assertEquals(nextDraft, repository.open(session))
        } finally {
            repository.release(session)
            listOf(
                    ".json",
                    ".json.bak",
                    ".json.new",
                    ".json.closed",
                    ".json.closed.bak",
                    ".json.closed.new",
                )
                .forEach { suffix -> File(directory, session + suffix).delete() }
        }
    }

    @Test
    fun largeResultsAndEditableQueryRestoreWithoutBundleAndCloseRejectsLateWriters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FileBookSearchDraftRepository(context)
        val ownedSession = UUID.randomUUID().toString()
        val neighboringSession = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "book-search-drafts")
        try {
            repository.open(ownedSession)
            repository.open(neighboringSession)
            val largePayload = "synthetic-search-content".repeat(60000)
            val book =
                SearchBook(
                        bookUrl = largePayload,
                        name = "Title",
                        variable = largePayload,
                    )
                    .apply {
                        infoHtml = largePayload
                        addOrigin("another")
                    }
            val result = BookSearchResult.from(book)
            val draft =
                BookSearchDraft(
                    revision = 10,
                    query = largePayload,
                    scope = largePayload,
                    results = listOf(result),
                    filterDraft = largePayload,
                    effects =
                        listOf(
                            BookSearchReceipt(
                                "receipt",
                                BookSearchEffect.BookInfo,
                                resultId = result.id,
                            )
                        ),
                    interrupted = true,
                )
            repository.write(ownedSession, draft)
            repository.write(ownedSession, draft.copy(revision = 9, query = "stale"))
            assertEquals(draft, FileBookSearchDraftRepository(context).open(ownedSession))

            val sessionFile = File(directory, "$ownedSession.json")
            sessionFile.renameTo(File(sessionFile.path + ".bak"))
            assertEquals(draft, repository.open(ownedSession))

            repository.release(ownedSession)
            assertTrue(File(sessionFile.path + ".closed").exists())
            assertFalse(sessionFile.exists())
            assertTrue(
                runCatching {
                    repository.write(ownedSession, draft.copy(revision = 20))
                }
                    .isFailure
            )
            assertTrue(runCatching { repository.open(ownedSession) }.isFailure)
            assertEquals(BookSearchDraft(), repository.open(neighboringSession))

            val marker = File(sessionFile.path + ".closed")
            marker.renameTo(File(marker.path + ".bak"))
            assertTrue(runCatching { repository.open(ownedSession) }.isFailure)
        } finally {
            repository.release(neighboringSession)
            listOf(ownedSession, neighboringSession).forEach { session ->
                listOf(
                        ".json",
                        ".json.bak",
                        ".json.new",
                        ".json.closed",
                        ".json.closed.bak",
                        ".json.closed.new",
                    )
                    .forEach { suffix ->
                        File(directory, session + suffix).delete()
                    }
            }
        }
    }
}
