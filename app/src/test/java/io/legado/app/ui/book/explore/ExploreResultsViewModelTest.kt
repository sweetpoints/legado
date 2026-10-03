package io.legado.app.ui.book.explore

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.ExploreResultsAddResult
import io.legado.app.data.repository.ExploreResultsCategory
import io.legado.app.data.repository.ExploreResultsCheckpoint
import io.legado.app.data.repository.ExploreResultsRepository
import io.legado.app.data.repository.ExploreResultsRequest
import io.legado.app.data.repository.ExploreResultsRow
import io.legado.app.data.repository.ExploreResultsSessionRepository
import io.legado.app.data.repository.ExploreResultsSource
import io.legado.app.data.repository.ExploreResultsSourceMissing
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreResultsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ExploreResultsViewModel>()
    private val request = ExploreResultsRequest("source", "Category", "large query")

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private fun model(
        saved: SavedStateHandle = SavedStateHandle(),
        repository: Results = Results(),
        disk: Sessions = Sessions(),
        navigation: Navigation = Navigation(),
    ) = ExploreResultsViewModel(saved, repository, disk, navigation).also { models += it }

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
    fun nextDeduplicatesByUrlAndStopsWhenPageAddsNoNewRows() = test {
        val repository =
            Results().apply {
                fetch = { _, page ->
                    if (page == 1) listOf(row("a"), row("b")) else listOf(row("b"))
                }
            }
        val model = model(repository = repository)
        model.load(request)
        runCurrent()
        assertEquals(listOf("a", "b"), model.state.value.rows.map { it.bookUrl })
        model.next()
        runCurrent()
        model.next()
        runCurrent()
        assertEquals(listOf(1, 2), repository.pages.map { it.second })
        assertFalse(model.state.value.checkpoint!!.hasMore)
        assertEquals(3, model.state.value.checkpoint!!.nextPage)
    }

    @Test
    fun prependKeepsAnchorOffsetAndDoesNotAdvanceBottomPage() = test {
        val disk =
            Sessions(
                ExploreResultsCheckpoint(
                    request,
                    rows = listOf(row("b")),
                    firstPage = 4,
                    displayedPage = 4,
                    nextPage = 5,
                    scrollKey = "b",
                    scrollOffset = 19,
                )
            )
        val repository = Results().apply { fetch = { _, _ -> listOf(row("a"), row("b", "new")) } }
        val model = model(repository = repository, disk = disk)
        model.load()
        runCurrent()
        model.previous()
        runCurrent()
        assertEquals(listOf("a", "b"), model.state.value.rows.map { it.bookUrl })
        assertEquals("new", model.state.value.rows[1].name)
        assertEquals(1, model.state.value.scrollTargetIndex)
        assertEquals(19, model.state.value.scrollTargetOffset)
        assertEquals(5, model.state.value.checkpoint!!.nextPage)
        assertEquals(3, model.state.value.checkpoint!!.firstPage)
    }

    @Test
    fun changingCategoryRejectsNonCooperativeOldPageAndCache() = test {
        val gate = CompletableDeferred<Unit>()
        val repository =
            Results().apply {
                fetch = { url, _ ->
                    if (url == request.exploreUrl) withContext(NonCancellable) { gate.await() }
                    listOf(row(url))
                }
            }
        val model = model(repository = repository)
        model.load(request)
        runCurrent()
        model.category(ExploreResultsCategory("New", "new-url"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new-url"), model.state.value.rows.map { it.bookUrl })
        assertEquals(listOf("new-url"), repository.cached.map { it.bookUrl })
        assertEquals(2, model.state.value.checkpoint!!.nextPage)
    }

    @Test
    fun pagePickerRestoresSmallDraftAndJumpStartsExactSelectedPage() = test {
        val saved = SavedStateHandle()
        val disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a"))))
        val model = model(saved = saved, disk = disk)
        model.load()
        runCurrent()
        model.showPagePicker()
        model.pagePicker(9999)
        val repository = Results()
        val restored = model(saved = copy(saved), repository = repository, disk = disk)
        restored.load()
        runCurrent()
        assertEquals(999, restored.state.value.pagePicker)
        restored.confirmPage()
        runCurrent()
        assertEquals(listOf(999), repository.pages.map { it.second })
        assertEquals(1000, restored.state.value.checkpoint!!.nextPage)
        assertNull(restored.state.value.pagePicker)
    }

    @Test
    fun continuousRestoreRetainsLargeInputAndEofOutsideSavedState() = test {
        val large = request.copy(exploreUrl = "x".repeat(2_000_000))
        val disk =
            Sessions(
                ExploreResultsCheckpoint(
                    large,
                    rows = listOf(row("a")),
                    nextPage = 8,
                    hasMore = false,
                )
            )
        val saved = SavedStateHandle()
        val first = model(saved = saved, disk = disk)
        first.load()
        runCurrent()
        val secondSaved = copy(saved)
        val second = model(saved = secondSaved, disk = disk)
        second.load()
        runCurrent()
        val third = model(saved = copy(secondSaved), disk = disk)
        third.load()
        runCurrent()
        assertEquals(large, third.state.value.checkpoint!!.request)
        assertEquals(8, third.state.value.checkpoint!!.nextPage)
        assertFalse(third.state.value.checkpoint!!.hasMore)
        assertTrue(saved.keys().all { saved.get<Any>(it).toString().length < 100 })
    }

    @Test
    fun interruptedPageWaitsForExplicitRetry() = test {
        val disk = Sessions(ExploreResultsCheckpoint(request, pendingPage = 6, nextPage = 6))
        val repository = Results()
        val model = model(repository = repository, disk = disk)
        model.load()
        runCurrent()
        assertTrue(model.state.value.interruptedPage)
        assertTrue(repository.pages.isEmpty())
        model.retryLoad()
        runCurrent()
        assertEquals(listOf(6), repository.pages.map { it.second })
        assertFalse(model.state.value.interruptedPage)
    }

    @Test
    fun failedPageRetriesSameNumber() = test {
        val repository = Results().apply { fetch = { _, _ -> error("network") } }
        val model = model(repository = repository)
        model.load(request)
        runCurrent()
        assertEquals("network", model.state.value.checkpoint!!.error)
        repository.fetch = { _, _ -> listOf(row("retry")) }
        model.retryLoad()
        runCurrent()
        assertEquals(listOf(1, 1), repository.pages.map { it.second })
        assertEquals("retry", model.state.value.rows.single().bookUrl)
    }

    @Test
    fun confirmationSnapshotAndCancelDoNotCallShelf() = test {
        val disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("old"))))
        val repository = Results()
        val model = model(repository = repository, disk = disk)
        model.load()
        runCurrent()
        model.askAdd()
        model.next()
        runCurrent()
        assertEquals(listOf("old"), model.state.value.checkpoint!!.addRows!!.map { it.bookUrl })
        model.cancelAdd()
        runCurrent()
        assertNull(model.state.value.checkpoint!!.addRows)
        assertEquals(0, repository.adds)
    }

    @Test
    fun acceptedAddAfterStopPersistsReceiptAndCannotBeRepeatedOnRestore() = test {
        val gate = CompletableDeferred<Unit>()
        val repository = Results().apply { addGate = gate }
        val disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a"))))
        val saved = SavedStateHandle()
        val model = model(saved = saved, repository = repository, disk = disk)
        model.load()
        runCurrent()
        model.askAdd()
        runCurrent()
        model.confirmAdd()
        model.confirmAdd()
        runCurrent()
        model.stop()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, repository.adds)
        assertEquals(1, disk.value!!.addedCount)
        assertNull(disk.value!!.addRows)
        val restored = model(saved = copy(saved), repository = repository, disk = disk)
        restored.load()
        runCurrent()
        restored.confirmAdd()
        runCurrent()
        assertEquals(1, repository.adds)
        assertEquals(1, restored.state.value.checkpoint!!.addedCount)
    }

    @Test
    fun failedReceiptRetriesAcceptedResultWithoutRepeatingShelfMutation() = test {
        val disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a"))))
        val repository = Results()
        val model = model(repository = repository, disk = disk)
        model.load()
        runCurrent()
        model.askAdd()
        runCurrent()
        disk.rejectReceipts = true
        model.confirmAdd()
        runCurrent()
        assertEquals(1, repository.adds)
        disk.rejectReceipts = false
        model.confirmAdd()
        runCurrent()
        assertEquals(1, repository.adds)
        assertNull(model.state.value.checkpoint!!.addRows)
        assertEquals(1, model.state.value.checkpoint!!.addedCount)
    }

    @Test
    fun scrollRequestTokensNeverReuseAnAcknowledgedValue() = test {
        val model =
            model(disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a")))))
        model.load()
        runCurrent()
        val old = model.state.value.scrollRequest
        model.scrolled(old)
        model.showPagePicker()
        model.pagePicker(3)
        model.confirmPage()
        runCurrent()
        val current = model.state.value.scrollRequest
        assertNotEquals(old, current)
        model.scrolled(old)
        assertEquals(current, model.state.value.scrollRequest)
    }

    @Test
    fun abandonedLateDetailPreparationOnlyReleasesOwnedTicket() = test {
        val gate = CompletableDeferred<Unit>()
        val navigation = Navigation().apply { prepareGate = gate }
        val model =
            model(
                navigation = navigation,
                disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a")))),
            )
        model.load()
        runCurrent()
        model.detail("a")
        runCurrent()
        val nonce = model.state.value.checkpoint!!.detailNonce!!
        val preparation = launch { model.prepareDetail(nonce) }
        runCurrent()
        model.category(ExploreResultsCategory("New", "new"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        preparation.join()
        assertEquals(listOf("owned-ticket"), navigation.abandoned)
        assertNull(model.state.value.checkpoint!!.detailTicket)
    }

    @Test
    fun detailClaimDefersAndRestoresPreparedTicketWithoutPreparingAgain() = test {
        val saved = SavedStateHandle()
        val disk = Sessions(ExploreResultsCheckpoint(request, rows = listOf(row("a"))))
        val navigation = Navigation()
        val model = model(saved, disk = disk, navigation = navigation)
        model.load()
        runCurrent()
        model.detail("a")
        runCurrent()
        val nonce = model.state.value.checkpoint!!.detailNonce!!
        assertEquals("owned-ticket", model.prepareDetail(nonce))
        assertTrue(model.detailClaimed(nonce))
        assertFalse(model.detailClaimed(nonce))
        model.detailDeferred(nonce)
        val restored = model(copy(saved), disk = disk, navigation = navigation)
        restored.load()
        runCurrent()
        assertEquals("owned-ticket", restored.prepareDetail(nonce))
        assertEquals(1, navigation.prepares)
        assertTrue(restored.detailClaimed(nonce))
    }

    @Test
    fun sourceMissingCanRetryWithoutReplacingOriginalInput() = test {
        val repository = Results().apply { missing = true }
        val model = model(repository = repository)
        model.load(request)
        runCurrent()
        assertTrue(model.state.value.missingSource)
        repository.missing = false
        model.retryLoad()
        runCurrent()
        assertTrue(model.state.value.loaded)
        assertEquals(request, model.state.value.checkpoint!!.request)
    }

    @Test
    fun closeBeforeNonCooperativeLoadFinishesDoesNotPublishLoadedState() = test {
        val gate = CompletableDeferred<Unit>()
        val repository = Results().apply { sourceGate = gate }
        val model = model(repository = repository)
        model.load(request)
        runCurrent()
        model.close()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
        assertFalse(model.state.value.loaded)
    }

    @Test
    fun reachingTopOfFirstLoadedPageRestoresPageIndicatorWithoutFetching() = test {
        val repository = Results()
        val disk =
            Sessions(
                ExploreResultsCheckpoint(
                    request,
                    rows = listOf(row("a")),
                    firstPage = 1,
                    displayedPage = 4,
                    nextPage = 5,
                )
            )
        val model = model(repository = repository, disk = disk)
        model.load()
        runCurrent()
        model.previous()
        runCurrent()
        assertEquals(1, model.state.value.checkpoint!!.displayedPage)
        assertEquals(5, model.state.value.checkpoint!!.nextPage)
        assertTrue(repository.pages.isEmpty())
    }

    private fun copy(saved: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun row(url: String, name: String = url) =
        ExploreResultsRow(
            key = url,
            bookUrl = url,
            name = name,
            author = "author",
            origin = "source",
            coverUrl = null,
            intro = null,
            latestChapter = null,
            kinds = emptyList(),
            metadata = "{}",
        )

    private class Sessions(var value: ExploreResultsCheckpoint? = null) :
        ExploreResultsSessionRepository {
        var rejectReceipts = false

        override suspend fun prepare(sourceUrl: String, title: String, exploreUrl: String): String =
            UUID.randomUUID().toString()

        override suspend fun read(sessionId: String): ExploreResultsCheckpoint? = value

        override suspend fun write(
            sessionId: String,
            checkpoint: ExploreResultsCheckpoint,
        ): Boolean {
            if (rejectReceipts && checkpoint.addedCount != null) error("receipt disk failure")
            if (value != null && value!!.revision >= checkpoint.revision) return false
            value = checkpoint
            return true
        }

        override suspend fun release(sessionId: String) {
            value = null
        }
    }

    private class Results : ExploreResultsRepository {
        var fetch: suspend (String, Int) -> List<ExploreResultsRow> = { _, page ->
            listOf(
                ExploreResultsRow(
                    "page-$page",
                    "page-$page",
                    "Page $page",
                    "",
                    "source",
                    null,
                    null,
                    null,
                    emptyList(),
                    "{}",
                )
            )
        }
        val pages = mutableListOf<Pair<String, Int>>()
        val cached = mutableListOf<ExploreResultsRow>()
        var adds = 0
        var addGate: CompletableDeferred<Unit>? = null
        var sourceGate: CompletableDeferred<Unit>? = null
        var missing = false
        var categoriesVisible = false

        override suspend fun source(sourceUrl: String): ExploreResultsSource {
            sourceGate?.let { withContext(NonCancellable) { it.await() } }
            if (missing) throw ExploreResultsSourceMissing()
            return ExploreResultsSource(sourceUrl, "{}")
        }

        override suspend fun categories(
            source: ExploreResultsSource
        ): List<ExploreResultsCategory> = emptyList()

        override suspend fun page(
            source: ExploreResultsSource,
            url: String,
            page: Int,
        ): List<ExploreResultsRow> {
            pages += url to page
            return fetch(url, page)
        }

        override suspend fun cache(rows: List<ExploreResultsRow>) {
            cached += rows
        }

        override fun membership(): Flow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun showCategories(): Boolean = categoriesVisible

        override suspend fun showCategories(value: Boolean) {
            categoriesVisible = value
        }

        override suspend fun loadCoverOnlyWifi(): Boolean = false

        override suspend fun addToShelf(rows: List<ExploreResultsRow>): ExploreResultsAddResult {
            adds++
            addGate?.await()
            return ExploreResultsAddResult(rows.size, 0)
        }
    }

    private class Navigation : ExploreResultsBookNavigation {
        var prepares = 0
        var prepareGate: CompletableDeferred<Unit>? = null
        val abandoned = mutableListOf<String>()

        override suspend fun prepare(row: ExploreResultsRow): String {
            prepares++
            prepareGate?.let { withContext(NonCancellable) { it.await() } }
            return "owned-ticket"
        }

        override suspend fun abandon(ticket: String) {
            abandoned += ticket
        }
    }
}
