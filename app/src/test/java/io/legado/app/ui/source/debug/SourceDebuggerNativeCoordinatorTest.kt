package io.legado.app.ui.source.debug

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.ui.book.source.debug.BookSourceDebugViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDebuggerNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookSourceDebugViewModel>()
    private val coordinators = mutableListOf<SourceDebuggerNativeCoordinator>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        coordinators.forEach { it.close() }
        models.forEach { it.stop(); it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        var ready = true
        var finishes = 0
    }

    private fun model(repo: Repository) = BookSourceDebugViewModel(repo, SavedStateHandle(), "source")
        .also { models += it }

    private fun coordinator(model: BookSourceDebugViewModel, host: Host, scope: CoroutineScope) =
        SourceDebuggerNativeCoordinator.book(model, host.lifecycle, scope, { host.ready },
            { host.finishes++ }, {}, {}, {}, {}).also { coordinators += it }

    @Test fun explicitCloseDuringLoadingCompletesOnceWhenIndependentHostGateBecomesReady() = runTest(dispatcher) {
        val repo = Repository().apply { loadGate = CompletableDeferred() }
        val model = model(repo)
        val host = Host().apply { ready = false; registry.currentState = Lifecycle.State.RESUMED }
        val coordinator = coordinator(model, host, backgroundScope)
        runCurrent()
        assertTrue(model.state.value.loading)
        coordinator.requestClose()
        runCurrent()
        assertTrue(model.state.value.closed)
        assertTrue(model.state.value.loading)
        assertEquals(0, host.finishes)
        host.ready = true // No model emission or lifecycle transition follows this change.
        coordinator.onHostReady()
        coordinator.onHostReady()
        runCurrent()
        assertEquals(1, host.finishes)
        assertEquals(1, repo.releases)
        assertTrue(repo.leases.isEmpty())
    }

    @Test fun missingSourceCompletesAfterHostReadinessWithoutNeedingAnotherModelEvent() = runTest(dispatcher) {
        val repo = Repository().apply { missing = true }
        val model = model(repo)
        val host = Host().apply { ready = false; registry.currentState = Lifecycle.State.RESUMED }
        val coordinator = coordinator(model, host, backgroundScope)
        runCurrent()
        assertTrue(model.state.value.missing)
        assertEquals(0, host.finishes)
        host.ready = true
        coordinator.onHostReady()
        coordinator.onHostReady()
        runCurrent()
        assertEquals(1, host.finishes)
        assertTrue(repo.leases.isEmpty())
    }

    @Test fun readyCallbackWhilePausedCannotFinishAndResumeDeliversCloseExactlyOnce() = runTest(dispatcher) {
        val model = model(Repository())
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        val coordinator = coordinator(model, host, backgroundScope)
        runCurrent()
        coordinator.requestClose()
        coordinator.onHostReady()
        runCurrent()
        assertEquals(0, host.finishes)
        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        coordinator.onHostReady()
        assertEquals(1, host.finishes)
    }

    @Test fun detachingAndReplacingUiBindingKeepsRunAliveAndRejectsAllOldOwnerAccess() = runTest(dispatcher) {
        val repo = Repository()
        val model = model(repo)
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val coordinator = coordinator(model, host, backgroundScope)
        val oldOwner = coordinator.bind()
        runCurrent()
        coordinator.command(oldOwner, mapOf("generation" to coordinator.generation, "op" to "run"))
        runCurrent()
        val lease = repo.leases.single()
        assertTrue(model.state.value.running)
        coordinator.detach(oldOwner)
        assertEquals(0, lease.closes)
        assertTrue(model.state.value.running)
        val successor = coordinator.bind()
        lease.event(BookSourceDebugEvent(0, "new log"))
        runCurrent()
        assertThrows(IllegalStateException::class.java) { coordinator.snapshot(oldOwner) }
        assertThrows(IllegalStateException::class.java) {
            coordinator.command(oldOwner, mapOf("generation" to coordinator.generation, "op" to "close"))
        }
        coordinator.detach(oldOwner) // Late cleanup cannot detach the replacement owner.
        assertEquals("new log", coordinator.snapshot(successor)["output"])
        assertEquals(0, lease.closes)
        assertEquals(0, repo.releases)
        assertEquals(1, lease.runs)
    }

    @Test fun recreatedCoordinatorChangesGenerationWithoutStoppingOrRestartingOwnedRun() = runTest(dispatcher) {
        val repo = Repository()
        val model = model(repo)
        val firstHost = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val first = coordinator(model, firstHost, backgroundScope)
        val firstOwner = first.bind()
        runCurrent()
        first.command(firstOwner, mapOf("generation" to first.generation, "op" to "run"))
        runCurrent()
        val lease = repo.leases.single()
        firstHost.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        first.close()
        val nextHost = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val next = coordinator(model, nextHost, backgroundScope)
        val nextOwner = next.bind()
        runCurrent()
        assertNotEquals(first.generation, next.generation)
        assertEquals(true, next.snapshot(nextOwner)["running"])
        assertThrows(IllegalArgumentException::class.java) {
            next.command(nextOwner, mapOf("generation" to first.generation, "op" to "close"))
        }
        assertThrows(IllegalStateException::class.java) { first.snapshot(firstOwner) }
        assertEquals(1, lease.runs)
        assertEquals(0, lease.closes)
        assertEquals(0, repo.releases)
    }

    @Test fun leavingStartedFlushesCheckpointWhileExecutionRemainsOwned() = runTest(dispatcher) {
        val repo = Repository()
        val model = model(repo)
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        coordinator(model, host, backgroundScope)
        runCurrent()
        model.run("running query")
        runCurrent()
        val lease = repo.leases.single()
        lease.event(BookSourceDebugEvent(0, "partial output"))
        runCurrent()
        val before = repo.writes
        host.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        assertTrue(repo.writes > before)
        assertEquals("partial output", repo.latest?.output)
        assertEquals(true, repo.latest?.running)
        assertEquals(0, lease.closes)
        assertTrue(model.state.value.running)
    }

    private class Repository : BookSourceDebugRepository {
        var missing = false
        var loadGate: CompletableDeferred<Unit>? = null
        var releases = 0
        var writes = 0
        var latest: BookSourceDebugRecord? = null
        val leases = mutableListOf<Lease>()
        override suspend fun load(key: String): BookSourceDebugSnapshot? {
            loadGate?.await()
            return if (missing) null else BookSourceDebugSnapshot(key, "Fixture", "private json")
        }
        override suspend fun sorts(source: BookSourceDebugSnapshot, refresh: Boolean) = emptyList<BookSourceDebugSort>()
        override suspend fun acquire(source: BookSourceDebugSnapshot, event: (BookSourceDebugEvent) -> Unit) =
            Lease(event).also { leases += it }
        override suspend fun read(session: String): BookSourceDebugRecord? = null
        override suspend fun write(session: String, record: BookSourceDebugRecord) { writes++; latest = record }
        override suspend fun release(session: String) { releases++ }
    }

    private class Lease(val event: (BookSourceDebugEvent) -> Unit) : BookSourceDebugLease {
        private val done = CompletableDeferred<Unit>()
        var runs = 0
        var closes = 0
        override suspend fun run(query: String) { runs++; done.await() }
        override fun close() { if (closes == 0) { closes++; done.complete(Unit) } }
        override suspend fun awaitStopped() = Unit
    }
}
