package io.legado.app.ui.rss.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RssReaderLaunchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssReaderViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val ticket = UUID.randomUUID().toString()
    private val request = RssReaderRequest("source", "Exact title", startHtml = "H".repeat(2000000))
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Repo : RssReaderRepository {
        var loads = 0; var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(request: RssReaderRequest): RssReaderSnapshot {
            loads++; withContext(NonCancellable) { gate?.await() }
            return RssReaderSnapshot(request, request.title.orEmpty(), null, null, null, emptyMap(), RssReaderDocument.Html("Body", "base", "history"))
        }
        override suspend fun addFavorite(article: RssArticle) = article.toStar()
        override suspend fun updateFavorite(article: RssArticle, title: String?, group: String?) = article to article.toStar()
        override suspend fun deleteFavorite(origin: String, link: String) {}
    }
    private class Sessions : RssReaderSessionRepository {
        var value: RssReaderSession? = null; var failure = false; var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun read(session: String) = value
        override suspend fun write(session: String, value: RssReaderSession) {
            if (failure) error("Private write failed")
            withContext(NonCancellable) { this@Sessions.value = value; writeGate?.await() }
        }
        override suspend fun release(session: String) { value = null }
    }
    private class Launches : RssReaderLaunchRepository {
        var value: RssReaderRequest? = null; var reads = 0; var releases = 0; var failure = false
        var readGate: CompletableDeferred<Unit>? = null
        var durable: (() -> Boolean)? = null
        override suspend fun stage(request: RssReaderRequest): String = error("Producer tested separately")
        override suspend fun read(ticket: String): RssReaderRequest? { reads++; withContext(NonCancellable) { readGate?.await() }; return value }
        override suspend fun release(ticket: String) {
            check(durable?.invoke() != false); if (failure) error("Release failed")
            releases++; value = null
        }
    }
    private class Speech : RssReaderSpeechRepository {
        override val speaking = MutableStateFlow(false)
        override suspend fun speakHtml(encoded: String) {}
        override fun stop() {}
        override fun release() {}
    }
    private fun model(repo: Repo = Repo(), sessions: Sessions = Sessions(), saved: SavedStateHandle = SavedStateHandle()) =
        RssReaderViewModel(repo, sessions, Speech(), saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; gates.forEach { it.complete(Unit) }; runCurrent() }
    }
    @Test fun durableLargeRequestPrecedesTicketReleaseAndRepeatedRestoreNeedsNoLaunchFile() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val launches = Launches().apply {
            value = request; durable = { sessions.value?.acceptedLaunchTicket == ticket && sessions.value?.request == request }
        }
        val first = model(sessions = sessions, saved = saved); first.bindPrepared(ticket, launches); runCurrent()
        assertTrue(first.state.value.loaded); assertEquals(1, launches.releases); first.page("Current URL", "Current title"); runCurrent(); first.stop()
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bindPrepared(ticket, launches); runCurrent()
        assertEquals(request, restored.snapshot()!!.request); assertEquals("Current title", restored.state.value.title)
        assertEquals(1, launches.reads); assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
        restored.bindPrepared(ticket, launches); runCurrent(); assertTrue(restored.state.value.loaded)
    }
    @Test fun failedPrivateWriteKeepsTicketAndRetryUsesExactRequest() = test {
        val sessions = Sessions().apply { failure = true }; val launches = Launches().apply { value = request }
        val repo = Repo(); val model = model(repo, sessions); model.bindPrepared(ticket, launches); runCurrent()
        assertNotNull(model.state.value.error); assertEquals(0, launches.releases); assertEquals(request, launches.value); assertEquals(0, repo.loads)
        sessions.failure = false; model.retry(); runCurrent(); assertEquals(request, model.snapshot()!!.request)
        assertEquals(ticket, sessions.value!!.acceptedLaunchTicket); assertEquals(1, launches.releases)
    }
    @Test fun releaseFailureAfterDurableReceiptRetriesWithoutRereadingConsumedRequest() = test {
        val sessions = Sessions(); val launches = Launches().apply { value = request; failure = true }; val model = model(sessions = sessions)
        model.bindPrepared(ticket, launches); runCurrent(); assertEquals(ticket, sessions.value!!.acceptedLaunchTicket); assertNotNull(model.state.value.error)
        launches.failure = false; model.retry(); runCurrent(); assertEquals(1, launches.reads); assertEquals(1, launches.releases)
        assertEquals(request, model.snapshot()!!.request)
    }
    @Test fun canceledNonCooperativeTicketReadCannotPublishAndNewOwnerCanRetry() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val launches = Launches().apply { value = request; readGate = gate() }
        val first = model(sessions = sessions, saved = saved); first.bindPrepared(ticket, launches); runCurrent(); first.stop()
        launches.readGate!!.complete(Unit); runCurrent(); assertNull(sessions.value); assertFalse(first.state.value.loaded); assertEquals(0, launches.releases)
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bindPrepared(ticket, launches); runCurrent()
        assertEquals(request, restored.snapshot()!!.request); assertEquals(1, launches.releases)
    }
    @Test fun canceledPostWriteWindowRestoresDurableAcceptedReceiptBeforeReleasingTicket() = test {
        val sessions = Sessions().apply { writeGate = gate() }; val saved = SavedStateHandle(); val launches = Launches().apply { value = request }
        val first = model(sessions = sessions, saved = saved); first.bindPrepared(ticket, launches); runCurrent(); first.stop()
        sessions.writeGate!!.complete(Unit); runCurrent(); assertEquals(ticket, sessions.value!!.acceptedLaunchTicket); assertEquals(0, launches.releases)
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bindPrepared(ticket, launches); runCurrent()
        assertEquals(1, launches.reads); assertEquals(1, launches.releases); assertTrue(restored.state.value.loaded)
    }
    @Test fun duplicateReceiptDuringAndAfterParserLoadingCannotRestartSourceParsing() = test {
        val repo = Repo().apply { gate = gate() }; val launches = Launches().apply { value = request }; val model = model(repo)
        model.bindPrepared(ticket, launches); runCurrent(); model.bindPrepared(ticket, launches); runCurrent()
        assertEquals(1, repo.loads); assertEquals(1, launches.reads); repo.gate!!.complete(Unit); runCurrent()
        model.bindPrepared(ticket, launches); runCurrent(); assertEquals(1, repo.loads)
    }
    @Test fun missingLaunchNeverFallsBackToUnrelatedOldReaderRequest() = test {
        val sessions = Sessions().apply { value = RssReaderSession(request.copy(title = "Old"), 20, acceptedLaunchTicket = "other") }
        val model = model(sessions = sessions); model.bindPrepared(ticket, Launches()); runCurrent()
        assertTrue(model.state.value.missingOrigin); assertFalse(model.state.value.loaded); assertNull(model.snapshot())
    }
    @Test fun retryAndInlineRefreshKeepAcceptedReceiptForSameInputButClearItForNewInput() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val launches = Launches().apply { value = request }
        val first = model(sessions = sessions, saved = saved)
        first.bindPrepared(ticket, launches); runCurrent()
        first.retry(); runCurrent(); assertEquals(ticket, sessions.value!!.acceptedLaunchTicket)
        first.bind(request.copy()); runCurrent(); assertEquals(ticket, sessions.value!!.acceptedLaunchTicket)
        first.stop()
        val restored = model(sessions = sessions, saved = copy(saved))
        restored.bindPrepared(ticket, launches); runCurrent()
        assertEquals(request, restored.snapshot()!!.request); assertEquals(1, launches.reads)
        restored.bind(request.copy(title = "Different request")); runCurrent()
        assertNull(sessions.value!!.acceptedLaunchTicket)
    }

}
