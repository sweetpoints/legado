package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftConflictException
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchMetadataRepository
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchHistory
import io.legado.app.model.webBook.BookSearchMembership
import io.legado.app.model.webBook.BookSearchPreferences
import io.legado.app.model.webBook.BookSearchSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun diskRevisionAndLargePayloadRestoreWithoutSavedStatePayloadOrSearchReplay() =
        runTest(dispatcher) {
            val payload = "synthetic".repeat(100000)
            val drafts =
                Drafts(
                    BookSearchDraft(
                        revision = Long.MAX_VALUE / 2,
                        query = payload,
                        filterDraft = payload,
                        interrupted = true,
                    )
                )
            val saved = SavedStateHandle()
            val metadata = Metadata()
            val viewModel = BookSearchViewModel(drafts, Preferences(), metadata, saved)
            try {
                runCurrent()
                assertTrue(viewModel.state.value.ready)
                assertEquals(payload, viewModel.state.value.draft.query)
                assertEquals(payload, viewModel.state.value.draft.filterDraft)
                assertTrue(viewModel.state.value.draft.interrupted)
                assertTrue(drafts.written.isEmpty())
                assertEquals(setOf("searchSession"), saved.keys())
                viewModel.editQuery("edited", -1, 99)
                runCurrent()
                assertTrue(drafts.current.revision > Long.MAX_VALUE / 2)
                assertEquals(0, drafts.current.selectionStart)
                assertEquals(6, drafts.current.selectionEnd)
                assertEquals(listOf(payload, "edited"), metadata.historyQueries)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun failedLoadCannotWriteBlankDraftAndExplicitRetryRestoresOriginal() =
        runTest(dispatcher) {
            val drafts =
                Drafts(BookSearchDraft(revision = 20, query = "original")).apply { failOpen = true }
            val viewModel =
                BookSearchViewModel(drafts, Preferences(), Metadata(), SavedStateHandle())
            try {
                runCurrent()
                assertTrue(viewModel.state.value.initializationFailed)
                viewModel.editQuery("blank replacement")
                runCurrent()
                assertTrue(drafts.written.isEmpty())
                drafts.failOpen = false
                viewModel.retry()
                runCurrent()
                assertEquals("original", viewModel.state.value.draft.query)
                assertTrue(viewModel.state.value.ready)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun writeFailureRetainsNewestEditableTextAndRetryDoesNotReplayMetadataMutation() =
        runTest(dispatcher) {
            val drafts = Drafts().apply { failWrite = true }
            val viewModel =
                BookSearchViewModel(drafts, Preferences(), Metadata(), SavedStateHandle())
            try {
                runCurrent()
                viewModel.editQuery("latest", 2, 4)
                viewModel.openFilter()
                viewModel.editFilter("unfinished\n", 3)
                runCurrent()
                assertNotNull(viewModel.state.value.persistError)
                assertEquals("latest", viewModel.state.value.draft.query)
                drafts.failWrite = false
                viewModel.retry()
                runCurrent()
                assertNull(viewModel.state.value.persistError)
                assertEquals(viewModel.state.value.draft, drafts.current)
                assertEquals(
                    viewModel.state.value.draft.revision,
                    viewModel.state.value.durableRevision,
                )
                assertEquals("unfinished\n", drafts.current.filterDraft)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun externalPreferencesKeepOpenEditorAndPrivateScopeWhileMetadataRemainsLive() =
        runTest(dispatcher) {
            val preferences = Preferences()
            val metadata = Metadata()
            val drafts = Drafts(BookSearchDraft(revision = 8, scope = "private-group"))
            val viewModel = BookSearchViewModel(drafts, preferences, metadata, SavedStateHandle())
            try {
                runCurrent()
                viewModel.openFilter()
                viewModel.editFilter("my unfinished filter")
                preferences.values.value =
                    BookSearchPreferences(
                        resultFilter = "external",
                        scope = "global-group",
                        loadCoverOnlyWifi = true,
                    )
                metadata.groupValues.value = listOf("first", "second")
                metadata.memberships.value =
                    BookSearchMembership(setOf("book-id"), ReadRecordIndex.EMPTY)
                runCurrent()
                assertEquals("my unfinished filter", viewModel.state.value.draft.filterDraft)
                assertEquals("private-group", viewModel.state.value.draft.scope)
                assertTrue(viewModel.state.value.preferences.loadCoverOnlyWifi)
                assertEquals(listOf("first", "second"), viewModel.state.value.groups)
                assertTrue(viewModel.state.value.membership.onShelf("book-id", "name", "author"))
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun canceledNonCooperativeInitializationCannotPublishOrStartObservers() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val drafts = Drafts(BookSearchDraft(query = "late")).apply { openGate = gate }
            val metadata = Metadata()
            val viewModel = BookSearchViewModel(drafts, Preferences(), metadata, SavedStateHandle())
            runCurrent()
            val before = viewModel.state.value
            viewModel.stop()
            gate.complete(Unit)
            runCurrent()
            assertEquals(before, viewModel.state.value)
            assertTrue(metadata.historyQueries.isEmpty())
        }

    @Test
    fun filterDismissOnlyChangesPrivateDraftAndRealCloseReleasesOwnedSession() =
        runTest(dispatcher) {
            val drafts = Drafts()
            val preferences = Preferences()
            val viewModel = BookSearchViewModel(drafts, preferences, Metadata(), SavedStateHandle())
            try {
                runCurrent()
                viewModel.openFilter()
                viewModel.editFilter("draft")
                viewModel.dismissFilter()
                runCurrent()
                assertNull(drafts.current.filterDraft)
                assertEquals("", preferences.values.value.resultFilter)
                viewModel.release()
                assertEquals(listOf(viewModel.session), drafts.released)
                viewModel.editQuery("after close")
                runCurrent()
                assertFalse(viewModel.state.value.draft.query == "after close")
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun metadataFailureRetriesSubscriptionsWithoutReplacingEditableQuery() =
        runTest(dispatcher) {
            val metadata = Metadata().apply { failHistory = true }
            val viewModel =
                BookSearchViewModel(Drafts(), Preferences(), metadata, SavedStateHandle())
            try {
                runCurrent()
                assertNotNull(viewModel.state.value.metadataError)
                viewModel.editQuery("retained query")
                runCurrent()
                metadata.failHistory = false
                viewModel.retry()
                runCurrent()
                assertNull(viewModel.state.value.metadataError)
                assertEquals("retained query", viewModel.state.value.draft.query)
                assertEquals("retained query", metadata.historyQueries.last())
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun consumedNativeReceiptDoesNotReplayWhenPrivateQueueRemovalFails() =
        runTest(dispatcher) {
            val drafts = Drafts()
            val saved = SavedStateHandle()
            val viewModel = BookSearchViewModel(drafts, Preferences(), Metadata(), saved)
            try {
                runCurrent()
                viewModel.openLog()
                assertNull(
                    viewModel.consumeReceipt(viewModel.state.value.draft.effects.single().id)
                )
                runCurrent()
                val receipt = viewModel.state.value.draft.effects.single()
                drafts.failWrite = true
                assertEquals(receipt, viewModel.consumeReceipt(receipt.id))
                assertNull(viewModel.consumeReceipt(receipt.id))
                runCurrent()
                assertEquals(listOf(receipt), drafts.current.effects)
                val restoredSaved =
                    SavedStateHandle(saved.keys().associateWith { key -> saved.get<Any?>(key) })
                val restored = BookSearchViewModel(drafts, Preferences(), Metadata(), restoredSaved)
                try {
                    runCurrent()
                    assertTrue(restored.state.value.draft.effects.isEmpty())
                    assertEquals(setOf("searchSession", "searchConsumedSequence"), saved.keys())
                } finally {
                    restored.stop()
                }
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun earlyOwnedScopeResultSurvivesLoadFailureDismissAndRejectsStaleRequest() =
        runTest(dispatcher) {
            val drafts = Drafts().apply { failOpen = true }
            val saved = SavedStateHandle(mapOf("searchScopeRequest" to "owned"))
            val preferences = Preferences()
            val viewModel = BookSearchViewModel(drafts, preferences, Metadata(), saved)
            try {
                viewModel.scopeSelected("old", "ignored")
                viewModel.scopeSelected("owned", "complete-scope")
                viewModel.scopeDismissed("owned")
                runCurrent()
                assertEquals("owned", viewModel.scopeRequest())
                drafts.failOpen = false
                viewModel.retry()
                runCurrent()
                assertEquals("complete-scope", viewModel.state.value.draft.scope)
                assertEquals("complete-scope", preferences.values.value.scope)
                assertNull(viewModel.scopeRequest())
                assertNull(drafts.current.pendingScopeRequest)
                assertEquals(setOf("searchSession"), saved.keys())
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun durableScopeResultRestoresWithoutRequiringCallbackOrRepeatingNetworkRequest() =
        runTest(dispatcher) {
            val drafts =
                Drafts(
                    BookSearchDraft(
                        revision = 50,
                        scope = "old",
                        pendingScopeRequest = "result",
                        pendingScopeValue = "accepted",
                    )
                )
            val preferences = Preferences()
            val viewModel = BookSearchViewModel(drafts, preferences, Metadata(), SavedStateHandle())
            try {
                runCurrent()
                assertEquals("accepted", viewModel.state.value.draft.scope)
                assertEquals("accepted", preferences.values.value.scope)
                assertNull(drafts.current.pendingScopeRequest)
                assertFalse(viewModel.state.value.searching)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun rejectedLateWriteCannotAuthorizeNativeReceiptOrReleaseNewerOwner() =
        runTest(dispatcher) {
            val drafts = Drafts(BookSearchDraft(revision = 10))
            val viewModel =
                BookSearchViewModel(drafts, Preferences(), Metadata(), SavedStateHandle())
            try {
                runCurrent()
                val gate = CompletableDeferred<Unit>()
                drafts.writeGate = gate
                viewModel.openLog()
                val receipt = viewModel.state.value.draft.effects.single()
                runCurrent()
                val newer = BookSearchDraft(revision = 11, query = "new owner's query")
                drafts.current = newer
                gate.complete(Unit)
                runCurrent()

                assertTrue(viewModel.state.value.draftConflict)
                assertFalse(viewModel.state.value.ready)
                assertEquals(-1L, viewModel.state.value.durableRevision)
                assertNull(viewModel.consumeReceipt(receipt.id))
                viewModel.editQuery("stale editing")
                viewModel.retry()
                runCurrent()
                assertEquals(newer, drafts.current)
                viewModel.release()
                assertTrue(drafts.released.isEmpty())
                assertEquals(newer, drafts.current)
            } finally {
                drafts.writeGate?.complete(Unit)
                viewModel.stop()
            }
        }

    @Test
    fun oldScopeDialogResultCannotReplaceNewOwnedRequest() =
        runTest(dispatcher) {
            val preferences = Preferences()
            val viewModel =
                BookSearchViewModel(Drafts(), preferences, Metadata(), SavedStateHandle())
            try {
                runCurrent()
                viewModel.openScope()
                runCurrent()
                val first = viewModel.state.value.draft.effects.single()
                assertNotNull(viewModel.consumeReceipt(first.id))
                viewModel.scopeDismissed(first.id)
                runCurrent()
                viewModel.openScope()
                runCurrent()
                val second = viewModel.state.value.draft.effects.single()
                assertNotNull(viewModel.consumeReceipt(second.id))
                viewModel.scopeSelected(first.id, "obsolete scope")
                viewModel.scopeDismissed(first.id)
                runCurrent()
                assertEquals(second.id, viewModel.scopeRequest())
                assertEquals("", preferences.values.value.scope)
                viewModel.scopeSelected(second.id, "accepted scope")
                runCurrent()
                assertEquals("accepted scope", viewModel.state.value.draft.scope)
                assertEquals("accepted scope", preferences.values.value.scope)
                assertNull(viewModel.scopeRequest())
            } finally {
                viewModel.stop()
            }
        }

    private class Drafts(var current: BookSearchDraft = BookSearchDraft()) :
        BookSearchDraftRepository {
        var failOpen = false
        var failWrite = false
        var openGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val written = mutableListOf<BookSearchDraft>()
        val released = mutableListOf<String>()

        override suspend fun open(session: String): BookSearchDraft {
            openGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            check(!failOpen) { "synthetic load failure" }
            return current
        }

        override suspend fun write(session: String, draft: BookSearchDraft) {
            writeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            check(!failWrite) { "synthetic write failure" }
            if (draft.revision > current.revision) {
                current = draft
            } else if (draft != current) {
                throw BookSearchDraftConflictException()
            }
            written += draft
        }

        override suspend fun release(session: String) {
            released += session
        }
    }

    private class Preferences : BookSearchPreferencesRepository {
        val values = MutableStateFlow(BookSearchPreferences())

        override fun observe() = values

        override suspend fun load() = values.value

        override suspend fun precision(value: Boolean) =
            values.value.copy(precision = value).also { values.value = it }

        override suspend fun showReadRecord(value: Boolean) =
            values.value.copy(showReadRecord = value).also { values.value = it }

        override suspend fun resultFilter(value: String) =
            values.value.copy(resultFilter = value).also { values.value = it }

        override suspend fun scope(value: String) =
            values.value.copy(scope = value).also { values.value = it }
    }

    private class Metadata : BookSearchMetadataRepository {
        var failHistory = false
        val historyQueries = mutableListOf<String>()
        val groupValues = MutableStateFlow<List<String>>(emptyList())
        val memberships = MutableStateFlow(BookSearchMembership(emptySet(), ReadRecordIndex.EMPTY))

        override fun history(query: String): Flow<List<BookSearchHistory>> {
            historyQueries += query
            return flow {
                check(!failHistory) { "synthetic metadata failure" }
                emit(emptyList())
            }
        }

        override fun suggestions(query: String): Flow<List<BookSearchSuggestion>> =
            flowOf(emptyList())

        override fun membership() = memberships

        override fun groups() = groupValues

        override suspend fun hasNamedBook(name: String) = false

        override suspend fun saveHistory(word: String) = Unit

        override suspend fun deleteHistory(word: String) = Unit

        override suspend fun clearHistory() = Unit
    }
}
