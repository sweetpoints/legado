package io.legado.app.ui.rss.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssReaderViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val request = RssReaderRequest("source", "Exact title", link = "article", sort = "Category")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repo : RssReaderRepository {
        var fail = false; var gate: CompletableDeferred<Unit>? = null; var loads = 0; var adds = 0; var updates = 0; var deletes = 0
        val article = RssArticle(origin = "source", link = "article", title = "Article", sort = "Category", variable = "Full variable")
        override suspend fun load(request: RssReaderRequest): RssReaderSnapshot {
            loads++; withContext(NonCancellable) { gate?.await() }; if (fail) error("Disk load failure")
            return RssReaderSnapshot(request, request.title ?: "Fallback", RssSource(sourceUrl = request.origin.orEmpty(), loginUrl = "login"),
                article.copy(), null, mapOf("Header" to "value"), RssReaderDocument.Html("Body", "base", "history"))
        }
        override suspend fun addFavorite(article: RssArticle): RssStar { adds++; withContext(NonCancellable) { gate?.await() }; return article.toStar() }
        override suspend fun updateFavorite(article: RssArticle, title: String?, group: String?): Pair<RssArticle, RssStar> {
            updates++; return article.copy(title = title ?: article.title, group = group ?: article.group).let { it to it.toStar() }
        }
        override suspend fun deleteFavorite(origin: String, link: String) { deletes++ }
    }
    private class Sessions : RssReaderSessionRepository {
        var value: RssReaderSession? = null; var fail = false; val writes = mutableListOf<RssReaderSession>()
        override suspend fun read(session: String) = value
        override suspend fun write(session: String, value: RssReaderSession) {
            if (fail) error("Disk write failure"); writes += value
            if (value.revision >= (this.value?.revision ?: -1)) this.value = value
        }
        override suspend fun release(session: String) { value = null }
    }
    private class Speech : RssReaderSpeechRepository {
        override val speaking = MutableStateFlow(false)
        val html = mutableListOf<String>(); var stops = 0
        override suspend fun speakHtml(encoded: String) { html += encoded; speaking.value = true }
        override fun stop() { stops++; speaking.value = false }
        override fun release() {}
    }
    private fun model(repo: Repo = Repo(), sessions: Sessions = Sessions(), speech: Speech = Speech(), saved: SavedStateHandle = SavedStateHandle()) =
        RssReaderViewModel(repo, sessions, speech, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; gates.forEach { it.complete(Unit) }; runCurrent() }
    }
    @Test fun hugeRequestHtmlUrlAndPageTitleStayOffSavedStateAndRestoreExactly() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val big = request.copy(startHtml = "H".repeat(2000000), origin = "O".repeat(200000))
        val first = model(sessions = sessions, saved = saved); first.bind(big); runCurrent(); first.page("U".repeat(200000), "T".repeat(200000)); runCurrent(); first.stop()
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bind(); runCurrent()
        assertEquals(big, restored.snapshot()!!.request); assertEquals("T".repeat(200000), restored.state.value.title)
        assertTrue(saved.keys().none { saved.get<Any?>(it).toString().length > 100 })
    }
    @Test fun writeFailureBeforeLoadKeepsOriginalRequestForRetry() = test {
        val repo = Repo(); val sessions = Sessions().apply { fail = true }; val model = model(repo, sessions)
        model.bind(request); runCurrent(); assertFalse(model.state.value.loaded); assertEquals(0, repo.loads)
        sessions.fail = false; model.retry(); runCurrent(); assertEquals(request, model.snapshot()!!.request); assertEquals(1, repo.loads)
    }
    @Test fun savedRevisionLagUsesDiskBaselineBeforeImmediateRequestPersistence() = test {
        val sessions = Sessions().apply { value = RssReaderSession(request, 91) }
        val model = model(sessions = sessions, saved = SavedStateHandle(mapOf("rssReader.revision" to 4L)))
        model.bind(request.copy(title = "New")); runCurrent(); assertEquals(92L, sessions.value!!.revision); assertEquals("New", sessions.value!!.request.title)
    }
    @Test fun nonCooperativeLoadingAfterStopCannotPublishSnapshotOrLoadedState() = test {
        val repo = Repo().apply { gate = gate() }; val model = model(repo); model.bind(request); runCurrent()
        model.stop(); repo.gate!!.complete(Unit); runCurrent(); assertFalse(model.state.value.loaded); assertNull(model.snapshot())
    }
    @Test fun changingRequestRejectsOldNonCooperativeLoadingResult() = test {
        val repo = Repo().apply { gate = gate() }; val model = model(repo); model.bind(request); runCurrent()
        model.bind(request.copy(title = "Current")); runCurrent(); repo.gate!!.complete(Unit); runCurrent()
        assertEquals("Current", model.state.value.title); assertEquals("Current", model.snapshot()!!.request.title)
    }
    @Test fun kernelReceivesDocumentOnceAndReplacementKernelReceivesFreshMonotonicTicket() = test {
        val model = model(); model.attachKernel("first"); model.bind(request); runCurrent()
        val first = model.state.value.document; assertTrue(first > 0); assertNotNull(model.document(first, "first"))
        assertTrue(model.documentDelivered(first, "first")); model.attachKernel("first"); assertEquals(0L, model.state.value.document)
        model.attachKernel("second"); val second = model.state.value.document; assertTrue(second > first)
        assertFalse(model.documentDelivered(first, "first")); assertEquals(second, model.state.value.document)
        assertTrue(model.documentDelivered(second, "second"))
    }
    @Test fun nativeEffectRestoresSmallNonceAndIsConsumedOnlyByExactTicket() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val first = model(sessions = sessions, saved = saved)
        first.bind(request); runCurrent(); first.action(RssReaderAction.Login); val pending = first.state.value.pending!!; first.stop()
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bind(); runCurrent()
        assertEquals(pending, restored.state.value.pending); assertNull(restored.delivered("stale"))
        assertEquals(pending, restored.delivered(pending.nonce)); assertNull(restored.delivered(pending.nonce))
    }
    @Test fun favoritePersistsBeforeNavigationAndDuplicateClicksDoNotWriteTwice() = test {
        val repo = Repo(); val model = model(repo); model.bind(request); runCurrent(); repo.gate = gate()
        model.action(RssReaderAction.Favorite); model.action(RssReaderAction.Favorite); runCurrent()
        assertTrue(model.state.value.busy); assertNull(model.state.value.pending); assertEquals(1, repo.adds)
        repo.gate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.favorite)
        assertEquals(RssReaderAction.Favorite, model.state.value.pending!!.action)
    }
    @Test fun staleFavoriteDialogCallbackCannotModifyDifferentReaderOwner() = test {
        val repo = Repo(); val model = model(repo); model.bind(request); runCurrent(); model.action(RssReaderAction.Favorite); runCurrent()
        model.bind(request.copy(link = "Other")); runCurrent(); model.updateFavorite("Stale", "Group"); model.deleteFavorite(); runCurrent()
        assertEquals(0, repo.updates); assertEquals(0, repo.deletes)
    }
    @Test fun ownedSnapshotCopiesProtectHeaderAndArticleStateFromHostMutation() = test {
        val model = model(); model.bind(request); runCurrent(); val external = model.snapshot()!!
        external.article!!.variable = "Changed"; external.source!!.sourceName = "Changed"
        assertEquals("Full variable", model.snapshot()!!.article!!.variable); assertNotEquals("Changed", model.snapshot()!!.source!!.sourceName)
    }
    @Test fun speechCallbackNeedsExactNonceAndKernelAndCannotReplayAfterRequestChanges() = test {
        val speech = Speech(); val model = model(speech = speech); model.attachKernel("kernel"); model.bind(request); runCurrent()
        model.action(RssReaderAction.Speech); val ticket = model.state.value.pending!!; model.delivered(ticket.nonce)
        model.speakHtml("Wrong", "other", ticket.nonce); runCurrent(); assertTrue(speech.html.isEmpty())
        model.speakHtml("Encoded", "kernel", ticket.nonce); model.speakHtml("Duplicate", "kernel", ticket.nonce); runCurrent()
        assertEquals(listOf("Encoded"), speech.html); assertTrue(model.state.value.speaking)
        model.action(RssReaderAction.Speech); runCurrent(); assertFalse(model.state.value.speaking)
        model.action(RssReaderAction.Speech); val old = model.state.value.pending!!; model.delivered(old.nonce)
        model.bind(request.copy(link = "New")); runCurrent(); model.speakHtml("Late", "kernel", old.nonce); runCurrent()
        assertEquals(listOf("Encoded"), speech.html)
    }
}
