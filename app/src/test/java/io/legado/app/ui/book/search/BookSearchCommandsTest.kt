package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchEngineRepository
import io.legado.app.data.repository.BookSearchMetadataRepository
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchHistory
import io.legado.app.model.webBook.BookSearchMembership
import io.legado.app.model.webBook.BookSearchPreferences
import io.legado.app.model.webBook.BookSearchSession
import io.legado.app.model.webBook.BookSearchSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchCommandsTest {
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
    fun submitTrimsEngineAndHistoryQueryButKeepsEditableSelectionAndScope() =
        runTest(dispatcher) {
            val engine = Engine()
            val metadata = Metadata()
            val drafts = Drafts(BookSearchDraft(revision = 10, scope = "group"))
            val viewModel =
                BookSearchViewModel(drafts, Preferences(), metadata, SavedStateHandle()) { engine }
            try {
                runCurrent()
                viewModel.editQuery("  keyword  ")
                runCurrent()
                viewModel.submit()
                runCurrent()
                assertEquals(listOf("keyword"), metadata.saved)
                assertEquals(listOf("keyword" to "group"), engine.searches)
                assertEquals("keyword", metadata.queries.last())
                assertEquals("  keyword  ", viewModel.state.value.draft.query)
                assertTrue(viewModel.state.value.searching)
                assertFalse(viewModel.state.value.draft.inputHelp)
                assertEquals("keyword", drafts.current.submittedKey)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun cursorOnlyEditPreservesActiveSearchWhileNewTextRejectsLateCallbacks() =
        runTest(dispatcher) {
            val engine = Engine()
            val viewModel =
                BookSearchViewModel(Drafts(), Preferences(), Metadata(), SavedStateHandle()) {
                    engine
                }
            try {
                runCurrent()
                viewModel.editQuery("first")
                runCurrent()
                viewModel.submit()
                runCurrent()
                val oldGeneration = engine.state.value.generation
                val stopCount = engine.stops
                viewModel.editQuery("first", 1, 2)
                runCurrent()
                assertEquals(stopCount, engine.stops)
                assertTrue(viewModel.state.value.searching)
                viewModel.editQuery("second")
                runCurrent()
                engine.state.value =
                    BookSearchSession(
                        generation = oldGeneration,
                        key = "first",
                        searched = 99,
                        searching = true,
                    )
                runCurrent()
                assertFalse(viewModel.state.value.searching)
                assertEquals("second", viewModel.state.value.draft.query)
                assertEquals(0, viewModel.state.value.draft.searched)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun manualStopBlocksAutomaticPagingAndExplicitContinueDoesNotSaveHistoryAgain() =
        runTest(dispatcher) {
            val engine = Engine()
            val metadata = Metadata()
            val viewModel =
                BookSearchViewModel(Drafts(), Preferences(), metadata, SavedStateHandle()) {
                    engine
                }
            try {
                runCurrent()
                viewModel.editQuery("query")
                runCurrent()
                viewModel.submit()
                runCurrent()
                viewModel.stopSearch()
                runCurrent()
                viewModel.continueSearch(manual = false)
                runCurrent()
                assertEquals(0, engine.pages)
                viewModel.continueSearch()
                runCurrent()
                assertEquals(1, engine.pages)
                assertEquals(listOf("query"), metadata.saved)
                assertFalse(viewModel.state.value.draft.manualStop)
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun restoredInterruptedDraftWaitsForExplicitContinueAndDoesNotRepeatHistory() =
        runTest(dispatcher) {
            val engine = Engine()
            val metadata = Metadata()
            val drafts =
                Drafts(
                    BookSearchDraft(
                        revision = 40,
                        query = "restored",
                        submittedKey = "restored",
                        scope = "private",
                        interrupted = true,
                    )
                )
            val viewModel =
                BookSearchViewModel(drafts, Preferences(), metadata, SavedStateHandle()) { engine }
            try {
                runCurrent()
                assertTrue(engine.searches.isEmpty())
                assertTrue(metadata.saved.isEmpty())
                viewModel.continueSearch()
                runCurrent()
                assertEquals(listOf("restored" to "private"), engine.searches)
                assertTrue(metadata.saved.isEmpty())
            } finally {
                viewModel.stop()
            }
        }

    @Test
    fun namedShelfHistoryOnlyChangesQueryAndLateLookupCannotStartOldSearch() =
        runTest(dispatcher) {
            val engine = Engine()
            val metadata = Metadata().apply { namedBook = true }
            val viewModel =
                BookSearchViewModel(Drafts(), Preferences(), metadata, SavedStateHandle()) {
                    engine
                }
            try {
                runCurrent()
                viewModel.historyClicked("on shelf")
                runCurrent()
                assertTrue(engine.searches.isEmpty())
                assertEquals("on shelf", viewModel.state.value.draft.query)
                val gate = CompletableDeferred<Unit>()
                metadata.namedGate = gate
                metadata.namedBook = false
                viewModel.historyClicked("old")
                runCurrent()
                viewModel.editQuery("new")
                gate.complete(Unit)
                runCurrent()
                assertTrue(engine.searches.isEmpty())
                assertEquals("new", viewModel.state.value.draft.query)
                viewModel.historyClicked("new")
                runCurrent()
                assertEquals(listOf("new" to ""), engine.searches)
            } finally {
                viewModel.stop()
            }
        }

    private class Engine : BookSearchEngineRepository {
        override val state = MutableStateFlow(BookSearchSession())
        val searches = mutableListOf<Pair<String, String>>()
        var stops = 0
        var pages = 0

        override suspend fun search(key: String, scope: String) {
            searches += key to scope
            state.value =
                BookSearchSession(
                    generation = state.value.generation + 1,
                    key = key,
                    scope = scope,
                    searching = key.isNotEmpty(),
                )
        }

        override suspend fun nextPage() {
            pages++
            state.value = state.value.copy(searching = true)
        }

        override suspend fun stop() {
            stops++
            state.value =
                state.value.copy(generation = state.value.generation + 1, searching = false)
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override suspend fun close() = Unit
    }

    private class Drafts(var current: BookSearchDraft = BookSearchDraft()) :
        BookSearchDraftRepository {
        override suspend fun open(session: String) = current

        override suspend fun write(session: String, draft: BookSearchDraft) {
            if (draft.revision > current.revision) current = draft
        }

        override suspend fun release(session: String) = Unit
    }

    private class Preferences : BookSearchPreferencesRepository {
        private val values = MutableStateFlow(BookSearchPreferences())

        override fun observe() = values

        override suspend fun load() = values.value

        override suspend fun precision(value: Boolean) = values.value.copy(precision = value)

        override suspend fun showReadRecord(value: Boolean) =
            values.value.copy(showReadRecord = value)

        override suspend fun resultFilter(value: String) = values.value.copy(resultFilter = value)

        override suspend fun scope(value: String) = values.value.copy(scope = value)
    }

    private class Metadata : BookSearchMetadataRepository {
        val saved = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var namedBook = false
        var namedGate: CompletableDeferred<Unit>? = null

        override fun history(query: String): Flow<List<BookSearchHistory>> {
            queries += query
            return flowOf(emptyList())
        }

        override fun suggestions(query: String): Flow<List<BookSearchSuggestion>> =
            flowOf(emptyList())

        override fun membership() = flowOf(BookSearchMembership(emptySet(), ReadRecordIndex.EMPTY))

        override fun groups() = flowOf(emptyList<String>())

        override suspend fun hasNamedBook(name: String): Boolean {
            namedGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return namedBook
        }

        override suspend fun saveHistory(word: String) {
            saved += word
        }

        override suspend fun deleteHistory(word: String) = Unit

        override suspend fun clearHistory() = Unit
    }
}
