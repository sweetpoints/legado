package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssArticlesPageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssArticlesPageViewModel>()
    private val repos = mutableListOf<Repo>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private val params = RssArticlesParameters("origin", "Category", "first-url")

    private class Sessions : RssArticlesSessionRepository {
        var value: RssArticlesSession? = null
        var fail = false

        override suspend fun read(session: String) = value

        override suspend fun write(session: String, value: RssArticlesSession) {
            if (fail) error("Disk")
            if (value.revision >= (this.value?.revision ?: 0)) this.value = value
        }

        override suspend fun release(session: String) {
            value = null
        }
    }

    private class Repo : RssArticlesPageRepository {
        val rows =
            MutableStateFlow(listOf(RssArticleRow("one", "One", null, "date", false, "origin")))
        var fetchGate: CompletableDeferred<Unit>? = null
        var resolveGate: CompletableDeferred<Unit>? = null
        var batch =
            RssArticlesBatch(
                listOf(
                    RssArticle(
                        origin = "origin",
                        link = "one",
                        sort = "Category",
                        title = "Latest",
                        variable = "{\"key\":\"value\"}",
                        type = 2,
                    )
                ),
                "next-url",
                true,
            )
        var fail = false
        var added = true
        val requests = mutableListOf<Pair<String, Int>>()
        var refreshes = 0
        var appends = 0

        override fun observe(parameters: RssArticlesParameters) = rows

        override suspend fun fetch(
            parameters: RssArticlesParameters,
            url: String,
            page: Int,
        ): RssArticlesBatch {
            requests += url to page
            withContext(NonCancellable) { fetchGate?.await() }
            if (fail) error("Network")
            return batch
        }

        override suspend fun refresh(
            parameters: RssArticlesParameters,
            batch: RssArticlesBatch,
            order: Long,
        ): RssArticlesCommit {
            refreshes++
            return RssArticlesCommit(order - batch.articles.size, batch.articles.isNotEmpty())
        }

        override suspend fun append(
            parameters: RssArticlesParameters,
            batch: RssArticlesBatch,
            order: Long,
        ): RssArticlesCommit {
            appends++
            return RssArticlesCommit(order - batch.articles.size, added)
        }

        override suspend fun resolve(parameters: RssArticlesParameters, key: String): RssArticle? {
            withContext(NonCancellable) { resolveGate?.await() }
            return batch.articles.firstOrNull()?.copy()
        }
    }

    private fun repo() = Repo().also { repos += it }

    private fun model(
        repo: Repo,
        sessions: Sessions = Sessions(),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = RssArticlesPageViewModel(repo, sessions, saved, { 1000L }).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                repos.forEach {
                    it.fetchGate?.complete(Unit)
                    it.resolveGate?.complete(Unit)
                }
                runCurrent()
            }
        }

    @Test
    fun nonPreloadedPageWaitsUntilVisibleAndFirstActivationFetchesOnlyOnce() = test {
        val repo = repo()
        val model = model(repo)
        model.bind(params)
        runCurrent()
        assertTrue(model.state.value.loaded)
        assertTrue(repo.requests.isEmpty())
        model.active(true)
        model.active(true)
        runCurrent()
        assertEquals(listOf("first-url" to 1), repo.requests)
        model.active(false)
        model.active(true)
        runCurrent()
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun preloadFetchesWhenHiddenAndDuplicateRefreshIsIgnoredWhileLoading() = test {
        val repo = repo()
        repo.fetchGate = CompletableDeferred()
        val model = model(repo)
        model.bind(params.copy(preload = true))
        runCurrent()
        assertTrue(model.state.value.refreshing)
        model.refresh()
        runCurrent()
        assertEquals(1, repo.requests.size)
        repo.fetchGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, repo.refreshes)
    }

    @Test
    fun refreshAndNextPageKeepOriginalUrlProgressAndRetrySameFailedPage() = test {
        val repo = repo()
        val model = model(repo)
        model.bind(params)
        model.active(true)
        runCurrent()
        model.more()
        runCurrent()
        assertEquals(listOf("first-url" to 1, "next-url" to 2), repo.requests)
        repo.fail = true
        model.more()
        runCurrent()
        assertEquals(RssArticlesRetry.NextPage, model.state.value.retry)
        repo.fail = false
        model.retry()
        runCurrent()
        assertEquals(listOf("next-url" to 3, "next-url" to 3), repo.requests.takeLast(2))
    }

    @Test
    fun failedRefreshRetriesFirstPageAndDoesNotAdvanceNextPage() = test {
        val repo = repo()
        repo.fail = true
        val model = model(repo)
        model.bind(params)
        model.active(true)
        runCurrent()
        assertEquals(RssArticlesRetry.Refresh, model.state.value.retry)
        assertFalse(model.state.value.refreshing)
        repo.fail = false
        model.retry()
        runCurrent()
        assertEquals(listOf("first-url" to 1, "first-url" to 1), repo.requests)
        assertTrue(model.state.value.hasMore)
    }

    @Test
    fun restoredPageCheckpointAvoidsRepeatedInitialFetchAndKeepsFullLargeUrlsOffSavedState() =
        test {
            val repo = repo()
            val sessions = Sessions()
            val saved = SavedStateHandle()
            val big = params.copy(sortUrl = "url" + "X".repeat(200000), query = "Q".repeat(200000))
            val first = model(repo, sessions, saved)
            first.bind(big)
            first.active(true)
            runCurrent()
            first.more()
            runCurrent()
            first.position(8, 17)
            first.stop()
            val restored = model(repo, sessions, copy(saved))
            restored.bind(big)
            restored.active(true)
            runCurrent()
            assertEquals(2, repo.requests.size)
            assertEquals(8, restored.state.value.firstVisible)
            assertEquals(17, restored.state.value.firstOffset)
            restored.more()
            runCurrent()
            assertEquals("next-url" to 3, repo.requests.last())
            assertTrue(saved.keys().none { saved.get<Any?>(it).toString().length > 100 })
        }

    @Test
    fun emptyAndDuplicateBatchesStopAutomaticMoreAndKeepThatStateAfterRestore() = test {
        val repo = repo()
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val model = model(repo, sessions, saved)
        model.bind(params)
        model.active(true)
        runCurrent()
        repo.added = false
        model.more()
        runCurrent()
        assertFalse(model.state.value.hasMore)
        model.stop()
        val restored = model(repo, sessions, copy(saved))
        restored.bind(params)
        runCurrent()
        assertFalse(restored.state.value.hasMore)
    }

    @Test
    fun nonCooperativeFetchAfterStopCannotCommitDatabaseOrPublishCompletedState() = test {
        val repo = repo()
        repo.fetchGate = CompletableDeferred()
        val model = model(repo)
        model.bind(params)
        model.active(true)
        runCurrent()
        model.stop()
        repo.fetchGate!!.complete(Unit)
        runCurrent()
        assertEquals(0, repo.refreshes)
        assertTrue(model.state.value.refreshing)
    }

    @Test
    fun changedPageOwnerClearsOldReadTicketAndRestoresScrollOnlyForSameOwner() = test {
        val model = model(repo())
        model.bind(params)
        runCurrent()
        model.position(4, 10)
        model.open("one")
        assertNotNull(model.state.value.open)
        model.bind(params.copy(sortName = "Other"))
        runCurrent()
        assertNull(model.state.value.open)
        assertEquals(0, model.state.value.firstVisible)
    }

    @Test
    fun readResolvesLatestFullMetadataAndDuplicateDeliveryIsRejected() = test {
        val repo = repo()
        val model = model(repo)
        model.bind(params)
        runCurrent()
        model.open("one")
        model.open("one")
        val ticket = model.state.value.open!!
        val value = model.resolve(ticket)!!
        assertEquals("Latest", value.title)
        assertEquals("{\"key\":\"value\"}", value.variable)
        assertEquals(2, value.type)
        assertEquals(ticket, model.delivered(ticket.nonce))
        assertNull(model.delivered(ticket.nonce))
        assertNull(model.resolve(ticket))
    }

    @Test
    fun readPendingSurvivesRecreationAndNonCooperativeResolveRejectsChangedOwner() = test {
        val repo = repo()
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val first = model(repo, sessions, saved)
        first.bind(params)
        runCurrent()
        first.open("one")
        val ticket = first.state.value.open!!
        first.stop()
        val restored = model(repo, sessions, copy(saved))
        restored.bind(params)
        runCurrent()
        assertEquals(ticket, restored.state.value.open)
        repo.resolveGate = CompletableDeferred()
        val value = async { restored.resolve(ticket) }
        runCurrent()
        restored.bind(params.copy(sourceUrl = "new"))
        runCurrent()
        repo.resolveGate!!.complete(Unit)
        runCurrent()
        assertNull(value.await())
    }

    @Test
    fun nonCooperativeReadAfterOwnerStopCannotReturnNativePayload() = test {
        val repo = repo()
        val model = model(repo)
        model.bind(params)
        runCurrent()
        model.open("one")
        repo.resolveGate = CompletableDeferred()
        val value = async { model.resolve(model.state.value.open!!) }
        runCurrent()
        model.stop()
        repo.resolveGate!!.complete(Unit)
        runCurrent()
        assertNull(value.await())
    }

    @Test
    fun pendingScrollTokensNeverRepeatAfterAcknowledgementAndReload() = test {
        val model = model(repo())
        model.bind(params)
        runCurrent()
        val first = model.state.value.scrollRequest
        model.scrolled(first)
        model.bind(params.copy(contentRevision = 2))
        runCurrent()
        val second = model.state.value.scrollRequest
        assertTrue(second > first)
        model.scrolled(first)
        assertEquals(second, model.state.value.scrollRequest)
    }

    @Test
    fun failedInitialSessionWriteCanRetryWithoutFetchingBeforeSessionIsReady() = test {
        val sessions = Sessions()
        sessions.fail = true
        val repo = repo()
        val model = model(repo, sessions)
        model.bind(params)
        model.active(true)
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertTrue(repo.requests.isEmpty())
        sessions.fail = false
        model.retry()
        runCurrent()
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun repeatedRestorationRetainsHasMoreInDiskCheckpointWithoutRepeatingFetch() = test {
        val repo = repo()
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val first = model(repo, sessions, saved)
        first.bind(params)
        first.active(true)
        runCurrent()
        assertTrue(first.state.value.hasMore)
        first.stop()
        val restoredSaved = copy(saved)
        val second = model(repo, sessions, restoredSaved)
        second.bind(params)
        runCurrent()
        assertTrue(second.state.value.hasMore)
        assertTrue(sessions.value!!.hasMore)
        second.stop()
        val third = model(repo, sessions, copy(restoredSaved))
        third.bind(params)
        third.active(true)
        runCurrent()
        assertTrue(third.state.value.hasMore)
        assertEquals(1, repo.requests.size)
        third.more()
        runCurrent()
        assertEquals("next-url" to 2, repo.requests.last())
    }

    @Test
    fun preparedReadKeepsMetadataAndDoesNotConsumeTicketBeforeNativeDelivery() = test {
        val repo = repo()
        val model = model(repo)
        model.bind(params)
        runCurrent()
        model.open("one")
        val ticket = model.state.value.open!!
        var input: RssArticlesParameters? = null
        val result =
            model.resolvePrepared(
                ticket,
                RssArticlesReadRepository { parameters, key ->
                    input = parameters
                    assertEquals("one", key)
                    RssArticlesRead(repo.batch.articles.single().copy(), null)
                },
            )!!
        assertEquals(params, input)
        assertEquals(repo.batch.articles.single().variable, result.article.variable)
        assertEquals(2, result.article.type)
        assertEquals(ticket, model.state.value.open)
        assertNotNull(model.delivered(ticket.nonce))
        assertNull(model.delivered(ticket.nonce))
    }

    @Test
    fun nonCooperativePreparedReadAfterOwnerChangesCannotDeliverOldArticle() = test {
        val model = model(repo())
        model.bind(params)
        runCurrent()
        model.open("one")
        val ticket = model.state.value.open!!
        val gate = CompletableDeferred<Unit>()
        val result = async {
            model.resolvePrepared(
                ticket,
                RssArticlesReadRepository { _, _ ->
                    withContext(NonCancellable) { gate.await() }
                    RssArticlesRead(RssArticle(origin = "old", link = "old", title = "Old"), null)
                },
            )
        }
        try {
            runCurrent()
            model.bind(params.copy(sortName = "Other"))
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertNull(result.await())
            assertNull(model.state.value.open)
        } finally {
            gate.complete(Unit)
            result.cancel()
            runCurrent()
        }
    }
}
