package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TocBookmarksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<TocBookmarksViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : TocBookmarksRepository {
        val source =
            MutableStateFlow(
                listOf(
                    TocBookmarkRow(1, 1, "One", "Original", "Note"),
                    TocBookmarkRow(2, 3, "Three", "", ""),
                    TocBookmarkRow(3, 5, "Five", "", ""),
                )
            )
        var collections = 0
        var active = 0
        var maximum = 0
        var gate: CompletableDeferred<Unit>? = null
        var row: Bookmark? = Bookmark(2, "Book", "Author", 3, 17, "Three", "Full", "Note")
        val checkpoints = mutableMapOf<String, TocBookmarksCheckpoint>()
        var restoreGate: CompletableDeferred<Unit>? = null

        override suspend fun checkpoint(session: String): TocBookmarksCheckpoint? {
            val value = checkpoints[session]
            withContext(NonCancellable) { restoreGate?.await() }
            return value
        }

        override suspend fun release(session: String) {
            checkpoints.remove(session)
        }

        override suspend fun checkpoint(session: String, value: TocBookmarksCheckpoint) {
            if ((checkpoints[session]?.revision ?: -1) <= value.revision)
                checkpoints[session] = value
        }

        override fun observe(parameters: TocBookmarksParameters) = flow {
            collections++
            active++
            maximum = maxOf(maximum, active)
            try {
                emitAll(source)
            } finally {
                active--
            }
        }

        override suspend fun resolve(parameters: TocBookmarksParameters, id: Long): Bookmark? {
            withContext(NonCancellable) { gate?.await() }
            return row?.takeIf {
                    it.time == id &&
                        it.bookName == parameters.name &&
                        it.bookAuthor == parameters.author
                }
                ?.copy()
        }
    }

    private val initial = TocBookmarksParameters("Book", "Author", null, 4)

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        TocBookmarksViewModel(repo, saved).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun repeatedSameBindKeepsOneCollectorAndQueryChangeCancelsBeforeStartingNew() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial)
        runCurrent()
        model.bind(initial)
        runCurrent()
        assertEquals(1, repo.collections)
        model.bind(initial.copy(search = "query"))
        runCurrent()
        assertEquals(2, repo.collections)
        assertEquals(1, repo.maximum)
        model.unbind()
        runCurrent()
        assertEquals(0, repo.active)
        model.bind(initial.copy(search = "query"))
        runCurrent()
        assertEquals(3, repo.collections)
    }

    @Test
    fun roomEmissionsUseOriginalPreviousChapterScrollRuleAndNeverReuseConsumedTokens() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial)
        runCurrent()
        assertEquals(1, model.state.value.scrollTarget)
        val first = model.state.value.scrollRequest
        model.scrolled(first)
        repo.source.value = repo.source.value + TocBookmarkRow(4, 2, "Two", "", "")
        runCurrent()
        val next = model.state.value.scrollRequest
        assertTrue(next > first)
        assertEquals(3, model.state.value.scrollTarget)
        model.scrolled(first)
        assertEquals(next, model.state.value.scrollRequest)
        model.scrolled(next)
        assertEquals(0L, model.state.value.scrollRequest)
    }

    @Test
    fun emptyAndNoPreviousChapterFallBackToZeroAndCurrentChapterChangeRebinds() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial.copy(chapter = 0))
        runCurrent()
        assertEquals(0, model.state.value.scrollTarget)
        repo.source.value = emptyList()
        runCurrent()
        assertEquals(0, model.state.value.scrollTarget)
        assertTrue(model.state.value.loaded)
        model.bind(initial.copy(chapter = 5))
        runCurrent()
        assertEquals(2, repo.collections)
    }

    @Test
    fun processRestoreKeepsSmallOpenTicketAndMonotonicScrollAcrossNewCollector() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.bind(initial)
        runCurrent()
        first.open(2, true)
        val ticket = first.state.value.open!!
        val scroll = first.state.value.scrollRequest
        first.stop()
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(ticket, restored.state.value.open)
        assertTrue(restored.state.value.scrollRequest > scroll)
        assertEquals(17, restored.resolve(ticket)!!.chapterPos)
        assertTrue(
            saved.keys().all {
                (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false
            }
        )
    }

    @Test
    fun duplicateClicksCannotReplaceTicketAndAckRequiresExactNonce() = test {
        val model = model(Fake())
        model.bind(initial)
        runCurrent()
        model.open(2, false)
        val ticket = model.state.value.open!!
        model.open(3, true)
        assertEquals(ticket, model.state.value.open)
        assertNull(model.delivered("unknown"))
        assertEquals(ticket, model.delivered(ticket.nonce))
        assertNull(model.delivered(ticket.nonce))
        model.open(3, true)
        assertTrue(model.state.value.open!!.edit)
    }

    @Test
    fun changingBookIdentityClearsOldTicketAndLateResolveCannotReadNewBook() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial)
        runCurrent()
        model.open(2, false)
        val ticket = model.state.value.open!!
        repo.gate = CompletableDeferred()
        var result: Bookmark? = repo.row
        val read = launch { result = model.resolve(ticket) }
        runCurrent()
        model.bind(initial.copy(author = "Other"))
        runCurrent()
        repo.gate!!.complete(Unit)
        read.join()
        assertNull(result)
        assertNull(model.state.value.open)
    }

    @Test
    fun canceledNonCooperativeReadDoesNotConsumeTicketAndRetrySeesLatestMetadata() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial)
        runCurrent()
        model.open(2, true)
        val ticket = model.state.value.open!!
        repo.gate = CompletableDeferred()
        var published = false
        val read = launch {
            model.resolve(ticket)
            published = true
        }
        runCurrent()
        read.cancel()
        repo.row = repo.row!!.copy(chapterPos = 91, bookText = "Big".repeat(100000))
        repo.gate!!.complete(Unit)
        runCurrent()
        assertFalse(published)
        assertEquals(ticket, model.state.value.open)
        assertEquals(91, model.resolve(ticket)!!.chapterPos)
    }

    @Test
    fun deletedTargetCanBeConsumedAndErrorRetryNeverStacksCollectors() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind(initial)
        runCurrent()
        model.open(2, false)
        val ticket = model.state.value.open!!
        repo.row = null
        assertNull(model.resolve(ticket))
        model.delivered(ticket.nonce)
        model.failed("Read failed")
        assertNotNull(model.state.value.error)
        model.retry()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals(1, repo.maximum)
    }

    @Test
    fun exactLargeQueryIdentityRestoresFromOwnedCheckpointWithOnlySmallSessionInSavedState() =
        test {
            val repo = Fake()
            val saved = SavedStateHandle()
            val full =
                initial.copy(
                    name = "Name".repeat(50000),
                    author = "Author".repeat(40000),
                    search = "Query".repeat(50000),
                )
            val first = model(repo, saved)
            first.bind(full)
            runCurrent()
            first.stop()
            assertFalse(saved.keys().contains("tocBookmarks.parameters"))
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false
                }
            )
            val restored = model(repo, copy(saved))
            runCurrent()
            assertEquals(full, restored.state.value.parameters)
            assertTrue(restored.state.value.loaded)
        }

    @Test
    fun lateDiskRestoreNeverOverridesNewHostBookOrSearch() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.bind(initial)
        runCurrent()
        first.stop()
        repo.restoreGate = CompletableDeferred()
        val restored = model(repo, copy(saved))
        runCurrent()
        val latest = initial.copy(name = "New", search = "Latest")
        restored.bind(latest)
        runCurrent()
        repo.restoreGate!!.complete(Unit)
        runCurrent()
        assertEquals(latest, restored.state.value.parameters)
        assertEquals(latest, repo.checkpoints.values.single().parameters)
    }

    @Test
    fun immediateHostBindUsesDiskRevisionEvenWhenSavedStateSnapshotLagsBehind() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.bind(initial)
        runCurrent()
        val older = copy(saved)
        first.bind(initial.copy(search = "Later"))
        runCurrent()
        first.bind(initial.copy(search = "Latest"))
        runCurrent()
        first.stop()
        val diskRevision = repo.checkpoints.values.single().revision
        val restored = model(repo, older)
        restored.bind(initial.copy(search = "Restored host"))
        runCurrent()
        assertEquals("Restored host", repo.checkpoints.values.single().parameters.search)
        assertTrue(repo.checkpoints.values.single().revision > diskRevision)
    }
}
