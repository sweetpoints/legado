package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchInputControllerTest {
    @Test
    fun privateInitialSeedIsAcceptedBeforeSearchAndRestorationDoesNotRepeat() = runTest {
        val fixture =
            Fixture(this, BookSearchDraft(revision = 1, query = " seed ", navigationSeed = true))
        try {
            fixture.controller.ready()
            runCurrent()
            assertEquals(listOf("seed"), fixture.searches)
            assertTrue(fixture.persisted.initialEntryAccepted)
            assertEquals("owner", fixture.persisted.acceptedNavigationTicket)
            fixture.controller.ready()
            runCurrent()
            assertEquals(1, fixture.searches.size)
            val restored = Fixture(this, fixture.persisted)
            try {
                restored.controller.ready()
                runCurrent()
                assertTrue(restored.searches.isEmpty())
            } finally {
                restored.controller.stop()
            }
        } finally {
            fixture.controller.stop()
        }
    }

    @Test
    fun earlyLegacyPreparationTransfersLargeInputOnlyAfterOwnerIsReady() = runTest {
        val fixture = Fixture(this)
        val query = "synthetic".repeat(100000)
        try {
            fixture.state = fixture.state.copy(loading = true)
            fixture.controller.receiveLegacy(query, "source-url", newIntent = false)
            runCurrent()
            assertTrue(fixture.searches.isEmpty())
            assertEquals(
                setOf("searchLegacyAccepted", "searchIncomingTicket"),
                fixture.saved.keys(),
            )
            assertFalse(fixture.saved.keys().any { fixture.saved.get<Any?>(it) == query })
            fixture.state = fixture.state.copy(loading = false)
            fixture.controller.ready()
            runCurrent()
            assertEquals(query, fixture.persisted.query)
            assertEquals("source-url", fixture.persisted.scope)
            assertEquals(listOf(query), fixture.searches)
            assertEquals(listOf(query), fixture.observedQueries)
            assertTrue(fixture.drafts.released.contains(fixture.persisted.acceptedNavigationTicket))
            assertEquals(setOf("searchLegacyAccepted"), fixture.saved.keys())
        } finally {
            fixture.controller.stop()
        }
    }

    @Test
    fun lateNonCooperativeReadCannotReplaceUserEditing() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.drafts.values["input"] = BookSearchDraft(revision = 1, query = "obsolete")
        fixture.drafts.readGate = gate
        try {
            fixture.controller.receive("input")
            runCurrent()
            fixture.controller.cancelPending()
            fixture.state =
                fixture.state.copy(draft = fixture.state.draft.copy(query = "user edit"))
            gate.complete(Unit)
            runCurrent()
            assertEquals("user edit", fixture.state.draft.query)
            assertTrue(fixture.searches.isEmpty())
            assertTrue(fixture.drafts.released.contains("input"))
        } finally {
            gate.complete(Unit)
            fixture.controller.stop()
        }
    }

    @Test
    fun restoredAcceptedTicketOnlyFinishesOwnedCleanup() = runTest {
        val accepted =
            BookSearchDraft(revision = 8, query = "retained", acceptedNavigationTicket = "input")
        val fixture = Fixture(this, accepted)
        fixture.saved["searchIncomingTicket"] = "input"
        try {
            fixture.controller.ready()
            runCurrent()
            assertEquals(accepted, fixture.persisted)
            assertTrue(fixture.searches.isEmpty())
            assertEquals(listOf("input"), fixture.drafts.released)
            assertTrue(fixture.saved.keys().isEmpty())
        } finally {
            fixture.controller.stop()
        }
    }

    @Test
    fun failedCheckpointRetriesAcceptanceWithoutDuplicatingPreparation() = runTest {
        val fixture = Fixture(this)
        fixture.drafts.values["input"] = BookSearchDraft(revision = 1, query = "retry query")
        fixture.failCheckpoint = true
        try {
            fixture.controller.receive("input")
            runCurrent()
            assertEquals(1, fixture.failures.size)
            assertTrue(fixture.searches.isEmpty())
            assertFalse(fixture.drafts.released.contains("input"))
            fixture.failCheckpoint = false
            fixture.controller.ready()
            runCurrent()
            assertEquals(listOf("retry query"), fixture.searches)
            assertEquals(listOf("input"), fixture.drafts.released)
        } finally {
            fixture.controller.stop()
        }
    }

    private class Fixture(owner: CoroutineScope, initial: BookSearchDraft = BookSearchDraft()) {
        val drafts = Drafts()
        val saved = SavedStateHandle()
        var state =
            BookSearchUiState(loading = false, draft = initial, durableRevision = initial.revision)
        var persisted = initial
        var failCheckpoint = false
        val searches = mutableListOf<String>()
        val observedQueries = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val controller =
            BookSearchInputController(
                session = "owner",
                saved = saved,
                drafts = drafts,
                preferences = Preferences(),
                owner = owner,
                cleanupOwner = owner,
                state = { state },
                update = { transform ->
                    val updated = transform(state.draft)
                    if (updated != state.draft) {
                        state =
                            state.copy(draft = updated.copy(revision = state.draft.revision + 1))
                    }
                },
                checkpoint = {
                    check(!failCheckpoint) { "synthetic checkpoint failure" }
                    persisted = state.draft
                    state = state.copy(durableRevision = persisted.revision)
                },
                queryChanged = { query -> observedQueries += query },
                search = { query ->
                    check(persisted == state.draft)
                    searches += query
                },
                failure = { error -> failures += error },
                cleanupFailure = { error -> failures += error },
            )
    }

    private class Drafts : BookSearchDraftRepository {
        val values = mutableMapOf<String, BookSearchDraft>()
        val released = mutableListOf<String>()
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun open(session: String) = values.getOrPut(session) { BookSearchDraft() }

        override suspend fun existing(session: String): BookSearchDraft {
            val value = values.getValue(session)
            readGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return value
        }

        override suspend fun write(session: String, draft: BookSearchDraft) {
            values[session] = draft
        }

        override suspend fun release(session: String) {
            released += session
            values.remove(session)
        }
    }

    private class Preferences : BookSearchPreferencesRepository {
        private var value = BookSearchPreferences()

        override fun observe() = flowOf(value)

        override suspend fun load() = value

        override suspend fun precision(value: Boolean) = this.value.copy(precision = value)

        override suspend fun showReadRecord(value: Boolean) =
            this.value.copy(showReadRecord = value)

        override suspend fun resultFilter(value: String) = this.value.copy(resultFilter = value)

        override suspend fun scope(value: String) = this.value.copy(scope = value)
    }
}
