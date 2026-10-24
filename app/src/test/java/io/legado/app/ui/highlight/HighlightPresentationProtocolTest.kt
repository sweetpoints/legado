package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.HighlightGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HighlightPresentationProtocolTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Groups : HighlightGroupRepository {
        val rows = MutableStateFlow(listOf("A", "B")); val calls = mutableListOf<String>()
        override fun groups() = rows
        override suspend fun rename(old: String, new: String) { calls += "$old:$new" }
        override suspend fun delete(group: String) { calls += "delete:$group" }
        override suspend fun move(from: String, to: String?) { calls += "move:$from:$to" }
    }
    @Test fun rawTwoMegabyteNameAndReversedUtf16SelectionRestoreByTicketOnlyWithoutRoomWrite() = runTest(dispatcher) {
        val repo = Groups(); val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(repo, saved)
        val store = ViewModelStore().apply { put("groups", vm) }
        try {
            runCurrent(); vm.rename("A"); val raw = " \n😀" + "X".repeat(2_000_000)
            vm.rawName(raw, 9, 1); vm.flush()
            assertEquals(setOf("highlight.groups.ticket"), saved.keys())
            val restored = HighlightGroupViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            val second = ViewModelStore().apply { put("groups", restored) }
            try { runCurrent(); assertEquals(raw, restored.state.value.name); assertEquals(9, restored.state.value.nameStart); assertEquals(1, restored.state.value.nameEnd); assertTrue(repo.calls.isEmpty()); restored.cancel(); restored.flush(); assertTrue(repo.calls.isEmpty()) }
            finally { second.clear() }
        } finally { store.clear() }
    }
    @Test fun invalidUtf16RangeDoesNotChangeDraftAndSnapshotRetainsRawRange() = runTest(dispatcher) {
        val vm = HighlightGroupViewModel(Groups(), SavedStateHandle()); val store = ViewModelStore().apply { put("groups", vm) }
        try {
            runCurrent(); vm.rename("A"); vm.rawName("😀raw", 4, 1)
            val before = vm.state.value
            assertThrows(IllegalArgumentException::class.java) { vm.rawName("bad", -1, 3) }
            assertEquals(before, vm.state.value)
            val snapshot = highlightGroupsSnapshot(before, "binding", 1)
            assertEquals("😀raw", snapshot["name"]); assertEquals(4, snapshot["nameStart"]); assertEquals(1, snapshot["nameEnd"])
            highlightGroupsCommand(vm, "back", emptyMap<String, Any?>()) { fail("Nested Back must cancel rename before closing its host") }
            assertEquals(HighlightGroupStage.None, vm.state.value.stage)
        } finally { store.clear() }
    }
    @Test fun refreshClaimRollsBackWhenLifecycleStopsDuringNonCooperativeDurableWrite() = runTest(dispatcher) {
        val records = mutableMapOf<String, HighlightGroupDraft>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var holdClaim = false
        val sessions = object : HighlightGroupDraftStore {
            override suspend fun read(ticket: String) = records[ticket]
            override suspend fun write(ticket: String, value: HighlightGroupDraft) {
                if (holdClaim && !value.refresh) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                records[ticket] = value
            }
            override suspend fun release(ticket: String) { records.remove(ticket) }
        }
        val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(Groups(), saved, sessions)
        val store = ViewModelStore().apply { put("groups", vm) }
        try {
            runCurrent(); vm.delete("A"); vm.confirmDelete(); runCurrent(); assertTrue(vm.state.value.refresh)
            var resumed = true; holdClaim = true
            val claim = launch { assertFalse(vm.claimRefresh { resumed }) }
            runCurrent(); assertTrue(vm.state.value.busy)
            resumed = false; gate.complete(Unit); runCurrent(); claim.join()
            assertTrue(vm.state.value.refresh); assertTrue(records[vm.ticket]!!.refresh)
            holdClaim = false; resumed = true
            var calls = 0
            val callbackFailure = runCatching { if (vm.claimRefresh { resumed }) { calls++; error("refresh callback failed") } }
            assertTrue(callbackFailure.isFailure); assertFalse(vm.state.value.refresh)
            assertFalse(vm.claimRefresh { resumed }); assertFalse(records[vm.ticket]!!.refresh); assertEquals(1, calls)
        } finally { store.clear() }
    }

}
