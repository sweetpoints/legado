package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightManagementViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<HighlightManagementViewModel>()
    private val stores=mutableListOf<Store>()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){models.forEach{it.stop()};stores.forEach{it.gate?.complete(Unit)};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model(repo:Rules=Rules(),store:Store=Store(),saved:SavedStateHandle=SavedStateHandle())=
        HighlightManagementViewModel(saved,repo,store).also{models+=it;stores+=store}
    private fun restored(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)})
    @Test fun literalGroupAndUngroupedFiltersAreDistinctAndAllMeansFullTable()=runTest(dispatcher) {
        val vm=model();runCurrent();assertEquals(4,vm.state.value.visible.size)
        vm.filter("A,B");assertEquals(listOf("a"),vm.state.value.visible.map{it.uuid})
        vm.filter(HighlightManagementState.UNGROUPED);assertEquals(listOf("c"),vm.state.value.visible.map{it.uuid})
        vm.filter("未分组");assertEquals(listOf("d"),vm.state.value.visible.map{it.uuid})
        vm.filter(null);assertEquals(4,vm.state.value.visible.size)
    }
    @Test fun selectAllFalseInvertsOnlyDisplayedRulesAndFilterDropsHiddenSelection()=runTest(dispatcher) {
        val vm=model();runCurrent();vm.select("a");vm.selectAll(false);assertEquals(setOf("b","c","d"),vm.state.value.draft.selection)
        vm.filter("A,B");assertTrue(vm.state.value.selected.isEmpty());vm.selectAll();assertEquals(listOf("a"),vm.state.value.selected.map{it.uuid})
    }
    @Test fun vanishedActiveGroupFallsBackToAllAndDeletedSelectionIsRemoved()=runTest(dispatcher) {
        val rules=Rules();val vm=model(rules);runCurrent();vm.filter("A,B");vm.select("a");runCurrent()
        rules.rows.value=rules.rows.value.filter{it.uuid!="a"};runCurrent()
        assertNull(vm.state.value.draft.filter);assertTrue(vm.state.value.draft.selection.isEmpty());assertEquals(3,vm.state.value.visible.size)
    }
    @Test fun selectedFullSnapshotShareAndExportAllRemainDistinctUnderGroupFilter()=runTest(dispatcher) {
        val vm=model();runCurrent();vm.filter("A,B");vm.select("a");vm.share();vm.export(true);runCurrent()
        val effects=vm.state.value.draft.effects
        assertEquals(listOf("a"),effects.first{it.action==HighlightManagementAction.Share}.rules.map{it.uuid})
        assertEquals(listOf("a","b","c","d"),effects.first{it.action==HighlightManagementAction.Export}.rules.map{it.uuid})
        assertEquals("style a",effects.first().rules.single().style)
    }
    @Test fun deleteRequiresConfirmationAndCancellationHasNoRepositoryWrite()=runTest(dispatcher) {
        val rules=Rules();val vm=model(rules);runCurrent();vm.requestDelete("a");assertEquals("a",vm.state.value.draft.deletionName)
        vm.dismissDelete();runCurrent();assertTrue(rules.deleted.isEmpty())
        vm.select("a");vm.select("b");vm.requestDelete();vm.deleteConfirmed();runCurrent()
        assertEquals(listOf(setOf("a","b")),rules.deleted);assertTrue(vm.state.value.draft.deletion.isEmpty())
        assertEquals(HighlightManagementAction.Refresh,vm.state.value.draft.effects.single().action)
    }
    @Test fun enabledAndTopBottomCaptureOnlyCurrentCheckedUuidsAndRefreshAfterSuccess()=runTest(dispatcher) {
        val rules=Rules();val vm=model(rules);runCurrent();vm.select("b");vm.enableSelection(false);runCurrent()
        assertEquals(listOf(setOf("b") to false),rules.enabled);vm.moveSelection(true);runCurrent()
        assertEquals(listOf(setOf("b") to true),rules.moved);assertEquals(2,vm.state.value.draft.effects.count{it.action==HighlightManagementAction.Refresh})
    }
    @Test fun dragCancelAndMidGestureRestoreNeverWriteRepository()=runTest(dispatcher) {
        val rules=Rules();val store=Store();val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent()
        vm.beginReorder();vm.reorder("a","c");assertEquals(listOf("b","c","a","d"),vm.state.value.visible.map{it.uuid})
        vm.flush();vm.stop();val restored=model(rules,store,restored(saved));runCurrent()
        assertEquals(listOf("a","b","c","d"),restored.state.value.visible.map{it.uuid});assertTrue(rules.reordered.isEmpty())
        restored.beginReorder();restored.reorder("a","d");restored.cancelGesture();assertEquals("a",restored.state.value.visible.first().uuid);assertTrue(rules.reordered.isEmpty())
    }
    @Test fun explicitDragReleaseCommitsExactlyOnce()=runTest(dispatcher) {
        val rules=Rules();val vm=model(rules);runCurrent();vm.beginReorder();vm.reorder("a","c");vm.finishReorder();vm.finishReorder();runCurrent()
        assertEquals(listOf(listOf("b","c","a","d")),rules.reordered)
    }
    @Test fun slidingSelectionCancelAndRestorationUseBaselineNotPreview()=runTest(dispatcher) {
        val rules=Rules();val store=Store();val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.select("a")
        vm.beginRange();vm.range(setOf("a","b"));assertEquals(setOf("b"),vm.state.value.draft.selection)
        runCurrent();vm.flush();assertEquals(setOf("a"),store.records[vm.ticket]!!.selection)
        vm.cancelGesture();assertEquals(setOf("a"),vm.state.value.draft.selection)
        vm.beginRange();vm.range(setOf("b","c"));vm.stop();val restored=model(rules,store,restored(saved));runCurrent();assertEquals(setOf("a"),restored.state.value.draft.selection)
    }
    @Test fun rangeReleaseAndRetractionUseToggleAndReverseBaseline()=runTest(dispatcher) {
        val vm=model();runCurrent();vm.select("a");vm.beginRange();vm.range(setOf("a","b","c"));assertEquals(setOf("b","c"),vm.state.value.draft.selection)
        vm.range(setOf("a","b"));assertEquals(setOf("b"),vm.state.value.draft.selection);vm.finishRange();runCurrent();assertEquals(setOf("b"),vm.state.value.draft.selection)
    }
    @Test fun largeGroupAndConfirmationRestoreFromDiskWithOnlySmallSavedTicket()=runTest(dispatcher) {
        val rules=Rules();val group="group".repeat(200000);rules.rows.value=listOf(rules.rows.value.first().copy(group=group,name="name".repeat(200000)))
        val store=Store();val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.filter(group);vm.requestDelete("a");runCurrent();vm.flush();vm.stop()
        val restored=model(rules,store,restored(saved));runCurrent();assertEquals(group,restored.state.value.draft.filter);assertEquals("name".repeat(200000),restored.state.value.draft.deletionName)
        assertTrue(saved.keys().all{(saved.get<Any?>(it) as? String)?.length?.let{n->n<100}!=false})
    }
    @Test fun nativeDeliveryConsumesBeforeCallbackAndDoesNotRepeatAfterRestore()=runTest(dispatcher) {
        val store=Store();val saved=SavedStateHandle();val vm=model(store=store,saved=saved);runCurrent();vm.action(HighlightManagementAction.Edit,12);runCurrent()
        val effect=vm.state.value.draft.effects.single();assertNull(vm.consume(effect.token){false});assertEquals(12L,vm.consume(effect.token){true}!!.id)
        assertTrue(store.records[vm.ticket]!!.effects.isEmpty());assertNull(vm.consume(effect.token){true});vm.stop()
        val restored=model(store=store,saved=restored(saved));runCurrent();assertTrue(restored.state.value.draft.effects.isEmpty())
    }
    @Test fun nonCooperativeCanceledClaimRestoresReceiptForNextResumedDelivery()=runTest(dispatcher) {
        val store=Store();val vm=model(store=store);runCurrent();vm.action(HighlightManagementAction.Import);runCurrent();val effect=vm.state.value.draft.effects.single()
        val gate=CompletableDeferred<Unit>();store.gate=gate;val job=launch{vm.consume(effect.token){true};error("Canceled delivery")};runCurrent();job.cancel();gate.complete(Unit);runCurrent()
        assertTrue(job.isCancelled);assertEquals(effect,store.records[vm.ticket]!!.effects.single());assertEquals(effect,vm.consume(effect.token){true})
    }
    @Test fun unreadSessionCannotBeOverwrittenAndFailedWriteRetriesLatestDraft()=runTest(dispatcher) {
        val store=Store();val saved=SavedStateHandle(mapOf(HighlightManagementViewModel.KEY to "ticket"));store.records["ticket"]=HighlightManagementDraft(selection=setOf("a"),revision=9);store.failRead=true
        val vm=model(store=store,saved=saved);runCurrent();vm.select("b");assertEquals(setOf("a"),store.records["ticket"]!!.selection)
        store.failRead=false;vm.retry();runCurrent();assertEquals(setOf("a"),vm.state.value.draft.selection)
        store.failWrite=true;vm.select("b");runCurrent();assertNotNull(vm.state.value.error);store.failWrite=false;vm.retry();runCurrent();assertEquals(setOf("a","b"),store.records["ticket"]!!.selection)
    }
    @Test fun exportResultUsesOwnedReceiptAndCannotRepeatOrAllowOverlappingExports()=runTest(dispatcher) {
        val vm=model();runCurrent();vm.export(true);vm.export(true);runCurrent();val effect=vm.state.value.draft.effects.single()
        assertEquals(effect,vm.consume(effect.token){true});vm.export(true);assertTrue(vm.state.value.draft.effects.isEmpty())
        vm.exportResult("content://export/"+"large".repeat(200000));runCurrent();assertNull(vm.state.value.draft.exporting);assertNotNull(vm.state.value.draft.exportResult)
        vm.copyExportResult();runCurrent();assertNull(vm.state.value.draft.exportResult);assertEquals(HighlightManagementAction.Copy,vm.state.value.draft.effects.single().action)
        vm.exportResult("duplicate");runCurrent();assertNull(vm.state.value.draft.exportResult)
    }
    @Test fun realCloseReleasesAndClosedRestoreSkipsRoomSubscription()=runTest(dispatcher) {
        val rules=Rules();val store=Store();val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.select("a");runCurrent();vm.close();runCurrent()
        assertTrue(vm.state.value.closed);assertTrue(store.records.isEmpty());val count=rules.subscriptions
        val restored=model(rules,store,restored(saved));runCurrent();assertTrue(restored.state.value.closed);assertFalse(restored.state.value.loading);assertEquals(count,rules.subscriptions)
    }
    @Test fun exportCallbackArrivingBeforeLoadWaitsAndRejectsUnownedOrDuplicateTokens()=runTest(dispatcher) {
        val store=Store();val saved=SavedStateHandle(mapOf(HighlightManagementViewModel.KEY to "ticket"))
        store.records["ticket"]=HighlightManagementDraft(exporting="owned",revision=5)
        val vm=model(store=store,saved=saved);vm.exportResult("first","owned");runCurrent();assertEquals("first",vm.state.value.draft.exportResult)
        vm.exportResult("duplicate","owned");runCurrent();assertEquals("first",vm.state.value.draft.exportResult)
        vm.dismissExportResult();vm.export(true);runCurrent();val next=vm.state.value.draft.effects.single();vm.consume(next.token){true}
        vm.exportResult("late-old","owned");runCurrent();assertEquals(next.token,vm.state.value.draft.exporting);assertNull(vm.state.value.draft.exportResult)
        vm.exportResult("new",next.token);runCurrent();assertEquals("new",vm.state.value.draft.exportResult)
    }
    @Test fun importFullResultUriIsQueuedForLifecycleDeliveryAndRestoresWithoutBundleInput()=runTest(dispatcher) {
        val store=Store();val saved=SavedStateHandle();val vm=model(store=store,saved=saved)
        val uri="content://provider/"+"large".repeat(200000);vm.importResult(uri);runCurrent();vm.flush();vm.stop()
        val restored=model(store=store,saved=restored(saved));runCurrent();val effect=restored.state.value.draft.effects.single()
        assertEquals(HighlightManagementAction.Import,effect.action);assertEquals(uri,effect.text)
        assertTrue(saved.keys().all{(saved.get<Any?>(it) as? String)?.length?.let{n->n<100}!=false})
    }
    @Test fun initialRoomFlowFailureCanRetryWithoutLosingLoadedSessionSelection()=runTest(dispatcher) {
        val rules=Rules();rules.failRows=true;val store=Store();val saved=SavedStateHandle(mapOf(HighlightManagementViewModel.KEY to "ticket"))
        store.records["ticket"]=HighlightManagementDraft(selection=setOf("a"),revision=1)
        val vm=model(rules,store,saved);runCurrent();assertFalse(vm.state.value.rowsReady);assertNotNull(vm.state.value.error);assertFalse(vm.state.value.loading)
        rules.failRows=false;vm.retry();runCurrent();assertTrue(vm.state.value.rowsReady);assertEquals(setOf("a"),vm.state.value.draft.selection);assertEquals(4,vm.state.value.visible.size)
    }
    @Test fun emptyExportQueuesLocalizedHostNotificationWithoutStagingAnyNativePayload()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=emptyList();val vm=model(rules);runCurrent();vm.export(true);runCurrent()
        val effect=vm.state.value.draft.effects.single();assertEquals(HighlightManagementAction.EmptyExport,effect.action);assertTrue(effect.rules.isEmpty())
        assertEquals(effect,vm.consume(effect.token){true});assertTrue(vm.state.value.draft.effects.isEmpty());assertNull(vm.state.value.draft.exporting)
    }
    private class Rules:HighlightManagementRepository {
        val rows=MutableStateFlow(listOf("a","b","c","d").mapIndexed{index,id->HighlightManagedRule(index+1L,id,id,"pattern $id",false,"scope",true,"style $id",index,3000,when(id){"a"->"A,B";"b"->"B";"d"->"未分组";else->null},false,true)})
        var subscriptions=0;var failRows=false;val enabled=mutableListOf<Pair<Set<String>,Boolean>>();val moved=mutableListOf<Pair<Set<String>,Boolean>>();val deleted=mutableListOf<Set<String>>();val reordered=mutableListOf<List<String>>()
        override fun rows():Flow<List<HighlightManagedRule>> = rows.onStart{subscriptions++;if(failRows)error("observe failed")}
        override fun groups():Flow<List<String>> = flowOf(listOf("A,B","B","未分组"))
        override suspend fun enable(uuids:Set<String>,enabled:Boolean){this.enabled+=uuids to enabled}
        override suspend fun delete(uuids:Set<String>){deleted+=uuids;rows.value=rows.value.filter{it.uuid !in uuids}}
        override suspend fun move(uuids:Set<String>,toTop:Boolean){moved+=uuids to toTop}
        override suspend fun reorder(visibleOrder:List<String>){reordered+=visibleOrder}
    }
    private class Store:HighlightManagementSessionRepository {
        val records=mutableMapOf<String,HighlightManagementDraft>();val released=mutableSetOf<String>();var failRead=false;var failWrite=false;var gate:CompletableDeferred<Unit>?=null
        override suspend fun read(ticket:String):HighlightManagementDraft?{if(failRead)error("read failed");return records[ticket]}
        override suspend fun write(ticket:String,draft:HighlightManagementDraft){
            if(records[ticket]?.effects?.isNotEmpty()==true && draft.effects.isEmpty()) {val current=gate;gate=null;current?.let{withContext(NonCancellable){it.await()}}}
            check(ticket !in released);if(failWrite)error("write failed");if((records[ticket]?.revision ?: -1)<=draft.revision)records[ticket]=draft
        }
        override suspend fun release(ticket:String){released+=ticket;records.remove(ticket)}
    }
}
