package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun row(id: String, origin: String = id, type: Int = BookType.text) = ChapterSourceSearchRow(id, origin, origin,
        "Book", "Author", "Latest", null, -1, 10, 0, 0, type, "full-$id")
    private fun snapshot(rows: List<ChapterSourceSearchRow> = emptyList()) = BookSourceChangeSession(ChapterSourceSearchRequest("Book", "Author",
        originalBookJson = GSON.toJson(Book(bookUrl = "old", origin = "old-source", originName = "Original", name = "Book", author = "Author", type = BookType.text)),
        currentBookUrl = "old"), rows = rows)
    private fun vm(change: FakeChange = FakeChange(), search: FakeSearch = FakeSearch(), prefs: FakePrefs = FakePrefs(), saved: SavedStateHandle = SavedStateHandle(), seed: BookSourceChangeSession = snapshot()) =
        BookSourceViewModel(search, change, prefs, saved) { seed }
    private fun own(model: BookSourceViewModel) = ViewModelStore().apply { put("book", model) }
    private suspend fun ready(model: BookSourceViewModel) { model.state.first { !it.loading && it.request != null }; yield() }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun typeMismatchRequiresConfirmationAndRestoresWithoutNetworkOrLargeSavedStatePayload() = runTest(dispatcher) {
        val target = row("target", type = BookType.audio); val search = FakeSearch(listOf(target)); val change = FakeChange(); val saved = SavedStateHandle()
        val model = vm(change, search, saved = saved); val owner = own(model)
        try { ready(model); model.choose(target.id); runCurrent()
            assertEquals(target.id, model.state.value.mismatchId); assertTrue(change.prepared.isEmpty())
            assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
            val restored = vm(change, search, saved = copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(target.id, restored.state.value.mismatchId); assertTrue(change.prepared.isEmpty())
                restored.confirmMismatch(); restored.state.first { it.pendingReceipt != null }
                assertEquals(listOf(target.id), change.prepared); assertNull(restored.state.value.mismatchId)
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun cancelMismatchLeavesDraftAndNeverPreparesReplacement() = runTest(dispatcher) {
        val change = FakeChange(); val target = row("audio", type = BookType.audio)
        val model = vm(change, FakeSearch(listOf(target))); val owner = own(model)
        try { ready(model); model.choose(target.id); model.dismissMismatch(); runCurrent()
            assertNull(model.state.value.mismatchId); assertTrue(change.prepared.isEmpty()); assertEquals(listOf(target), model.state.value.rows)
        } finally { owner.clear() }
    }
    @Test fun currentSourceDeletionTriesOnlyOtherOriginsWithExactTypeAndWaitsForSuccessfulHostAcknowledgement() = runTest(dispatcher) {
        val current = row("old", "old-source"); val sameOrigin = row("same", "old-source")
        val wrong = row("wrong", type = BookType.audio); val first = row("failed"); val good = row("good")
        val change = FakeChange().apply { failingRows += first.id }
        val model = vm(change, FakeSearch(listOf(current, sameOrigin, wrong, first, good))); val owner = own(model)
        try { ready(model); model.deleteSource(current.id); val pending = model.state.first { it.pendingReceipt != null }
            assertEquals(listOf(first.id, good.id), change.prepared); assertTrue(change.deleted.isEmpty())
            val receipt = model.prepareReceipt(pending.pendingReceipt!!); assertEquals(current, receipt.deleteAfter)
            model.consumeReceipt(receipt); runCurrent(); assertFalse(model.state.value.finished); assertTrue(change.deleted.isEmpty())
            model.completeReceipt(receipt); model.completeReceipt(receipt); runCurrent()
            assertEquals(listOf(current.id), change.deleted); assertFalse(model.state.value.rows.any { it.id == current.id })
        } finally { owner.clear() }
    }
    @Test fun failedAutomaticReplacementKeepsCurrentSourceAndReportsFailureWithoutCallback() = runTest(dispatcher) {
        val current = row("old", "old-source"); val candidate = row("failed")
        val change = FakeChange().apply { failingRows += candidate.id }
        val model = vm(change, FakeSearch(listOf(current, candidate))); val owner = own(model)
        try { ready(model); model.deleteSource(current.id); model.state.first { it.error != null && !it.changing }
            assertNull(model.state.value.pendingReceipt); assertTrue(change.deleted.isEmpty()); assertTrue(model.state.value.rows.contains(current))
        } finally { owner.clear() }
    }
    @Test fun pendingNormalReceiptSurvivesProcessRestoreAndConsumptionFinishesExactlyOnceWithoutHostReplay() = runTest(dispatcher) {
        val change = FakeChange(); val search = FakeSearch(listOf(row("target"))); val saved = SavedStateHandle()
        val model = vm(change, search, saved = saved); val owner = own(model)
        try { ready(model); model.choose("target"); val pending = model.state.first { it.pendingReceipt != null }
            val restored = vm(change, search, saved = copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(pending.pendingReceipt, restored.state.value.pendingReceipt)
                val receipt = restored.prepareReceipt(pending.pendingReceipt!!); restored.consumeReceipt(receipt); restored.consumeReceipt(receipt); runCurrent()
                assertTrue(restored.state.value.finished); assertNull(restored.state.value.pendingReceipt)
                val last = vm(change, search, saved = copy(saved)); val finalOwner = own(last)
                try { ready(last); assertTrue(last.state.value.finished); assertNull(last.state.value.pendingReceipt); assertEquals(listOf("target"), change.prepared) }
                finally { finalOwner.clear() }
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun cancelledNonCooperativePreparationCannotPublishLateFailureAndAutomaticChangeIgnoresCancel() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val change = FakeChange().apply { prepareHook = { withContext(NonCancellable) { gate.await(); error("late") } } }
        val model = vm(change, FakeSearch(listOf(row("old", "old-source"), row("target")))); val owner = own(model)
        try { ready(model); model.choose("target"); model.state.first { it.changing }; runCurrent(); model.cancelChange(); gate.complete(Unit); runCurrent()
            assertFalse(model.state.value.changing); assertNull(model.state.value.error); assertNull(model.state.value.pendingReceipt)
            val next = CompletableDeferred<Unit>(); change.prepareHook = { next.await() }
            model.deleteSource("old"); model.state.first { it.changing }; assertFalse(model.state.value.changeCancelable)
            model.cancelChange(); assertTrue(model.state.value.changing)
            next.complete(Unit); model.state.first { it.pendingReceipt != null }
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun emptySelectedGroupOnlyResetsGlobalGroupAndStartsAllAfterConfirmation() = runTest(dispatcher) {
        val prefs = FakePrefs().apply { value = value.copy(group = "Group") }
        val search = FakeSearch().apply { searchUpdates = flow { emit(ChapterSourceSearchUpdate(emptyList(), false, effectiveGroup = "Group")) } }
        val model = vm(search = search, prefs = prefs); val owner = own(model)
        try { ready(model); model.state.first { it.emptyGroup }; assertEquals("Group", prefs.value.group)
            model.dismissEmptyGroup(); assertEquals("Group", prefs.value.group)
            search.searchUpdates = flow { emit(ChapterSourceSearchUpdate(emptyList(), false)) }
            model.searchAll(); model.state.first { it.request?.group == "" && !it.busy }; runCurrent()
            assertEquals("", prefs.value.group); assertEquals(listOf("Group", ""), search.searched.map { it.first.group })
        } finally { owner.clear() }
    }
    @Test fun measurementRequestedDuringSearchIsDeferredOnceUntilSearchCompletes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val target = row("target")
        val search = FakeSearch(listOf(target)).apply { searchUpdates = flow { emit(ChapterSourceSearchUpdate(listOf(target), true)); gate.await(); emit(ChapterSourceSearchUpdate(listOf(target), false)) } }
        val model = vm(search = search); val owner = own(model)
        try { ready(model); model.startSearch(); model.state.first { it.searching }; model.toggle(ChapterSourceOption.WordCount)
            model.state.first { it.request?.loadWordCount == true && !it.busy }; assertEquals(0, search.measurements)
            gate.complete(Unit); runCurrent(); assertEquals(1, search.measurements)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun stoppedNonCooperativeSearchCannotPublishLateErrorAndSingleOriginRefreshPassesExistingRows() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val existing = row("target")
        val search = FakeSearch(listOf(existing)).apply { searchUpdates = flow { withContext(NonCancellable) { gate.await(); error("late search") } } }
        val model = vm(search = search); val owner = own(model)
        try { ready(model); model.startSearch("edited"); runCurrent()
            assertEquals("edited", search.searched.single().second); assertEquals(listOf(existing), search.previous.single())
            model.stopSearch(); gate.complete(Unit); runCurrent(); assertFalse(model.state.value.searching); assertNull(model.state.value.error)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun scoreToggleAndCurrentBookUpdatesPreserveAllRowsAndReflectNewReaderOrigin() = runTest(dispatcher) {
        val change = FakeChange(); val model = vm(change, FakeSearch(listOf(row("target")))); val owner = own(model)
        try { ready(model); model.score("target", 1); model.state.first { it.rows.single().score == 1 && !it.busy }
            model.score("target", 1); model.state.first { it.rows.single().score == 0 && !it.busy }
            assertEquals(listOf(1, 0), change.scores)
            model.updateCurrent(Book(bookUrl = "target", originName = "New current", type = BookType.text)); model.state.first { it.request?.currentBookUrl == "target" }
            assertEquals("New current", model.state.value.originName); assertNull(model.state.value.request!!.originalBookJson)
        } finally { owner.clear() }
    }
    @Test fun initialReadFailureCannotWriteBlankSessionAndExplicitRetryLoadsOriginalMetadata() = runTest(dispatcher) {
        val change = FakeChange().apply { readFailure = true }; val model = vm(change); val owner = own(model)
        try { model.state.first { !it.loading && it.error != null }; assertNull(model.state.value.request); assertTrue(change.sessions.isEmpty())
            model.choose("target"); assertTrue(change.prepared.isEmpty())
            change.readFailure = false; model.retry(); ready(model); assertEquals("old", model.state.value.request!!.currentBookUrl)
        } finally { owner.clear() }
    }
    @Test fun clickingCurrentBookNeverRequestsChangeButLeavesOtherRowsAvailable() = runTest(dispatcher) {
        val change = FakeChange(); val model = vm(change, FakeSearch(listOf(row("old", "old-source"), row("target")))); val owner = own(model)
        try { ready(model); model.choose("old"); runCurrent(); assertTrue(change.prepared.isEmpty())
            model.choose("target"); model.state.first { it.pendingReceipt != null }; assertEquals(listOf("target"), change.prepared)
        } finally { owner.clear() }
    }
    @Test fun restoringDiskRevisionLargerThanCurrentClockPersistsEverySubsequentDraftAboveDiskRevision() = runTest(dispatcher) {
        val change = FakeChange(); val session = "restored-session"; val oldRevision = System.nanoTime() + 1000000000000L
        change.sessions[session] = snapshot(listOf(row("target"))).copy(revision = oldRevision)
        val model = vm(change, FakeSearch(listOf(row("target"))), saved = SavedStateHandle(mapOf("session" to session)))
        val owner = own(model)
        try { ready(model); model.query("Book"); runCurrent()
            assertTrue(change.sessions.getValue(session).revision > oldRevision)
            assertEquals("Book", change.sessions.getValue(session).request.query)
        } finally { owner.clear() }
    }
    @Test fun manualRefreshWithWordCountOffReplacesRunningSearchAndRefreshesAllExistingResults() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val target = row("target")
        val search = FakeSearch(listOf(target)).apply { searchUpdates = flow { emit(ChapterSourceSearchUpdate(listOf(target), true)); gate.await() } }
        val model = vm(search = search); val owner = own(model)
        try { ready(model); model.startSearch(); model.state.first { it.searching }; assertFalse(model.state.value.request!!.loadWordCount)
            model.refreshMeasurements(); runCurrent()
            assertEquals(1, search.measurements); assertEquals(listOf(false), search.missingOnly)
            assertFalse(model.state.value.searching); assertEquals(listOf(target), model.state.value.rows)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun cachedSourceTitleQuerySurvivesOptionProjectionWhileNewSearchResultsUseBookTitleAndLateOldQueryCannotReplaceNewQuery() = runTest(dispatcher) {
        val cached = row("cached").copy(originName = "SourceOnly"); val next = row("new").copy(name = "new")
        val search = FakeSearch(listOf(cached)); val model = vm(search = search); val owner = own(model)
        val gate = CompletableDeferred<Unit>()
        try { ready(model); model.query("SourceOnly"); runCurrent()
            assertEquals(listOf(cached), model.state.value.rows)
            model.optionsChanged(false); runCurrent(); assertEquals(listOf(cached), model.state.value.rows)
            search.cachedHandler = { request ->
                if (request.query == "old") withContext(NonCancellable) { gate.await(); ChapterSourceSearchUpdate(listOf(cached), false) }
                else ChapterSourceSearchUpdate(listOf(next), false)
            }
            model.query("old"); runCurrent(); model.query("new"); runCurrent(); gate.complete(Unit); runCurrent()
            assertEquals("new", model.state.value.request!!.query); assertEquals(listOf(next), model.state.value.rows)
            search.searchUpdates = flowOf(ChapterSourceSearchUpdate(listOf(cached, next), false))
            model.startSearch(); runCurrent(); assertEquals(listOf(next), model.state.value.rows)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun relativeReferenceWarningIsOncePerOperationAndConsumedWarningDoesNotReplayOnProcessRestore() = runTest(dispatcher) {
        val changes = FakeChange(); val saved = SavedStateHandle(); val search = FakeSearch(listOf(row("target")))
        val prefs = FakePrefs().apply { value = value.copy(wordCount = true, filterMode = 2, minimum = 40, maximum = 60) }
        val model = vm(changes, search, prefs, saved); val owner = own(model)
        try { ready(model); model.state.first { it.relativeWarning }; model.consumeWarning(); runCurrent()
            assertFalse(model.state.value.relativeWarning)
            val restored = vm(changes, search, prefs, copy(saved)); val other = own(restored)
            try { ready(restored); runCurrent(); assertFalse(restored.state.value.relativeWarning)
                restored.refreshMeasurements(); restored.state.first { it.relativeWarning }
                restored.consumeWarning(); runCurrent(); assertFalse(restored.state.value.relativeWarning)
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    private class FakePrefs : ChapterSourceSettingsRepository {
        var value = ChapterSourceSettings("", true, false, false, false, false, 0, 0, 0)
        override suspend fun load() = value
        override suspend fun group(value: String): ChapterSourceSettings { this.value = this.value.copy(group = value); return this.value }
        override suspend fun toggle(option: ChapterSourceOption): ChapterSourceSettings {
            value = when (option) {
                ChapterSourceOption.Author -> value.copy(author = !value.author); ChapterSourceOption.Info -> value.copy(info = !value.info)
                ChapterSourceOption.Toc -> value.copy(toc = !value.toc); ChapterSourceOption.WordCount -> value.copy(wordCount = !value.wordCount)
                ChapterSourceOption.ResponseTime -> value.copy(responseTime = !value.responseTime)
            }; return value
        }
    }
    private class FakeSearch(private val cachedRows: List<ChapterSourceSearchRow> = emptyList()) : BookSourceSearchRepository {
        var searchUpdates: Flow<ChapterSourceSearchUpdate> = flow { emit(ChapterSourceSearchUpdate(cachedRows, false)) }
        val searched = mutableListOf<Pair<ChapterSourceSearchRequest, String?>>(); val previous = mutableListOf<List<ChapterSourceSearchRow>>()
        var cachedHandler: suspend (ChapterSourceSearchRequest) -> ChapterSourceSearchUpdate = { request -> ChapterSourceSearchUpdate(cachedRows, false, effectiveGroup = request.group) }
        var measurements = 0; val missingOnly = mutableListOf<Boolean>()
        override suspend fun cached(request: ChapterSourceSearchRequest) = cachedHandler(request)
        override suspend fun project(request: ChapterSourceSearchRequest, rows: List<ChapterSourceSearchRow>) = rows
        override fun search(request: ChapterSourceSearchRequest, previous: List<ChapterSourceSearchRow>) = search(request, previous, null)
        override fun search(request: ChapterSourceSearchRequest, previous: List<ChapterSourceSearchRow>, origin: String?): Flow<ChapterSourceSearchUpdate> {
            searched += request to origin; this.previous += previous; return searchUpdates
        }
        override fun measure(request: ChapterSourceSearchRequest, previous: List<ChapterSourceSearchRow>, missingOnly: Boolean): Flow<ChapterSourceSearchUpdate> {
            measurements++; this.missingOnly += missingOnly; return flowOf(ChapterSourceSearchUpdate(previous, false, effectiveGroup = request.group))
        }
    }
    private class FakeChange : BookSourceChangeRepository {
        val sessions = mutableMapOf<String, BookSourceChangeSession>(); val receipts = mutableMapOf<String, BookSourceChangeReceipt>()
        val prepared = mutableListOf<String>(); val failingRows = mutableSetOf<String>(); val deleted = mutableListOf<String>(); val scores = mutableListOf<Int>()
        var readFailure = false; var prepareHook: suspend () -> Unit = {}
        override suspend fun prepare(session: String, row: ChapterSourceSearchRow, deleteAfter: ChapterSourceSearchRow?): BookSourceChangeReceipt {
            prepared += row.id; prepareHook(); if (row.id in failingRows) error("failed ${row.id}")
            return BookSourceChangeReceipt("receipt-${prepared.size}", row.json, "source-json", emptyList(), deleteAfter).also { receipts[it.key] = it }
        }
        override suspend fun read(session: String): BookSourceChangeSession? { if (readFailure) error("read failed"); return sessions[session] }
        override suspend fun write(session: String, snapshot: BookSourceChangeSession) {
            if ((sessions[session]?.revision ?: -1) <= snapshot.revision) sessions[session] = snapshot
        }
        override suspend fun receipt(session: String, key: String) = receipts.getValue(key)
        override suspend fun consume(session: String, key: String) { receipts[key] = receipt(session, key).copy(consumed = true) }
        override suspend fun complete(session: String, key: String) {
            val value = receipt(session, key); if (value.acknowledged) return
            value.deleteAfter?.let { deleted += it.id }; receipts[key] = value.copy(acknowledged = true)
        }
        override suspend fun delete(row: ChapterSourceSearchRow) { deleted += row.id }
        override suspend fun disable(row: ChapterSourceSearchRow) = Unit
        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = Unit
        override suspend fun score(row: ChapterSourceSearchRow, score: Int) { scores += score }
    }
}
