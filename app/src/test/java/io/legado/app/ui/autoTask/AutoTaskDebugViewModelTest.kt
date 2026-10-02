package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTaskDebugViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<AutoTaskDebugViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop(); it.viewModelScope.cancel() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), id: String? = "id") = AutoTaskDebugViewModel(repo, saved, id).also { models += it }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun firstLoadAutomaticallyRunsOnceAndStreamsLogsThenAppendsResultAndReleasesOwnLease() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); assertTrue(vm.uiState.value.isRunning); assertEquals(1, repo.leases.size)
        val lease = repo.leases.single(); lease.log("first"); lease.log("second"); lease.result.complete("result"); runCurrent()
        assertEquals("first\nsecond\nresult", vm.uiState.value.output); assertFalse(vm.uiState.value.isRunning); assertEquals(1, lease.closes)
    }
    @Test fun missingTaskNeverAcquiresAndLoadingFailureCanRetry() = runTest(dispatcher) {
        val repo = Fake(); repo.missing = true; val vm = model(repo); runCurrent(); assertTrue(vm.uiState.value.taskMissing); assertTrue(repo.leases.isEmpty())
        val failing = Fake(); failing.failLoad = true; val retry = model(failing); runCurrent(); assertNotNull(retry.uiState.value.error); failing.failLoad = false
        retry.retryLoad(); runCurrent(); assertEquals(1, failing.leases.size); failing.leases.single().result.complete("done"); runCurrent()
    }
    @Test fun globalBusyReportsIssueWithoutCancelingUnrelatedDebugOwner() = runTest(dispatcher) {
        val repo = Fake(); repo.busy = true; val vm = model(repo); runCurrent()
        assertEquals(AutoTaskDebugIssue.Busy, vm.uiState.value.issue); assertFalse(vm.uiState.value.isRunning); assertEquals(0, repo.closedCount)
        repo.busy = false; vm.runDebug(); runCurrent(); repo.leases.single().result.complete("done"); runCurrent(); assertEquals("done", vm.uiState.value.output)
    }
    @Test fun explicitRerunWaitsForPriorRunnerBeforeAcquiringNewGlobalOwnerAndIgnoresLateOutput() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); val old = repo.leases.single(); old.log("old")
        vm.runDebug(); runCurrent(); assertEquals(1, old.closes); assertEquals(1, repo.leases.size)
        old.log("late log"); old.result.complete("late result"); runCurrent()
        assertEquals(2, repo.leases.size); assertEquals(listOf("acquire", "run", "close", "finish", "acquire", "run"), repo.events)
        val current = repo.leases.last(); current.log("new"); assertEquals("new", vm.uiState.value.output); assertTrue(vm.uiState.value.isRunning)
        current.result.complete("current result"); runCurrent(); assertEquals("new\ncurrent result", vm.uiState.value.output)
    }
    @Test fun stoppedNonCooperativeLoadFailureCannotPublishUiAfterCancellation() = runTest(dispatcher) {
        val repo = Fake(); repo.loadGate = CompletableDeferred(); repo.failLoad = true
        val vm = model(repo); runCurrent(); vm.stop(); val before = vm.uiState.value
        repo.loadGate!!.complete(Unit); runCurrent(); assertEquals(before, vm.uiState.value); assertTrue(repo.leases.isEmpty())
    }
    @Test fun closeImmediatelyReleasesExecutionAndLateCallbacksCannotChangeClosedState() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); val lease = repo.leases.single(); lease.log("before")
        vm.close(); assertEquals(1, lease.closes); assertTrue(vm.uiState.value.closed); assertFalse(vm.uiState.value.isRunning)
        lease.log("late"); lease.result.complete("late result"); runCurrent(); assertEquals("before", vm.uiState.value.output)
        val restored = model(repo, snapshot(saved)); runCurrent(); assertTrue(restored.uiState.value.closed); assertEquals(1, repo.leases.size)
    }
    @Test fun stoppedDuringNonCooperativeAcquireClosesLeaseWithoutStartingRunnerOrUpdatingUi() = runTest(dispatcher) {
        val repo = Fake(); repo.acquireGate = CompletableDeferred(); val vm = model(repo); runCurrent(); vm.stop()
        repo.acquireGate!!.complete(Unit); runCurrent(); val lease = repo.leases.single()
        assertEquals(1, lease.closes); assertEquals(0, lease.runs); assertEquals("", vm.uiState.value.output)
    }
    @Test fun completedLogRestoresFromDiskWithoutAutomaticallyExecutingAgainAndBundleStaysSmall() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); val lease = repo.leases.single()
        lease.log("old".repeat(10000)); lease.result.complete("latest result"); runCurrent(); vm.flush()
        assertEquals(20000, vm.uiState.value.output.length); assertTrue(vm.uiState.value.output.endsWith("latest result"))
        vm.stop(); val restored = model(repo, snapshot(saved)); runCurrent(); assertEquals(vm.uiState.value.output, restored.uiState.value.output)
        assertFalse(restored.uiState.value.isRunning); assertEquals(1, repo.leases.size)
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { it < 1000 } != false })
    }
    @Test fun processRestoreOfInterruptedRunKeepsLogsAndRequiresExplicitRerun() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); val old = repo.leases.single(); old.log("retained"); runCurrent(); vm.flush()
        val before = snapshot(saved); vm.stop(); old.result.complete("late"); runCurrent()
        val restored = model(repo, before); runCurrent(); assertEquals("retained", restored.uiState.value.output); assertEquals(AutoTaskDebugIssue.Interrupted, restored.uiState.value.issue)
        assertFalse(restored.uiState.value.isRunning); assertEquals(1, repo.leases.size)
        restored.runDebug(); runCurrent(); assertEquals("", restored.uiState.value.output); assertNull(restored.uiState.value.issue)
        repo.leases.last().result.complete("new"); runCurrent(); assertEquals("new", restored.uiState.value.output)
    }
    @Test fun executionExceptionPreservesStreamedOutputAndAlwaysReleasesLease() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); val lease = repo.leases.single(); lease.log("partial")
        lease.result.completeExceptionally(IllegalStateException("execution failed")); runCurrent()
        assertEquals("partial", vm.uiState.value.output); assertEquals("execution failed", vm.uiState.value.error); assertFalse(vm.uiState.value.isRunning); assertEquals(1, lease.closes)
    }
    private class Fake : AutoTaskDebugRepository {
        var missing = false; var failLoad = false; var busy = false; var closedCount = 0
        val events = mutableListOf<String>(); var loadGate: CompletableDeferred<Unit>? = null
        var acquireGate: CompletableDeferred<Unit>? = null; val leases = mutableListOf<Lease>(); val records = mutableMapOf<String, AutoTaskDebugRecord>()
        override suspend fun load(id: String): AutoTaskDebugSnapshot? { loadGate?.let { withContext(NonCancellable) { it.await() } }; if (failLoad) error("load failed"); return if (missing) null else AutoTaskDebugSnapshot(id, "source", "json") }
        override suspend fun acquire(task: AutoTaskDebugSnapshot, log: (String) -> Unit): AutoTaskDebugLease? {
            acquireGate?.await(); if (busy) return null
            events += "acquire"
            return Lease(log, events) { closedCount++ }.also { leases += it }
        }
        override suspend fun read(session: String) = records[session]
        override suspend fun write(session: String, record: AutoTaskDebugRecord) { if (record.revision >= (records[session]?.revision ?: -1)) records[session] = record }
    }
    private class Lease(val log: (String) -> Unit, private val events: MutableList<String>, private val closed: () -> Unit) : AutoTaskDebugLease {
        val result = CompletableDeferred<String>(); var closes = 0; var runs = 0
        override suspend fun run(): AutoTaskDebugResult = withContext(NonCancellable) { runs++; events += "run"; try { AutoTaskDebugResult(result.await()) } finally { events += "finish" } }
        override fun close() { if (closes == 0) { closes++; events += "close"; closed() } }
    }
}
