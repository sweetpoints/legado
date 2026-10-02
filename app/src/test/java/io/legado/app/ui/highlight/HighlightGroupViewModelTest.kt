package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.HighlightGroupRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightGroupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun renameDraftRestoresWithoutWritingThenTrimmedConfirmationWritesOnce() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(repo, saved); val store = own(vm)
        try { runCurrent(); vm.rename("A"); vm.name(" New ")
            val restored = HighlightGroupViewModel(repo, copy(saved)); val other = own(restored)
            try { runCurrent(); assertEquals(" New ", restored.state.value.name); assertEquals("A", restored.state.value.source); assertTrue(repo.calls.isEmpty())
                restored.confirmRename(); restored.confirmRename(); runCurrent(); assertEquals(listOf("rename:A:New"), repo.calls)
                assertEquals(HighlightGroupStage.None, restored.state.value.stage); assertFalse(restored.state.value.refresh)
            } finally { other.clear() }
        } finally { store.clear() }
    }
    @Test fun blankRenameAndCancelAreNoOpsAndDoNotRemoveMembership() = runTest(dispatcher) {
        val repo = Fake(); val vm = HighlightGroupViewModel(repo, SavedStateHandle()); val store = own(vm)
        try { runCurrent(); vm.rename("A"); vm.name(" \n "); vm.confirmRename(); runCurrent(); assertTrue(repo.calls.isEmpty())
            assertEquals(HighlightGroupStage.None, vm.state.value.stage); vm.delete("A"); vm.cancel(); vm.confirmDelete(); assertTrue(repo.calls.isEmpty())
        } finally { store.clear() }
    }
    @Test fun moveStageAndNullTargetRestoreSeparatelyFromRealGroupNamedNoGroup() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(repo, saved); val store = own(vm)
        try { runCurrent(); vm.delete("A"); vm.chooseMove(); val restored = HighlightGroupViewModel(repo, copy(saved)); val other = own(restored)
            try { runCurrent(); assertEquals(HighlightGroupStage.Move, restored.state.value.stage)
                restored.move("A"); restored.move("missing"); assertTrue(repo.calls.isEmpty())
                restored.move("No group"); runCurrent(); assertEquals(listOf("move:A:No group"), repo.calls); assertTrue(restored.state.value.refresh)
                restored.consumeRefresh(); restored.delete("B"); restored.chooseMove(); restored.move(null); runCurrent()
                assertEquals("move:B:null", repo.calls.last()); assertTrue(restored.state.value.refresh)
            } finally { other.clear() }
        } finally { store.clear() }
    }
    @Test fun deletePendingRefreshRestoresAndConsumePreventsReplay() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(repo, saved); val store = own(vm)
        try { runCurrent(); vm.delete("A"); vm.confirmDelete(); runCurrent(); assertEquals(listOf("delete:A"), repo.calls)
            val restoredSaved = copy(saved); val restored = HighlightGroupViewModel(repo, restoredSaved); val other = own(restored)
            try { runCurrent(); assertTrue(restored.state.value.refresh); restored.consumeRefresh(); assertNull(restoredSaved.get<Boolean>("refresh"))
                val next = HighlightGroupViewModel(repo, copy(restoredSaved)); val last = own(next)
                try { runCurrent(); assertFalse(next.state.value.refresh); assertEquals(1, repo.calls.size) } finally { last.clear() }
            } finally { other.clear() }
        } finally { store.clear() }
    }
    @Test fun mutationFailurePreservesDialogForRetryAndNeverRequestsReaderRefresh() = runTest(dispatcher) {
        val repo = Fake().apply { fails = true }; val vm = HighlightGroupViewModel(repo, SavedStateHandle()); val store = own(vm)
        try { runCurrent(); vm.delete("A"); vm.confirmDelete(); runCurrent(); assertEquals("failed", vm.state.value.error)
            assertEquals(HighlightGroupStage.Delete, vm.state.value.stage); assertFalse(vm.state.value.refresh); assertFalse(vm.state.value.busy)
            repo.fails = false; vm.confirmDelete(); runCurrent(); assertTrue(vm.state.value.refresh); assertEquals(HighlightGroupStage.None, vm.state.value.stage)
        } finally { store.clear() }
    }
    @Test fun nonCooperativeMutationAfterStopCannotPublishStateOrSavedRefresh() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }; val saved = SavedStateHandle(); val vm = HighlightGroupViewModel(repo, saved); val store = own(vm)
        try { runCurrent(); vm.delete("A"); vm.confirmDelete(); runCurrent(); val before = vm.state.value; vm.stop(); repo.gate!!.complete(Unit); runCurrent()
            assertEquals(before, vm.state.value); assertNull(saved.get<Boolean>("refresh"))
        } finally { store.clear() }
    }
    @Test fun observationPausesAndResumesWithoutLosingEditDraftAndFailureRetries() = runTest(dispatcher) {
        val repo = Fake(); val vm = HighlightGroupViewModel(repo, SavedStateHandle()); val store = own(vm)
        try { runCurrent(); assertEquals(1, repo.collectors); vm.rename("A"); vm.name("Draft"); vm.pauseObservation(); runCurrent(); assertEquals(0, repo.collectors)
            repo.values.value = listOf("B", "C"); vm.observe(); runCurrent(); assertEquals(listOf("B", "C"), vm.state.value.groups); assertEquals("Draft", vm.state.value.name)
            repo.readFails = true; vm.retry(); runCurrent(); assertEquals("read failed", vm.state.value.error)
            repo.readFails = false; vm.retry(); runCurrent(); assertNull(vm.state.value.error); assertFalse(vm.state.value.loading)
        } finally { store.clear() }
    }
    private fun own(vm: HighlightGroupViewModel) = ViewModelStore().apply { put("groups", vm) }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private class Fake : HighlightGroupRepository {
        val values = MutableStateFlow(listOf("A", "B", "No group")); var fails = false; var readFails = false; var collectors = 0; var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        override fun groups(): Flow<List<String>> = flow { if (readFails) error("read failed"); collectors++; try { emitAll(values) } finally { collectors-- } }
        private suspend fun write(value: String) { gate?.let { withContext(NonCancellable) { it.await() } }; if (fails) error("failed"); calls += value }
        override suspend fun rename(source: String, replacement: String) = write("rename:$source:$replacement")
        override suspend fun delete(source: String) = write("delete:$source")
        override suspend fun move(source: String, target: String?) = write("move:$source:$target")
    }
}
