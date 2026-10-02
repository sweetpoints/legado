package io.legado.app.ui.rss.source.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssSourceDebugViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<RssSourceDebugViewModel>(); private val repos = mutableListOf<Fake>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop(); it.viewModelScope.cancel() }; repos.forEach { it.loadGate?.complete(Unit); it.acquireGate?.complete(Unit); it.leases.forEach { lease -> lease.done.complete(Unit) } }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = RssSourceDebugViewModel(repo, saved, "url").also { models += it; repos += repo }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun firstOpenLoadsHelpAndCategoriesWithoutAutomaticallyExecuting() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); assertTrue(vm.state.value.loaded); assertTrue(vm.state.value.help); assertEquals("Name", vm.state.value.name)
        assertEquals(listOf("Category::https://category", "Other::https://other"), vm.state.value.sorts.map { it.query }); assertTrue(repo.leases.isEmpty())
    }
    @Test fun missingAndFailedLoadDoNotRunAndRetryCannotLoseTyping() = runTest(dispatcher) {
        val missing = Fake(); missing.missing = true; val absent = model(missing); runCurrent(); assertTrue(absent.state.value.missing); assertTrue(missing.leases.isEmpty())
        val repo = Fake(); repo.failLoad = true; val vm = model(repo); runCurrent(); assertNotNull(vm.state.value.error); vm.query("ignored"); assertEquals("", vm.state.value.query)
        repo.failLoad = false; vm.retry(); runCurrent(); vm.query("accepted", 3); assertEquals("accepted", vm.state.value.query); assertEquals(3, vm.state.value.queryStart)
    }
    @Test fun stoppedNonCooperativeLoadFailureDoesNotPublishAnyState() = runTest(dispatcher) {
        val repo = Fake(); repo.loadGate = CompletableDeferred(); repo.failLoad = true; val vm = model(repo); runCurrent(); vm.stop(); val before = vm.state.value
        repo.loadGate!!.complete(Unit); runCurrent(); assertEquals(before, vm.state.value); assertTrue(repo.leases.isEmpty())
    }
    @Test fun explicitSearchChangesHelpLoadingAndSeparatesHtmlEventsFromSelectableLog() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.run("我的"); runCurrent(); val lease = repo.leases.single()
        assertFalse(vm.state.value.help); assertTrue(vm.state.value.running); assertEquals("我的", lease.query)
        lease.event(RssSourceDebugEvent(10, "<list>")); lease.event(RssSourceDebugEvent(20, "<content>")); lease.event(RssSourceDebugEvent(1, "log"))
        assertEquals("log", vm.state.value.output); assertEquals("<list>", vm.html(false)); assertEquals("<content>", vm.html(true)); assertTrue(vm.state.value.hasListHtml); assertTrue(vm.state.value.hasContentHtml)
        lease.done.complete(Unit); runCurrent(); assertFalse(vm.state.value.running); assertEquals(1, lease.closes)
    }
    @Test fun nullQueryUsesOriginalDefaultAndSortSelectionRunsExactNameUrlAndTerminalClearsSpinner() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.run(null); runCurrent(); assertEquals("我的", repo.leases.single().query); repo.leases.single().done.complete(Unit); runCurrent()
        vm.sort(1); runCurrent(); assertEquals("Other::https://other", vm.state.value.query); assertEquals(1, vm.state.value.selectedSort); assertEquals("Other::https://other", repo.leases.last().query)
        repo.leases.last().event(RssSourceDebugEvent(-1, "parse failed")); repo.leases.last().done.complete(Unit); runCurrent(); assertEquals("parse failed", vm.state.value.output); assertFalse(vm.state.value.running)
    }
    @Test fun rerunJoinsPriorExecutionBeforeAcquiringSuccessorAndDropsLateHtmlLogAndCompletion() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.run("old"); runCurrent(); val old = repo.leases.single(); old.event(RssSourceDebugEvent(1, "old log"))
        vm.run("new"); runCurrent(); assertEquals(1, old.closes); assertEquals(1, repo.leases.size); assertTrue(vm.state.value.running)
        old.event(RssSourceDebugEvent(20, "late html")); old.event(RssSourceDebugEvent(1, "late log")); old.done.complete(Unit); runCurrent()
        assertEquals(listOf("acquire", "run:old", "close", "finished", "acquire", "run:new"), repo.events); assertFalse(vm.state.value.hasContentHtml); assertEquals("", vm.state.value.output)
        repo.leases.last().event(RssSourceDebugEvent(1, "new log")); repo.leases.last().done.complete(Unit); runCurrent(); assertEquals("new log", vm.state.value.output)
    }
    @Test fun busyChannelReportsWithoutCancelingOtherOwnerAndExplicitRetryCanRun() = runTest(dispatcher) {
        val repo = Fake(); repo.busy = true; val vm = model(repo); runCurrent(); vm.run("query"); runCurrent(); assertEquals(RssSourceDebugIssue.Busy, vm.state.value.issue); assertFalse(vm.state.value.running); assertTrue(repo.leases.isEmpty())
        repo.busy = false; vm.run(); runCurrent(); repo.leases.single().done.complete(Unit); runCurrent(); assertNull(vm.state.value.issue)
    }
    @Test fun diskRestoresLargeHtmlQueryCursorAndBoundedLogsWithoutReexecutingInterruptedRun() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); vm.run("query"); runCurrent(); val old = repo.leases.single()
        val html = "large".repeat(200000); old.event(RssSourceDebugEvent(10, html)); old.event(RssSourceDebugEvent(1, "log".repeat(10000))); vm.help(true); vm.query("draft query", 2, 4); runCurrent(); vm.flush()
        val before = snapshot(saved); vm.stop(); old.done.complete(Unit); runCurrent(); val restored = model(repo, before); runCurrent()
        assertEquals(html, restored.html(false)); assertEquals(20000, restored.state.value.output.length); assertEquals("draft query", restored.state.value.query)
        assertEquals(2, restored.state.value.queryStart); assertEquals(4, restored.state.value.queryEnd); assertTrue(restored.state.value.help); assertEquals(RssSourceDebugIssue.Interrupted, restored.state.value.issue)
        assertFalse(restored.state.value.running); assertEquals(1, repo.leases.size); assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { it < 1000 } != false })
    }
    @Test fun closeAndCancellationDuringAcquireReleaseOnlyOwnedLeaseAndIgnoreLateCallbacks() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.run("query"); runCurrent(); val lease = repo.leases.single(); lease.event(RssSourceDebugEvent(1, "before"))
        vm.close(); assertEquals(1, lease.closes); assertTrue(vm.state.value.closed); lease.event(RssSourceDebugEvent(1, "late")); lease.done.complete(Unit); runCurrent(); assertEquals("before", vm.state.value.output)
        val slow = Fake(); slow.acquireGate = CompletableDeferred(); val canceled = model(slow); runCurrent(); canceled.run(); runCurrent(); canceled.stop(); slow.acquireGate!!.complete(Unit); runCurrent()
        assertEquals(1, slow.leases.single().closes); assertEquals(0, slow.leases.single().runs)
    }
    @Test fun exceptionPreservesPartialLogReleasesLeaseAndNextRunResetsHtmlAndLog() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.run("query"); runCurrent(); val lease = repo.leases.single(); lease.event(RssSourceDebugEvent(1, "partial")); lease.event(RssSourceDebugEvent(10, "html"))
        lease.done.completeExceptionally(IllegalStateException("execution failed")); runCurrent(); assertEquals("partial", vm.state.value.output); assertEquals("execution failed", vm.state.value.error); assertEquals(1, lease.closes)
        vm.run("again"); runCurrent(); assertEquals("", vm.state.value.output); assertNull(vm.html(false)); repo.leases.last().done.complete(Unit); runCurrent()
    }
    private class Fake : RssSourceDebugRepository {
        var missing = false; var failLoad = false; var busy = false; var loadGate: CompletableDeferred<Unit>? = null; var acquireGate: CompletableDeferred<Unit>? = null
        val records = mutableMapOf<String, RssSourceDebugRecord>(); val leases = mutableListOf<Lease>(); val events = mutableListOf<String>()
        override suspend fun load(key: String): RssSourceDebugSnapshot? { loadGate?.let { withContext(NonCancellable) { it.await() } }; if (failLoad) error("load failed"); return if (missing) null else RssSourceDebugSnapshot(key, "Name", "json") }
        override suspend fun sorts(source: RssSourceDebugSnapshot) = listOf(RssSourceDebugSort("Category", "https://category"), RssSourceDebugSort("Other", "https://other"))
        override suspend fun acquire(source: RssSourceDebugSnapshot, event: (RssSourceDebugEvent) -> Unit): RssSourceDebugLease? { acquireGate?.await(); if (busy) return null; events += "acquire"; return Lease(event, events).also { leases += it } }
        override suspend fun read(session: String) = records[session]
        override suspend fun write(session: String, record: RssSourceDebugRecord) { if (record.revision >= (records[session]?.revision ?: -1)) records[session] = record }
    }
    private class Lease(val event: (RssSourceDebugEvent) -> Unit, private val events: MutableList<String>) : RssSourceDebugLease {
        val done = CompletableDeferred<Unit>(); var query = ""; var runs = 0; var closes = 0
        override suspend fun run(query: String) = withContext(NonCancellable) { this@Lease.query = query; runs++; events += "run:$query"; try { done.await() } finally { events += "finished" } }
        override fun close() { if (closes == 0) { closes++; events += "close" } }
        override suspend fun awaitStopped() = Unit
    }
}
