package io.legado.app.ui.book.searchContent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.model.book.ContentSearchMatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContentSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun result(id: String = "match", query: String = "needle") = ContentSearchMatch(id, query = query,
        resultText = "large snippet", chapterTitle = "Chapter", chapterIndex = 7, queryIndexInChapter = 123, resultCountWithinChapter = 2)
    private fun snapshot(query: String = "needle", results: List<ContentSearchMatch> = emptyList(), initial: Boolean = false) =
        ContentSearchSession("book", query = query, results = results, initialSubmit = initial)
    private fun model(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), options: Options = Options(), seed: ContentSearchSession = snapshot()) =
        ContentSearchViewModel(repo, options, saved) { seed }
    private fun own(model: ContentSearchViewModel) = ViewModelStore().apply { put("content", model) }
    private suspend fun ready(model: ContentSearchViewModel) { model.state.first { !it.loading }; yield() }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test fun suppliedResultsKeepAllReaderMetadataAndPositionWithoutStartingSearchOrSavingLargePayload() = runTest(dispatcher) {
        val incoming = result().copy(resultText = "x".repeat(1200000), query = "q".repeat(200000), pageIndex = 3, pageSize = 12, resultCount = 99)
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved, seed = snapshot(results = listOf(incoming)).copy(position = 4, searchOpen = false)); val owner = own(vm)
        try { ready(vm); assertEquals(listOf(incoming), vm.state.value.results); assertEquals(4, vm.state.value.position)
            assertFalse(vm.state.value.focusInput); assertTrue(repo.queries.isEmpty()); vm.position(6); vm.flush()
            assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(listOf(incoming), restored.state.value.results); assertEquals(6, restored.state.value.position) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun initialQuerySubmitsOnceButProcessRestoreKeepsPartialResultsWithoutAutomaticallyRepeatingSearch() = runTest(dispatcher) {
        val repo = Fake().apply { searchBlock = { _, _ -> flow { emit(ContentSearchUpdate(listOf(result()), true, 1, 5)); awaitCancellation() } } }
        val saved = SavedStateHandle(); val vm = model(repo, saved, seed = snapshot(" needle ", initial = true)); val owner = own(vm)
        try { vm.state.first { it.results.isNotEmpty() }; vm.flush(); assertEquals(listOf("needle"), repo.queries); assertFalse(vm.state.value.focusInput)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(1, restored.state.value.results.size); assertFalse(restored.state.value.running)
                assertEquals(listOf("needle"), repo.queries); assertFalse(repo.value!!.initialSubmit) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun stopKeepsPartialResultsAndLateNonCooperativeFailureCannotPublishError() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { searchBlock = { _, _ -> flow {
            emit(ContentSearchUpdate(listOf(result()), true, 1, 4)); withContext(NonCancellable) { gate.await(); error("late failure") }
        } } }
        val vm = model(repo); val owner = own(vm)
        try { ready(vm); vm.submit(); vm.state.first { it.results.isNotEmpty() }; vm.stopSearch(); gate.complete(Unit); runCurrent()
            assertFalse(vm.state.value.running); assertNull(vm.state.value.error); assertEquals(1, vm.state.value.results.size); assertFalse(vm.state.value.completed)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun newQueryCancelsOldGenerationAndWhitespaceSubmissionDoesNothing() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { searchBlock = { query, _ -> flow {
            if (query == "needle") withContext(NonCancellable) { gate.await() }
            currentCoroutineContext().ensureActive(); emit(ContentSearchUpdate(listOf(result(query, query)), false, 1, 1))
        } } }
        val vm = model(repo); val owner = own(vm)
        try { ready(vm); vm.query(" "); vm.submit(); runCurrent(); assertTrue(repo.queries.isEmpty())
            vm.query("needle"); vm.submit(); runCurrent(); vm.query("new"); vm.submit(); vm.state.first { it.completed }
            gate.complete(Unit); runCurrent(); assertEquals(listOf("new"), vm.state.value.results.map { it.query })
            assertEquals(listOf("needle", "new"), repo.queries)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun liveCachedChapterEventsAndOptionTogglesApplyToFollowingChaptersWithoutRestartingSearch() = runTest(dispatcher) {
        val next = CompletableDeferred<Unit>(); val options = Options(); var observed = emptySet<String>(); var settings = ContentSearchOptions()
        val repo = Fake().apply { searchBlock = { _, cache -> flow { next.await(); observed = cache(); settings = options.current(); emit(ContentSearchUpdate(emptyList(), false, 2, 2)) } } }
        val vm = model(repo, options = options); val owner = own(vm)
        try { ready(vm); vm.submit(); runCurrent(); vm.cached("unrelated", "wrong"); vm.cached("book", "fresh")
            vm.replace(true); vm.regex(true); assertEquals(listOf("needle"), repo.queries); next.complete(Unit); vm.state.first { it.completed }
            assertEquals(setOf("cached", "fresh"), observed); assertEquals(ContentSearchOptions(true, true), settings)
        } finally { next.complete(Unit); owner.clear() }
    }
    @Test fun resultDeliveryIsDurableBeforePublishAndConsumedOnceAcrossRecreation() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val match = result(); val vm = model(repo, saved, seed = snapshot(results = listOf(match))); val owner = own(vm)
        try { ready(vm); repo.writeGate = CompletableDeferred(); vm.choose(match.id); runCurrent()
            assertTrue(vm.state.value.selecting); assertNull(vm.state.value.pendingResult)
            repo.writeGate!!.complete(Unit); vm.state.first { it.pendingResult != null }
            assertEquals(match.id, repo.value!!.pendingResult); val prepared = vm.prepareResult(match.id)
            assertEquals(match, prepared.selected); assertEquals(0, prepared.index); assertEquals(listOf(match), prepared.results)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(match.id, restored.state.value.pendingResult); assertTrue(restored.consumeResult(match.id)); assertFalse(restored.consumeResult(match.id))
                val again = model(repo, copy(saved)); val third = own(again)
                try { ready(again); assertNull(again.state.value.pendingResult); assertTrue(again.state.value.finished) } finally { third.clear() }
            } finally { other.clear() }
        } finally { repo.writeGate?.complete(Unit); owner.clear() }
    }
    @Test fun failedSelectionRetainsResultsWithoutDeliveringAndRetryChoiceCanSucceed() = runTest(dispatcher) {
        val repo = Fake(); val match = result(); val vm = model(repo, seed = snapshot(results = listOf(match))); val owner = own(vm)
        try { ready(vm); repo.failWrites = true; vm.choose(match.id); runCurrent(); assertNull(vm.state.value.pendingResult); assertFalse(vm.state.value.finished)
            assertEquals(listOf(match), vm.state.value.results); assertTrue(vm.state.value.persistError)
            repo.failWrites = false; vm.choose(match.id); vm.state.first { it.pendingResult != null }; assertEquals(match.id, repo.value!!.pendingResult)
        } finally { owner.clear() }
    }
    @Test fun failedInitializationNeverOverwritesStoredMetadataAndRetryUsesDiskRevisionAboveRebootedClock() = runTest(dispatcher) {
        val large = System.nanoTime() + 100000000000L; val original = snapshot(results = listOf(result())).copy(revision = large)
        val repo = Fake(original).apply { failLoad = true }; val vm = model(repo); val owner = own(vm)
        try { ready(vm); assertTrue(vm.state.value.loadFailed); vm.query("bad"); vm.submit(); runCurrent(); assertEquals(original, repo.value); assertEquals(0, repo.writes)
            repo.failLoad = false; vm.retry(); vm.state.first { !it.loading && !it.loadFailed }; assertEquals(original.results, vm.state.value.results)
            vm.query("new"); vm.flush(); assertTrue(repo.value!!.revision > large); assertEquals("new", repo.value!!.query)
        } finally { owner.clear() }
    }
    @Test fun realCloseReleasesOwnedSessionAndLateInitializationCannotResurrectOrPublish() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { loadGate = gate }; val vm = model(repo); val owner = own(vm)
        try { runCurrent(); vm.releaseOwnedSession(); gate.complete(Unit); runCurrent(); assertTrue(repo.closed); assertNull(repo.value)
            assertTrue(vm.state.value.loading); assertNull(vm.state.value.error); assertTrue(repo.queries.isEmpty())
            val restored = model(repo); val other = own(restored)
            try { ready(restored); assertTrue(restored.state.value.finished); assertFalse(restored.state.value.loadFailed); assertNull(repo.value) }
            finally { other.clear() }
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun completedEmptySearchRestoresEmptyIndicatorWhileCancelledSearchDoesNotClaimCompletion() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); vm.submit(); vm.state.first { it.completed }; vm.flush()
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertTrue(restored.state.value.completed); assertTrue(restored.state.value.results.isEmpty()) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun savedCompletionAheadOfDurableResultsCannotClaimPartialSearchFinishedEvenAfterAnotherRestore() = runTest(dispatcher) {
        val original = snapshot(results = listOf(result())).copy(revision = 10)
        val repo = Fake(original); val saved = SavedStateHandle(mapOf("completed" to true, "completedRevision" to 11L))
        val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); assertFalse(vm.state.value.completed); assertEquals(original.results, vm.state.value.results)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertFalse(restored.state.value.completed) } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun anotherPageProcessOptionChangeRefreshesChecksOnResumeWithoutSubmittingOrClobberingItsFlags() = runTest(dispatcher) {
        val options = Options(); val repo = Fake(); val vm = model(repo, options = options); val owner = own(vm)
        try { ready(vm); options.regex(true); options.replace(true); vm.refreshOptions(); vm.flush()
            assertEquals(ContentSearchOptions(true, true), vm.state.value.options); assertEquals(options.current(), repo.value!!.options)
            assertTrue(repo.queries.isEmpty())
        } finally { owner.clear() }
    }
    private class Options : ContentSearchOptionsRepository {
        private var value = ContentSearchOptions()
        override fun current() = value
        override fun replace(value: Boolean) = this.value.copy(replace = value).also { this.value = it }
        override fun regex(value: Boolean) = this.value.copy(regex = value).also { this.value = it }
        override fun restore(value: ContentSearchOptions) = value.also { this.value = it }
    }
    private class Fake(var value: ContentSearchSession? = null) : ContentSearchRepository {
        var closed = false; var failLoad = false; var failWrites = false; var writes = 0
        var writeGate: CompletableDeferred<Unit>? = null; var loadGate: CompletableDeferred<Unit>? = null
        val queries = mutableListOf<String>()
        var searchBlock: (String, () -> Set<String>) -> Flow<ContentSearchUpdate> = { _, _ -> flowOf(ContentSearchUpdate(emptyList(), false, 2, 2)) }
        override suspend fun load(url: String): ContentSearchLoaded {
            loadGate?.let { withContext(NonCancellable) { it.await() } }; if (failLoad) error("Load failed")
            return ContentSearchLoaded(ContentSearchBook(url, "Book", 7, false, "metadata"), setOf("cached"))
        }
        override fun search(book: ContentSearchBook, query: String, cacheNames: () -> Set<String>): Flow<ContentSearchUpdate> {
            queries += query; return searchBlock(query, cacheNames)
        }
        override suspend fun read(session: String): ContentSearchSession? { if (closed) throw ContentSearchSessionClosedException(); return value }
        override suspend fun create(session: String, snapshot: ContentSearchSession): ContentSearchSession {
            if (closed) throw ContentSearchSessionClosedException(); return value ?: snapshot.also { value = it }
        }
        override suspend fun write(session: String, snapshot: ContentSearchSession) {
            writeGate?.await(); if (closed) throw ContentSearchSessionClosedException(); if (failWrites) error("Write failed")
            writes++; if (snapshot.revision >= (value?.revision ?: 0)) value = snapshot
        }
        override suspend fun release(session: String) { closed = true; value = null }
    }
}
