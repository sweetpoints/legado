package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssCategoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssCategoryViewModel>()
    private val repos = mutableListOf<Repo>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Sessions : RssCategorySessionRepository {
        var value: RssCategorySession? = null
        var fail = false
        var released = false
        override suspend fun read(session: String) = value
        override suspend fun write(session: String, value: RssCategorySession) { if (fail) error("Disk failure"); if (!released && value.revision >= (this.value?.revision ?: 0)) this.value = value }
        override suspend fun release(session: String) { released = true; value = null }
    }
    private class Repo : RssCategoryRepository {
        var source = RssSource(sourceUrl = "source", sourceName = "Name", articleStyle = 0, searchUrl = "search-url", loginUrl = "login", preload = true)
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var loads = 0; var refreshes = 0; var styles = 0; var clears = 0
        var variables = mutableListOf<Pair<String, String?>>()
        override suspend fun load(request: RssCategoryRequest, refresh: Boolean): RssCategorySnapshot {
            val snapshot = source.copy(); loads++; if (refresh) refreshes++
            withContext(NonCancellable) { gate?.await() }; if (fail) error("Load failure")
            return RssCategorySnapshot(snapshot, if (request.query != null) listOf(RssCategoryTab(0, "搜索", "search-url"))
                else (0..2).map { RssCategoryTab(it, "Category $it", "url$it") })
        }
        override suspend fun switchStyle(sourceUrl: String): RssSource { styles++; source.articleStyle = (source.articleStyle + 1) % 5; return source.copy() }
        override suspend fun clearArticles(sourceUrl: String) { clears++ }
        override suspend fun variable(sourceUrl: String): RssCategoryVariable { withContext(NonCancellable) { gate?.await() }; return RssCategoryVariable(sourceUrl, "Latest", "Comment") }
        override suspend fun variable(sourceUrl: String, value: String?) { variables += sourceUrl to value }
    }
    private fun repo() = Repo().also { repos += it }
    private fun model(repo: Repo, sessions: Sessions = Sessions(), saved: SavedStateHandle = SavedStateHandle()) =
        RssCategoryViewModel(repo, sessions, saved).also { models += it }
    private fun copied(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; repos.forEach { it.gate?.complete(Unit) }; runCurrent() }
    }
    @Test fun largeInputsAndDraftRestoreExactWithOnlySmallIdentifiersInSavedState() = test {
        val repo = repo(); val sessions = Sessions(); val saved = SavedStateHandle()
        val input = RssCategoryRequest("source" + "U".repeat(250000), "sort" + "S".repeat(250000))
        val first = model(repo, sessions, saved); first.bind(input); runCurrent()
        val query = "Exact " + "Q".repeat(750000); first.draft(query, 2, 9); first.search(true); first.select(2); runCurrent()
        assertTrue(saved.keys().none { saved.get<Any?>(it).toString().length > 100 })
        first.stop(); val restored = model(repo, sessions, copied(saved)); restored.bind(); runCurrent()
        assertEquals(input, restored.state.value.request); assertEquals(query, restored.state.value.draft)
        assertEquals(2, restored.state.value.selected); assertEquals(2, restored.state.value.selectionStart); assertEquals(9, restored.state.value.selectionEnd)
    }
    @Test fun lateNonCooperativeLoadCannotPublishAfterStop() = test {
        val repo = repo(); repo.gate = CompletableDeferred(); val model = model(repo)
        model.bind(RssCategoryRequest("source")); runCurrent(); model.stop(); repo.gate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.loaded); assertNull(model.sourceSnapshot())
    }
    @Test fun changingSourceBeforeOldLoadReturnsCannotOverwriteNewOwnerOrReuseNativeEffect() = test {
        val repo = repo(); val model = model(repo); model.bind(RssCategoryRequest("source")); runCurrent()
        model.effect(RssCategoryEffectKind.Login); assertNotNull(model.state.value.pending)
        repo.gate = CompletableDeferred(); model.bind(RssCategoryRequest("new")); runCurrent(); repo.gate!!.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.request!!.sourceUrl); assertNull(model.state.value.pending)
    }
    @Test fun submittedEmptySearchDiffersFromNullAndBackRestoresExplicitCategoryUrl() = test {
        val model = model(repo()); model.bind(RssCategoryRequest("source", "explicit")); runCurrent()
        model.submitSearch(); runCurrent(); assertEquals("", model.state.value.request!!.query); assertEquals("搜索", model.state.value.tabs.single().name)
        assertTrue(model.exitSearch()); runCurrent(); assertNull(model.state.value.request!!.query); assertEquals("explicit", model.state.value.request!!.sortUrl)
        assertFalse(model.exitSearch())
    }
    @Test fun singleTopIntentWithNullSortRetainsOldExplicitSortButReplacesQuery() = test {
        val model = model(repo()); model.bind(RssCategoryRequest("source", "explicit")); runCurrent()
        model.bind(RssCategoryRequest("source", query = "query"), reuseSortUrl = true); runCurrent()
        assertEquals("explicit", model.state.value.request!!.sortUrl); assertEquals("query", model.state.value.request!!.query)
    }
    @Test fun refreshClearsSortCacheAndStyleCyclesFiveWithoutMutatingOwnedSnapshot() = test {
        val repo = repo(); val model = model(repo); model.bind(RssCategoryRequest("source")); runCurrent()
        model.sourceSnapshot()!!.articleStyle = 99; assertEquals(0, model.state.value.style)
        repeat(5) { model.switchStyle(); runCurrent(); assertEquals((it + 1) % 5, model.state.value.style) }
        model.refresh(); runCurrent(); assertEquals(1, repo.refreshes); assertEquals(5, repo.styles)
    }
    @Test fun busyMutationRejectsDuplicatesAndClearRevisesPageBatchOnce() = test {
        val repo = repo(); val model = model(repo); model.bind(RssCategoryRequest("source")); runCurrent()
        val revision = model.state.value.contentRevision; repo.gate = CompletableDeferred()
        model.clearArticles(); model.clearArticles(); runCurrent(); assertTrue(model.state.value.busy); assertEquals(1, repo.clears)
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(revision + 1, model.state.value.contentRevision)
    }
    @Test fun nativeTicketRestoresAndIsConsumedExactlyOnceAndInvalidLoginIsIgnored() = test {
        val repo = repo(); val sessions = Sessions(); val saved = SavedStateHandle(); val first = model(repo, sessions, saved)
        first.bind(RssCategoryRequest("source")); runCurrent(); first.effect(RssCategoryEffectKind.Login)
        val ticket = first.state.value.pending!!; first.stop()
        val restored = model(repo, sessions, copied(saved)); restored.bind(); runCurrent()
        assertEquals(ticket, restored.delivered(ticket.nonce)); assertNull(restored.delivered(ticket.nonce))
        repo.source.loginUrl = null; restored.edited(); runCurrent(); restored.effect(RssCategoryEffectKind.Login); assertNull(restored.state.value.pending)
    }
    @Test fun variablePayloadIsLatestAndWrongSourceCallbackCannotWrite() = test {
        val repo = repo(); val model = model(repo); model.bind(RssCategoryRequest("source")); runCurrent(); model.effect(RssCategoryEffectKind.Variable)
        val ticket = model.state.value.pending!!; assertEquals("Latest", model.variable(ticket)!!.value)
        model.delivered(ticket.nonce); model.setVariable("wrong", "Ignored"); model.setVariable("source", null); runCurrent()
        assertEquals(listOf("source" to null), repo.variables)
    }
    @Test fun variableNonCooperativeReturnAfterSourceChangeIsRejected() = test {
        val repo = repo(); val model = model(repo); model.bind(RssCategoryRequest("source")); runCurrent(); model.effect(RssCategoryEffectKind.Variable)
        val ticket = model.state.value.pending!!; repo.gate = CompletableDeferred()
        val pending = async { model.variable(ticket) }; runCurrent(); model.bind(RssCategoryRequest("new")); runCurrent()
        repo.gate!!.complete(Unit); runCurrent(); assertNull(pending.await())
    }
    @Test fun restoredDraftSurvivesInitialDiskWriteFailureAndRetry() = test {
        val repo = repo(); val sessions = Sessions(); val saved = SavedStateHandle()
        val first = model(repo, sessions, saved); first.bind(RssCategoryRequest("source")); runCurrent(); first.draft("Unsaved search"); runCurrent(); first.stop()
        sessions.fail = true; val restored = model(repo, sessions, copied(saved)); restored.bind(); runCurrent(); assertFalse(restored.state.value.loaded)
        sessions.fail = false; restored.retry(); runCurrent(); assertEquals("Unsaved search", restored.state.value.draft)
    }
    @Test fun immediateBindStartsAboveDiskRevisionEvenWhenSavedStateIsOlder() = test {
        val sessions = Sessions(); sessions.value = RssCategorySession(RssCategoryRequest("source"), "Old", 30)
        val saved = SavedStateHandle(mapOf("rssCategory.revision" to 2L)); val model = model(repo(), sessions, saved)
        model.bind(RssCategoryRequest("new")); runCurrent(); assertTrue(sessions.value!!.revision > 30); assertEquals("new", sessions.value!!.request.sourceUrl)
    }
}
