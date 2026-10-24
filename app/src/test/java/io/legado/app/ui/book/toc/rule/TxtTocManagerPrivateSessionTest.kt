package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.TxtTocRuleManagementRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TxtTocManagerPrivateSessionTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private class Store : TxtTocManagementSessionRepository {
        var snapshot: TxtTocManagementSession? = null
        override suspend fun read(ticket: String) = snapshot
        override suspend fun write(ticket: String, state: TxtTocManagementSession) { snapshot = state }
    }
    @Test fun largeRawDraftSelectionAndModalRestoreThroughPrivateTicketOnly() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val store = Store(); val repo = Fake()
        val vm = TxtTocRuleManagementViewModel(repo, handle, sessions = store)
        runCurrent()
        val large = "😀\u0000".repeat(250000)
        vm.inputQuery(large); vm.querySelection(9, 2)
        vm.showOnline(true); vm.inputOnline(large); vm.onlineSelection(4, 1)
        vm.persist()
        assertEquals(setOf("txt.toc.management.ticket"), handle.keys())
        assertEquals(36, handle.get<String>("txt.toc.management.ticket")!!.length)
        val recreated = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = TxtTocRuleManagementViewModel(repo, recreated, sessions = store)
        runCurrent()
        assertEquals(large, restored.state.value.query); assertEquals(9, restored.state.value.queryStart); assertEquals(2, restored.state.value.queryEnd)
        assertEquals(large, restored.state.value.onlineInput); assertTrue(restored.state.value.online); assertEquals(4, restored.state.value.onlineStart); assertEquals(1, restored.state.value.onlineEnd)
    }
    @Test fun unfinishedPrivateReorderAndSelectionRecoverTheirBaselines() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val store = Store(); val repo = Fake()
        val vm = TxtTocRuleManagementViewModel(repo, handle, sessions = store); runCurrent()
        vm.toggle(2); vm.beginSlide(1); vm.slideTo(3); vm.move(1, 3); vm.persist()
        val restored = TxtTocRuleManagementViewModel(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), sessions = store); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), restored.state.value.rules.map { it.id }); assertEquals(setOf(2L), restored.state.value.selected); assertTrue(repo.orders.isEmpty())
    }
    @Test fun consumedPickerReceiptIsPersistedBeforeAnyReopenedOwnerCanDeliverItAgain() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val store = Store(); val repo = Fake()
        val vm = TxtTocRuleManagementViewModel(repo, handle, picker = true, sessions = store); runCurrent()
        vm.choose(2); vm.confirmChoice(); vm.persist(); assertNotNull(vm.consumeEffect()); vm.persist()
        val restored = TxtTocRuleManagementViewModel(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), picker = true, sessions = store); runCurrent()
        assertTrue(restored.state.value.pickerFinished); assertNull(restored.consumeEffect())
    }
    private class Owner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
    }
    private class BlockedStore : TxtTocManagementSessionRepository {
        var snapshot: TxtTocManagementSession? = null
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun read(ticket: String) = snapshot
        override suspend fun write(ticket: String, state: TxtTocManagementSession) {
            if (!state.values.containsKey("txt.toc.management.effect.kind")) {
                entered.complete(Unit)
                release.await()
            }
            snapshot = state
        }
    }
    @Test fun hostPauseDuringDurableClaimRestoresTheSameReceiptAndDeliversOnlyAfterResume() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val store = BlockedStore(); val repo = Fake()
        val vm = TxtTocRuleManagementViewModel(repo, handle, picker = true, sessions = store); runCurrent()
        val owner = Owner(); owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        var delivered = 0; var closed = 0
        val coordinator = TxtTocManagerNativeCoordinator(vm, owner.lifecycle, this, { true }, true, { delivered++ }, { throw it }, { closed++ })
        vm.choose(2); vm.confirmChoice(); runCurrent(); assertTrue(store.entered.isCompleted)
        owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        store.release.complete(Unit); runCurrent()
        assertEquals(0, delivered); assertEquals(0, closed); assertNotNull(vm.state.value.effect)
        assertTrue(store.snapshot!!.values.containsKey("txt.toc.management.effect.kind"))
        owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; runCurrent()
        assertEquals(1, delivered); assertEquals(1, closed); assertNull(vm.state.value.effect)
        owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; runCurrent(); assertEquals(1, delivered)
        coordinator.close()
    }
    @Test fun cancellationDuringNonCooperativeClaimRollsBackInsteadOfLosingOrDeliveringReceipt() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val store = BlockedStore(); val repo = Fake()
        val vm = TxtTocRuleManagementViewModel(repo, handle, picker = true, sessions = store); runCurrent()
        vm.choose(2); vm.confirmChoice()
        var delivered = false
        val claim = launch { if (vm.consumeEffect { true } != null) delivered = true }
        runCurrent(); assertTrue(store.entered.isCompleted)
        claim.cancel(); store.release.complete(Unit); runCurrent(); claim.join()
        assertFalse(delivered); assertNotNull(vm.state.value.effect); assertTrue(store.snapshot!!.values.containsKey("txt.toc.management.effect.kind"))
        assertNotNull(vm.consumeEffect { true }); assertNull(vm.consumeEffect { true })
    }
    private class Fake : TxtTocRuleManagementRepository {
        val rows =
            MutableStateFlow(
                listOf(
                    TxtTocRuleSnapshot(
                        1,
                        "alpha",
                        "alpha",
                        example = "Chapter A",
                        serialNumber = 42,
                    ),
                    TxtTocRuleSnapshot(2, "beta", "beta", "$1", "Chapter B", 42, false),
                    TxtTocRuleSnapshot(3, "gamma", "gamma", serialNumber = 42),
                )
            )
        val enabled = mutableListOf<Pair<List<Long>, Boolean>>()
        val deleted = mutableListOf<List<Long>>()
        val edges = mutableListOf<Pair<List<Long>, Boolean>>()
        val orders = mutableListOf<List<Long>>()
        val serialized = mutableListOf<List<TxtTocRuleSnapshot>>()
        var historyRead: suspend () -> List<String> = { emptyList() }

        override fun observe() = rows

        override suspend fun setEnabled(ids: List<Long>, enabled: Boolean) {
            this.enabled += ids to enabled
        }

        override suspend fun delete(ids: List<Long>) {
            deleted += ids
        }

        override suspend fun reorder(ids: List<Long>) {
            orders += ids
        }

        override suspend fun moveToEdge(ids: List<Long>, top: Boolean) {
            edges += ids to top
        }

        override suspend fun importDefault() {}

        override suspend fun history() = historyRead()

        override suspend fun rememberUrl(url: String) {}

        override suspend fun removeUrl(url: String) {}

        override suspend fun json(rules: List<TxtTocRuleSnapshot>): String {
            serialized += rules
            return "json"
        }

        override suspend fun shareFile(rules: List<TxtTocRuleSnapshot>): String {
            serialized += rules
            return "/cache/share.json"
        }

        override suspend fun exportSummary(url: String) = "summary"

        override suspend fun passphrase(url: String) = "phrase:$url"
    }
}
