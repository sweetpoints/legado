package io.legado.app.ui.main.rss

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MainRssViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<MainRssViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private class Repo : MainRssRepository {
        val values =
            MutableStateFlow(listOf(MainRssRow("id", "https://feed.invalid", "Feed", "icon", true)))
        val groupValues = MutableStateFlow(listOf("A"))
        val queries = mutableListOf<String>()
        var subscriptions = 0
        var canceled = 0
        var prepares = 0
        var exists = true
        val tops = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var navigation =
            MainRssNavigation(MainRssDestination.ReaderHtml, "https://feed.invalid", "Feed", "Body")

        override fun rows(query: String) = flow {
            queries += query
            subscriptions++
            try {
                emitAll(values)
            } finally {
                canceled++
            }
        }

        override fun groups() = groupValues

        override suspend fun source(id: String): RssSource? {
            withContext(NonCancellable) { gate?.await() }
            return if (exists) RssSource(sourceUrl = "https://feed.invalid") else null
        }

        override suspend fun top(id: String) {
            tops += id
            withContext(NonCancellable) { gate?.await() }
        }

        override suspend fun disable(id: String) {}

        override suspend fun delete(id: String) {
            deleted += id
            withContext(NonCancellable) { gate?.await() }
        }

        override suspend fun prepare(id: String): MainRssNavigation? {
            prepares++
            withContext(NonCancellable) { gate?.await() }
            return if (exists) navigation else null
        }
    }

    private class Sessions : MainRssSessionRepository {
        var value: MainRssCheckpoint? = null
        var gate: CompletableDeferred<Unit>? = null
        var failure = false
        var readFailure = false

        override suspend fun read(id: String): MainRssCheckpoint? {
            withContext(NonCancellable) { gate?.await() }
            if (readFailure) error("Read failed")
            return value
        }

        override suspend fun write(id: String, checkpoint: MainRssCheckpoint) {
            if (failure) error("Disk failed")
            if (checkpoint.revision >= (value?.revision ?: -1)) value = checkpoint
        }

        override suspend fun release(id: String) {}
    }

    private fun model(
        repo: Repo = Repo(),
        sessions: Sessions = Sessions(),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = MainRssViewModel(repo, sessions, saved).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                gates.forEach { it.complete(Unit) }
                runCurrent()
            }
        }

    private fun TestScope.start(model: MainRssViewModel) {
        model.visible(true)
        model.bind()
        runCurrent()
    }

    @Test
    fun inactivePagerStopsCollectorsRejectsActionsAndResumeReadsFreshRows() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind()
        runCurrent()
        assertTrue(model.state.value.loaded)
        assertEquals(0, repo.subscriptions)
        model.top("id")
        model.action(MainRssAction.History)
        runCurrent()
        assertTrue(repo.tops.isEmpty())
        assertNull(model.state.value.pending)
        model.visible(true)
        runCurrent()
        assertEquals(1, repo.subscriptions)
        model.visible(false)
        runCurrent()
        assertEquals(1, repo.canceled)
        repo.values.value = repo.values.value.map { it.copy(name = "Latest") }
        runCurrent()
        assertEquals("Feed", model.state.value.rows.single().name)
        model.visible(true)
        runCurrent()
        assertEquals("Latest", model.state.value.rows.single().name)
    }

    @Test
    fun largeQueryCursorAndGridScrollRestoreWithoutLargeSavedValues() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val first = model(sessions = sessions, saved = saved)
        start(first)
        first.query("Q".repeat(2000000), 8, 13)
        first.scroll(42, 16)
        runCurrent()
        first.stop()
        val restored = model(sessions = sessions, saved = copy(saved))
        start(restored)
        assertEquals(2000000, restored.state.value.query.length)
        assertEquals(8, restored.state.value.queryStart)
        assertEquals(13, restored.state.value.queryEnd)
        assertEquals(42, restored.state.value.scrollIndex)
        assertEquals(16, restored.state.value.scrollOffset)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }

    @Test
    fun diskRevisionAheadOfSavedBundleCannotRejectFirstEdit() = test {
        val sessions = Sessions().apply { value = MainRssCheckpoint(revision = 80, query = "Old") }
        val model =
            model(sessions = sessions, saved = SavedStateHandle(mapOf("mainRss.revision" to 2L)))
        start(model)
        model.query("Exact group")
        runCurrent()
        assertEquals(81L, sessions.value!!.revision)
        assertEquals("Exact group", sessions.value!!.query)
    }

    @Test
    fun queriesKeepExactWhitespaceAndGroupPrefixAndPausedEditsAreIgnored() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        listOf(" ", "group:A", "Literal").forEach {
            model.query(it)
            runCurrent()
            assertEquals(it, repo.queries.last())
        }
        model.visible(false)
        model.query("Offscreen")
        runCurrent()
        assertEquals("Literal", model.state.value.query)
    }

    @Test
    fun deleteCancelDoesNotWriteAndRestoredConfirmationCommitsFrozenIdOnce() = test {
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val repo = Repo()
        val first = model(repo, sessions, saved)
        start(first)
        first.requestDelete("id")
        first.cancelDelete()
        runCurrent()
        assertTrue(repo.deleted.isEmpty())
        first.requestDelete("id")
        runCurrent()
        first.stop()
        val restored = model(repo, sessions, copy(saved))
        start(restored)
        assertEquals("Feed", restored.state.value.deletingName)
        restored.confirmDelete()
        restored.confirmDelete()
        runCurrent()
        assertEquals(listOf("id"), repo.deleted)
        assertNull(restored.state.value.deletingId)
    }

    @Test
    fun htmlCompletedWhilePausedPersistsAndRestoreNeverRepeatsJavascript() = test {
        val repo =
            Repo().apply {
                gate = gate()
                navigation = navigation.copy(value = "H".repeat(2000000))
            }
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val first = model(repo, sessions, saved)
        start(first)
        first.action(MainRssAction.Open, "id")
        runCurrent()
        first.visible(false)
        repo.gate!!.complete(Unit)
        runCurrent()
        val nonce = first.state.value.pending!!.nonce
        assertFalse(first.delivered(nonce))
        first.stop()
        val restored = model(repo, sessions, copy(saved))
        start(restored)
        assertEquals(nonce, restored.state.value.pending!!.nonce)
        assertEquals(2000000, restored.resolve(nonce)!!.navigation!!.value!!.length)
        assertEquals(1, repo.prepares)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
        assertTrue(restored.delivered(nonce))
        assertFalse(restored.delivered(nonce))
    }

    @Test
    fun preparedWriteFailureRetriesFixedReceiptWithoutRepeatingParser() = test {
        val repo = Repo()
        val sessions = Sessions()
        val model = model(repo, sessions)
        start(model)
        sessions.failure = true
        model.action(MainRssAction.Open, "id")
        runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.pending)
        sessions.failure = false
        model.retry()
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        model.retry()
        runCurrent()
        assertEquals(nonce, model.state.value.pending!!.nonce)
        assertEquals(1, repo.prepares)
    }

    @Test
    fun savedDeliveredReceiptSuppressesOlderDiskPending() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val model = model(sessions = sessions, saved = saved)
        start(model)
        model.action(MainRssAction.Settings)
        runCurrent()
        val oldDisk = sessions.value!!
        val nonce = model.state.value.pending!!.nonce
        assertTrue(model.delivered(nonce))
        model.stop()
        sessions.value = oldDisk
        val restored = model(sessions = sessions, saved = copy(saved))
        start(restored)
        assertNull(restored.state.value.pending)
        assertFalse(restored.delivered(nonce))
    }

    @Test
    fun missingSourceCannotPrepareOrResolveAndNextToolbarActionRemainsAvailable() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        repo.exists = false
        model.action(MainRssAction.Open, "id")
        runCurrent()
        assertEquals(MainRssIssue.SourceMissing, model.state.value.issue)
        model.action(MainRssAction.History)
        runCurrent()
        assertEquals(MainRssAction.History, model.state.value.pending!!.action)
        model.delivered(model.state.value.pending!!.nonce)
        runCurrent()
        repo.exists = true
        model.action(MainRssAction.Edit, "id")
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        repo.exists = false
        assertNull(model.resolve(nonce))
        assertTrue(model.delivered(nonce))
        model.missing()
        assertEquals(MainRssIssue.SourceMissing, model.state.value.issue)
    }

    @Test
    fun canceledNonCooperativeResolveKeepsPendingForResume() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        model.action(MainRssAction.Edit, "id")
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        repo.gate = gate()
        var result: MainRssPrepared? = null
        val job = launch { result = model.resolve(nonce) }
        runCurrent()
        job.cancel()
        model.visible(false)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertNull(result)
        assertEquals(nonce, model.state.value.pending!!.nonce)
        model.visible(true)
        runCurrent()
        assertNotNull(model.resolve(nonce))
    }

    @Test
    fun nonCooperativeReadCannotPublishAfterStopAndReadFailureCanRetry() = test {
        val sessions = Sessions().apply { gate = gate() }
        val model = model(sessions = sessions)
        model.visible(true)
        model.bind()
        runCurrent()
        model.stop()
        sessions.gate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.loaded)
        val retrySessions = Sessions().apply { readFailure = true }
        val retry = model(sessions = retrySessions)
        start(retry)
        assertFalse(retry.state.value.loaded)
        assertNotNull(retry.state.value.error)
        retrySessions.readFailure = false
        retry.retry()
        runCurrent()
        assertTrue(retry.state.value.loaded)
    }

    @Test
    fun nonCooperativePrepareAndDeleteCannotPublishAfterStop() = test {
        val repo = Repo().apply { gate = gate() }
        val preparing = model(repo)
        start(preparing)
        preparing.action(MainRssAction.Open, "id")
        runCurrent()
        preparing.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertNull(preparing.state.value.pending)
        val deleteRepo = Repo().apply { gate = gate() }
        val deleting = model(deleteRepo)
        start(deleting)
        deleting.requestDelete("id")
        runCurrent()
        deleting.confirmDelete()
        runCurrent()
        deleting.stop()
        deleteRepo.gate!!.complete(Unit)
        runCurrent()
        assertEquals("id", deleting.state.value.deletingId)
        assertTrue(deleting.state.value.busy)
    }
}
