package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TocHighlightsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<TocHighlightsViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : TocHighlightsRepository {
        val source = MutableStateFlow(listOf(TocHighlightRow(1, 1, "One", "Original", "Note", 0), TocHighlightRow(2, 3, "Three", "", "", 0), TocHighlightRow(3, 5, "Five", "", "", 0)))
        var collections = 0; var active = 0; var maximum = 0; var gate: CompletableDeferred<Unit>? = null; var row: BookHighlight? = BookHighlight(time = 2, bookUrl = "book", chapterIndex = 3, chapterPos = 17, chapterName = "Three", bookText = "Full", note = "Note")
        val checkpoints = mutableMapOf<String, TocHighlightsCheckpoint>()
        var restoreGate: CompletableDeferred<Unit>? = null
        override suspend fun checkpoint(session: String): TocHighlightsCheckpoint? { val value = checkpoints[session]; withContext(NonCancellable) { restoreGate?.await() }; return value }
        override suspend fun release(session: String) { checkpoints.remove(session) }
        override suspend fun checkpoint(session: String, value: TocHighlightsCheckpoint) { if ((checkpoints[session]?.revision ?: -1) <= value.revision) checkpoints[session] = value }
        override fun observe(parameters: TocHighlightsParameters) = flow {
            collections++; active++; maximum = maxOf(maximum, active)
            try { if (parameters.supported) emitAll(source) else emit(emptyList()) } finally { active-- }
        }
        override suspend fun resolve(parameters: TocHighlightsParameters, id: Long): TocHighlightTarget? {
            withContext(NonCancellable) { gate?.await() }; return row?.takeIf { it.time == id && it.bookUrl == parameters.bookUrl && parameters.supported }?.copy()?.let { TocHighlightTarget(it, it.chapterIndex) }
        }
    }
    private val initial = TocHighlightsParameters("book", null, 4)
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = TocHighlightsViewModel(repo, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun hostRefreshRestartsCurrentChapterMappingWithoutStackingCollectors() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); model.bind(initial); runCurrent(); assertEquals(2, repo.collections)
        model.bind(initial.copy(search = "query")); runCurrent(); assertEquals(3, repo.collections); assertEquals(1, repo.maximum)
        model.unbind(); runCurrent(); assertEquals(0, repo.active); model.bind(initial.copy(search = "query")); runCurrent(); assertEquals(4, repo.collections)
    }
    @Test fun roomEmissionsUseOriginalPreviousChapterScrollRuleAndNeverReuseConsumedTokens() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); assertEquals(1, model.state.value.scrollTarget)
        val first = model.state.value.scrollRequest; model.scrolled(first); repo.source.value = repo.source.value + TocHighlightRow(4, 2, "Two", "", "", 0); runCurrent()
        val next = model.state.value.scrollRequest; assertTrue(next > first); assertEquals(3, model.state.value.scrollTarget)
        model.scrolled(first); assertEquals(next, model.state.value.scrollRequest); model.scrolled(next); assertEquals(0L, model.state.value.scrollRequest)
    }
    @Test fun emptyAndNoPreviousChapterFallBackToZeroAndCurrentChapterChangeRebinds() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial.copy(chapter = 0)); runCurrent(); assertEquals(0, model.state.value.scrollTarget)
        repo.source.value = emptyList(); runCurrent(); assertEquals(0, model.state.value.scrollTarget); assertTrue(model.state.value.loaded)
        model.bind(initial.copy(chapter = 5)); runCurrent(); assertEquals(2, repo.collections)
    }
    @Test fun processRestoreKeepsSmallOpenTicketAndMonotonicScrollAcrossNewCollector() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.bind(initial); runCurrent(); first.open(2, true)
        val ticket = first.state.value.open!!; val scroll = first.state.value.scrollRequest; first.stop(); val restored = model(repo, copy(saved)); runCurrent()
        assertEquals(ticket, restored.state.value.open); assertTrue(restored.state.value.scrollRequest > scroll)
        assertEquals(17, restored.resolve(ticket)!!.highlight.chapterPos); assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false })
    }
    @Test fun duplicateClicksCannotReplaceTicketAndAckRequiresExactNonce() = test {
        val model = model(Fake()); model.bind(initial); runCurrent(); model.open(2, false); val ticket = model.state.value.open!!; model.open(3, true)
        assertEquals(ticket, model.state.value.open); assertNull(model.delivered("unknown")); assertEquals(ticket, model.delivered(ticket.nonce)); assertNull(model.delivered(ticket.nonce))
        model.open(3, true); assertTrue(model.state.value.open!!.edit)
    }
    @Test fun changingBookIdentityClearsOldTicketAndLateResolveCannotReadNewBook() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); model.open(2, false); val ticket = model.state.value.open!!
        repo.gate = CompletableDeferred(); var result: TocHighlightTarget? = repo.row?.let { TocHighlightTarget(it, it.chapterIndex) }
        val read = launch { result = model.resolve(ticket) }; runCurrent(); model.bind(initial.copy(bookUrl = "Other")); runCurrent(); repo.gate!!.complete(Unit); read.join()
        assertNull(result); assertNull(model.state.value.open)
    }
    @Test fun canceledNonCooperativeReadDoesNotConsumeTicketAndRetrySeesLatestMetadata() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); model.open(2, true); val ticket = model.state.value.open!!
        repo.gate = CompletableDeferred(); var published = false; val read = launch { model.resolve(ticket); published = true }; runCurrent()
        read.cancel(); repo.row = repo.row!!.copy(chapterPos = 91, bookText = "Big".repeat(100000)); repo.gate!!.complete(Unit); runCurrent()
        assertFalse(published); assertEquals(ticket, model.state.value.open); assertEquals(91, model.resolve(ticket)!!.highlight.chapterPos)
    }
    @Test fun deletedTargetCanBeConsumedAndErrorRetryNeverStacksCollectors() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); model.open(2, false); val ticket = model.state.value.open!!
        repo.row = null; assertNull(model.resolve(ticket)); model.delivered(ticket.nonce); model.failed("Read failed"); assertNotNull(model.state.value.error)
        model.retry(); runCurrent(); assertNull(model.state.value.error); assertEquals(1, repo.maximum)
    }
    @Test fun exactLargeQueryIdentityRestoresFromOwnedCheckpointWithOnlySmallSessionInSavedState() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val full = initial.copy(bookUrl = "URL".repeat(50000), search = "Query".repeat(50000))
        val first = model(repo, saved); first.bind(full); runCurrent(); first.stop()
        assertFalse(saved.keys().contains("tocHighlights.parameters")); assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false })
        val restored = model(repo, copy(saved)); runCurrent(); assertEquals(full, restored.state.value.parameters); assertTrue(restored.state.value.loaded)
    }
    @Test fun lateDiskRestoreNeverOverridesNewHostBookOrSearch() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.bind(initial); runCurrent(); first.stop()
        repo.restoreGate = CompletableDeferred(); val restored = model(repo, copy(saved)); runCurrent()
        val latest = initial.copy(bookUrl = "New", search = "Latest"); restored.bind(latest); runCurrent(); repo.restoreGate!!.complete(Unit); runCurrent()
        assertEquals(latest, restored.state.value.parameters); assertEquals(latest, repo.checkpoints.values.single().parameters)
    }

    @Test fun immediateHostBindUsesDiskRevisionEvenWhenSavedStateSnapshotLagsBehind() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.bind(initial); runCurrent()
        val older = copy(saved); first.bind(initial.copy(search = "Later")); runCurrent(); first.bind(initial.copy(search = "Latest")); runCurrent(); first.stop()
        val diskRevision = repo.checkpoints.values.single().revision
        val restored = model(repo, older); restored.bind(initial.copy(search = "Restored host")); runCurrent()
        assertEquals("Restored host", repo.checkpoints.values.single().parameters.search)
        assertTrue(repo.checkpoints.values.single().revision > diskRevision)
    }

    @Test fun unsupportedOwnerClearsRowsAndRefusesResolvedReadAfterHostChange() = test {
        val repo = Fake(); val model = model(repo); model.bind(initial); runCurrent(); model.open(2, false)
        model.bind(initial.copy(supported = false)); runCurrent()
        assertNull(model.state.value.open); assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.resolve(TocHighlightOpen(2, false)))
    }
    @Test fun currentChapterUrlOverridesStoredIndexAndOrphansRemainUnnavigable() {
        val row = BookHighlight(chapterUrl = "chapter", chapterIndex = 3)
        assertEquals(8, io.legado.app.model.book.tocHighlightChapterIndex(row, mapOf("chapter" to 8)))
        assertNull(io.legado.app.model.book.tocHighlightChapterIndex(row, emptyMap()))
        assertEquals(3, io.legado.app.model.book.tocHighlightChapterIndex(row.copy(chapterUrl = ""), emptyMap()))
    }
    @Test fun bodySortingAndAnchorRetainRawLayoutCoordinateContract() {
        val row = BookHighlight(chapterPos = 12, chapterPosEnd = 16, layoutTitleLength = 10, bookText = "Text")
        assertEquals(2, io.legado.app.model.book.tocHighlightBodyPosition(row))
        assertEquals("Text", io.legado.app.model.book.tocHighlightAnchorText(row))
        assertEquals("", io.legado.app.model.book.tocHighlightAnchorText(row.copy(chapterPosEnd = 18)))
        assertEquals(12, io.legado.app.model.book.tocHighlightBodyPosition(row.copy(layoutTitleLength = -1)))
    }
    @Test fun swatchUsesFillThenTextThenUnderlineStrikeBoxAndEmphasis() {
        val styles = listOf(io.legado.app.help.HighlightStyle(fill = 1, textColor = 2), io.legado.app.help.HighlightStyle(textColor = 2),
            io.legado.app.help.HighlightStyle(underline = io.legado.app.help.HighlightStyle.Underline(color = 3)),
            io.legado.app.help.HighlightStyle(strike = io.legado.app.help.HighlightStyle.Deco(4)),
            io.legado.app.help.HighlightStyle(box = io.legado.app.help.HighlightStyle.Deco(5)),
            io.legado.app.help.HighlightStyle(emphasis = io.legado.app.help.HighlightStyle.Deco(6)))
        styles.forEachIndexed { index, style -> val row = BookHighlight(); row.applyStyle(style); assertEquals(index + 1, io.legado.app.model.book.tocHighlightColor(row)) }
        assertEquals(0, io.legado.app.model.book.tocHighlightColor(BookHighlight(style = "broken")))
    }
}
