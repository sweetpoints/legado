package io.legado.app.data.repository

import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchInputRepositoryTest {
    @Test
    fun longQueryAndDefaultOrExplicitScopeRemainInPrivateInputWithSmallTicket() = runBlocking {
        val drafts = Drafts()
        val preferences = Preferences()
        val repository = BookSearchInputRepository(drafts, preferences) { "owned" }
        val text = "synthetic".repeat(100000)
        assertEquals("owned", repository.prepare(text, null))
        assertEquals(text, drafts.rows.getValue("owned").query)
        assertEquals("global", drafts.rows.getValue("owned").scope)
        assertEquals(text.length, drafts.rows.getValue("owned").selectionStart)
        assertTrue(drafts.rows.getValue("owned").navigationSeed)
        repository.prepare(text, "Source::synthetic")
        assertEquals("Source::synthetic", drafts.rows.getValue("owned").scope)
        assertFalse(drafts.rows.getValue("owned").initialEntryAccepted)
    }

    @Test
    fun failedPreparationReleasesOnlyItsOwnedInput() = runBlocking {
        val drafts =
            Drafts().apply {
                rows["neighbor"] = BookSearchDraft(query = "kept")
                failWrite = true
            }
        val repository = BookSearchInputRepository(drafts, Preferences()) { "owned" }
        assertTrue(runCatching { repository.prepare("query", "scope") }.isFailure)
        assertEquals(listOf("owned"), drafts.released)
        assertEquals("kept", drafts.rows.getValue("neighbor").query)
    }

    @Test
    fun callerCanceledAfterAcceptedWriteDoesNotLeakUnreturnedInput() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val drafts = Drafts().apply { writeGate = accepted to finish }
        val repository = BookSearchInputRepository(drafts, Preferences()) { "owned" }
        val job = launch { repository.prepare("query", "scope") }
        accepted.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertFalse(drafts.rows.containsKey("owned"))
        assertEquals(listOf("owned"), drafts.released)
    }

    private class Drafts : BookSearchDraftRepository {
        val rows = mutableMapOf<String, BookSearchDraft>()
        val released = mutableListOf<String>()
        var failWrite = false
        var writeGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

        override suspend fun open(session: String) = rows.getOrPut(session) { BookSearchDraft() }

        override suspend fun write(session: String, draft: BookSearchDraft) {
            check(!failWrite) { "synthetic preparation failure" }
            withContext(NonCancellable) {
                rows[session] = draft
                writeGate?.let { gate ->
                    gate.first.complete(Unit)
                    gate.second.await()
                }
            }
        }

        override suspend fun release(session: String) {
            released += session
            rows.remove(session)
        }
    }

    private class Preferences : BookSearchPreferencesRepository {
        private val values = MutableStateFlow(BookSearchPreferences(scope = "global"))

        override fun observe() = values

        override suspend fun load() = values.value

        override suspend fun precision(value: Boolean) = values.value

        override suspend fun showReadRecord(value: Boolean) = values.value

        override suspend fun resultFilter(value: String) = values.value

        override suspend fun scope(value: String) = values.value
    }
}
