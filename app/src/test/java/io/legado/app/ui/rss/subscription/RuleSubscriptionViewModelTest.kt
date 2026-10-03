package io.legado.app.ui.rss.subscription

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RuleSubscriptionViewModelTest {
    private val dispatcher=StandardTestDispatcher();private val models=mutableListOf<RuleSubscriptionViewModel>();private val stores=mutableListOf<Store>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop() };stores.forEach { it.readGate?.complete(Unit);it.saveGate?.complete(Unit) };dispatcher.scheduler.runCurrent();Dispatchers.resetMain() }
    private fun model(rules:Rules,store:Store=Store(rules),saved:SavedStateHandle=SavedStateHandle())=RuleSubscriptionViewModel(rules,store,saved).also { models+=it;stores+=store }
    private fun snapshot(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun row(id:Long,name:String="Name")=RuleSubscription(id,name,"https://$id",0,id.toInt(),false,10,0,false,"js","show","source")
    @Test fun roomFlowIsLiveAndImmutableAndOpeningEachTypeStoresExactCompleteUrl()=runTest(dispatcher) {
        val rules=Rules();val vm=model(rules);runCurrent();assertFalse(vm.state.value.loading)
        val value=row(1).copy(type=2,url="https://host/"+"long".repeat(2000));rules.rows.value=listOf(value);runCurrent();vm.open(1);runCurrent()
        assertEquals(value.url,vm.state.value.navigation!!.url);assertEquals(2,vm.state.value.navigation!!.type)
        rules.rows.value=listOf(value.copy(name="Changed"));runCurrent();assertEquals("Changed",vm.state.value.rows.single().name);assertEquals("Name",value.name)
    }
    @Test fun newEditorPreservesAllFieldsAndSaveClosesOnlyAfterPersistence()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val vm=model(rules,store);runCurrent();vm.create();vm.name("Subscription");vm.url("https://subscription");vm.type(2);vm.automatic(true);vm.silent(true)
        val stable=vm.state.value.editor!!.newId;store.saveGate=CompletableDeferred();vm.save();runCurrent();assertTrue(vm.state.value.busy);assertNotNull(vm.state.value.editor);assertTrue(rules.rows.value.isEmpty())
        store.saveGate!!.complete(Unit);runCurrent();assertNull(vm.state.value.editor);assertFalse(vm.state.value.busy);val saved=rules.rows.value.single();assertEquals(stable,saved.id);assertEquals("Subscription",saved.name);assertEquals(2,saved.type);assertTrue(saved.automatic);assertTrue(saved.silent);assertEquals(24,saved.interval)
    }
    @Test fun automaticUses24ForOriginallyZeroAndZeroIntervalClearsBothCheckBoxes()=runTest(dispatcher) {
        val vm=model(Rules());runCurrent();vm.create();vm.automatic(true);assertEquals("24",vm.state.value.editor!!.interval);assertTrue(vm.state.value.editor!!.silentEnabled)
        vm.silent(true);vm.interval("0");assertFalse(vm.state.value.editor!!.automatic);assertFalse(vm.state.value.editor!!.silent);assertFalse(vm.state.value.editor!!.silentEnabled)
        vm.automatic(true);vm.interval("48");vm.automatic(false);assertEquals("0",vm.state.value.editor!!.interval);vm.automatic(true);assertEquals("24",vm.state.value.editor!!.interval)
    }
    @Test fun nonzeroAndBlankIntervalsOnlyEnableSilentWithoutForcingAutomaticAndOriginalIntervalRetainsLegacyToggleBehavior()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1).copy(interval=48,automatic=true,silent=true));val vm=model(rules);runCurrent();vm.edit(1);runCurrent();assertTrue(vm.state.value.editor!!.silentEnabled)
        vm.automatic(false);vm.automatic(true);assertEquals("0",vm.state.value.editor!!.interval);assertTrue(vm.state.value.editor!!.automatic)
        vm.interval("0");vm.interval("48");assertFalse(vm.state.value.editor!!.automatic);assertTrue(vm.state.value.editor!!.silentEnabled);vm.silent(true);vm.interval("");assertTrue(vm.state.value.editor!!.silentEnabled);assertTrue(vm.state.value.editor!!.silent)
    }
    @Test fun nullableNewIdentityAndMillionCharacterDraftRestoreOnlyTicketInSavedState()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.create();val large="large".repeat(200000);vm.name(large);vm.url(large);runCurrent();vm.flush();val stable=vm.state.value.editor!!.newId
        vm.stop();val restored=model(rules,store,snapshot(saved));runCurrent();assertEquals(large,restored.state.value.editor!!.name);assertEquals(large,restored.state.value.editor!!.url);assertNull(restored.state.value.editor!!.id);assertEquals(stable,restored.state.value.editor!!.newId)
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { size->size<100 } != false })
    }
    @Test fun cancelEditorDoesNotSaveAndDoesNotReturnOnRecreation()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.create();vm.url("input");vm.cancelEditor();runCurrent();vm.flush();vm.stop()
        val restored=model(rules,store,snapshot(saved));runCurrent();assertNull(restored.state.value.editor);assertTrue(rules.rows.value.isEmpty());assertEquals(0,rules.saves)
    }
    @Test fun blankAndDuplicateFailuresKeepInputAndExistingIdentityForCorrection()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1));val vm=model(rules);runCurrent();vm.create();vm.save();runCurrent();assertEquals(RuleSubscriptionIssue.EmptyUrl,vm.state.value.issue);assertNotNull(vm.state.value.editor)
        vm.url("https://1");vm.save();runCurrent();assertEquals(RuleSubscriptionIssue.DuplicateUrl,vm.state.value.issue);assertEquals("Name",vm.state.value.error);assertEquals(1,rules.rows.value.size)
        vm.url("https://new");vm.save();runCurrent();assertNull(vm.state.value.editor);assertEquals(2,rules.rows.value.size)
    }
    @Test fun deletedExistingEditorCannotRecreateItsIdentityAndMetadataIsMergedFromLatestRow()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1));val vm=model(rules);runCurrent();vm.edit(1);runCurrent();vm.name("Edited");rules.rows.value=listOf(row(1).copy(js="fresh",lastUpdate=999));runCurrent();vm.save();runCurrent();assertEquals("fresh",rules.rows.value.single().js);assertEquals(999L,rules.rows.value.single().lastUpdate)
        vm.edit(1);runCurrent();rules.rows.value=emptyList();runCurrent();vm.save();runCurrent();assertEquals(RuleSubscriptionIssue.Missing,vm.state.value.issue);assertNotNull(vm.state.value.editor);assertTrue(rules.rows.value.isEmpty())
    }
    @Test fun canceledAndMidGestureRestoredDragNeverWritesOrderAndUsesFreshRoomBaseline()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1),row(2),row(3));val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();assertTrue(vm.beginDrag());vm.move(1,3);assertEquals(listOf(2L,3L,1L),vm.state.value.rows.map { it.id })
        rules.rows.value=listOf(row(1,"Fresh"),row(2),row(3));runCurrent();assertEquals("Fresh",vm.state.value.rows.last().name);vm.cancelDrag();assertEquals(listOf(1L,2L,3L),vm.state.value.rows.map { it.id });assertEquals(0,rules.reorders.size)
        vm.beginDrag();vm.move(1,3);vm.stop();val restored=model(rules,store,snapshot(saved));runCurrent();assertEquals(listOf(1L,2L,3L),restored.state.value.rows.map { it.id });assertTrue(rules.reorders.isEmpty())
    }
    @Test fun normalDragReleaseCommitsOnceAndUnchangedReleaseDoesNotWrite()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1),row(2));val vm=model(rules);runCurrent();vm.beginDrag();vm.finishDrag();runCurrent();assertTrue(rules.reorders.isEmpty())
        vm.beginDrag();vm.move(1,2);vm.finishDrag();vm.finishDrag();runCurrent();assertEquals(listOf(listOf(2L,1L)),rules.reorders);assertEquals(listOf(2L,1L),vm.state.value.rows.map { it.id })
    }
    @Test fun deleteOnlyTouchesRequestedSubscriptionAndDoesNotRunOrMutateItsImportSources()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1),row(2));val vm=model(rules);runCurrent();vm.delete(1);runCurrent();assertEquals(listOf(2L),vm.state.value.rows.map { it.id });assertEquals(0,rules.saves)
    }
    @Test fun committedSaveWithFinalDiskFailureRecoversAndDoesNotDuplicateAndLocksPendingInput()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.create();vm.url("https://new");store.failFinal=true;vm.save();runCurrent()
        assertTrue(vm.state.value.pendingSave);assertEquals(1,rules.rows.value.size);vm.url("late input");assertEquals("https://new",vm.state.value.editor!!.url);vm.stop()
        val restored=model(rules,store,snapshot(saved));runCurrent();assertNull(restored.state.value.editor);assertEquals(1,rules.saves);assertEquals(1,rules.rows.value.size)
    }
    @Test fun externalTargetChangeCannotBeOverwrittenOnRecoveryAndDraftRemainsCancelable()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.create();vm.url("https://new");store.failFinal=true;vm.save();runCurrent();rules.rows.value=rules.rows.value.map { it.copy(name="External") };vm.stop()
        val restored=model(rules,store,snapshot(saved));runCurrent();assertEquals(RuleSubscriptionIssue.Conflict,restored.state.value.issue);assertEquals("External",rules.rows.value.single().name);assertTrue(restored.state.value.pendingSave);restored.cancelEditor();runCurrent();assertNull(restored.state.value.editor)
    }
    @Test fun stoppedNonCooperativeLoadFailureCannotPublishOrOverwriteSession()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);store.readGate=CompletableDeferred();store.failRead=true;val vm=model(rules,store);runCurrent();vm.stop();val before=vm.state.value;store.readGate!!.complete(Unit);runCurrent();assertEquals(before,vm.state.value)
    }
    @Test fun realCloseReleasesDiskAfterParentCancellationAndClosedRestorationDoesNotLoad()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.create();vm.url("large private");runCurrent();vm.close();runCurrent();assertTrue(store.records.isEmpty());assertEquals(1,store.released.size)
        val restored=model(rules,store,snapshot(saved));runCurrent();assertTrue(restored.state.value.closed);assertNull(restored.state.value.editor);assertEquals(0,rules.saves)
    }
    @Test fun unreadDiskDraftCannotBeOverwrittenAndRetryRestoresOriginalInput()=runTest(dispatcher) {
        val rules=Rules();val store=Store(rules);val saved=SavedStateHandle(mapOf("rule.subscription.ticket" to "ticket"))
        val original=RuleSubscriptionDraft(editor=RuleSubscriptionEditor(newId=20,name="Restored",url="original"));store.records["ticket"]=original;store.failRead=true
        val vm=model(rules,store,saved);runCurrent();assertFalse(vm.state.value.loaded);vm.create();assertNull(vm.state.value.editor);assertEquals(original,store.records["ticket"])
        store.failRead=false;vm.retry();runCurrent();assertTrue(vm.state.value.loaded);assertEquals("original",vm.state.value.editor!!.url)
    }
    @Test fun pendingImportIsConsumedBeforeDeliveryAndCannotRepeatAfterDiskRestore()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1));val store=Store(rules);val saved=SavedStateHandle();val vm=model(rules,store,saved);runCurrent();vm.open(1);runCurrent();val token=vm.state.value.navigation!!.token
        assertEquals("https://1",vm.consumeOpen(token){true}!!.url);assertNull(vm.consumeOpen(token){true});vm.stop();val restored=model(rules,store,snapshot(saved));runCurrent();assertNull(restored.state.value.navigation)
    }
    @Test fun canceledOrPausedNonCooperativeDiskClaimRollsBackOriginalPendingImport()=runTest(dispatcher) {
        val rules=Rules();rules.rows.value=listOf(row(1));val store=Store(rules);val vm=model(rules,store);runCurrent();vm.open(1);runCurrent();val pending=vm.state.value.navigation!!
        val gate=CompletableDeferred<Unit>();store.claimGate=gate;val job=launch { vm.consumeOpen(pending.token){true};error("Canceled claim delivered") };runCurrent();job.cancel();gate.complete(Unit);runCurrent();assertTrue(job.isCancelled);assertEquals(pending,vm.state.value.navigation);assertEquals(pending,store.records.values.single().navigation)
        assertNull(vm.consumeOpen(pending.token){false});assertEquals(pending,vm.consumeOpen(pending.token){true});assertNull(vm.state.value.navigation)
    }
    private class Rules:RuleSubscriptionRepository {
        val rows=MutableStateFlow<List<RuleSubscription>>(emptyList());val reorders=mutableListOf<List<Long>>();var saves=0
        override fun rows()=rows
        override suspend fun load(id:Long)=rows.value.find { it.id==id }
        override suspend fun save(input:RuleSubscriptionInput)=saveJournaled(input,123,{})
        override suspend fun saveJournaled(input:RuleSubscriptionInput,newId:Long,journal:(RuleSubscriptionSave)->Unit):RuleSubscription {
            if(input.url.isBlank()) throw RuleSubscriptionEmptyUrl()
            val before=input.id?.let { id->load(id) ?: throw RuleSubscriptionMissing() }
            rows.value.find { it.url==input.url && it.id!=input.id }?.let { throw RuleSubscriptionDuplicateUrl(it.name) }
            val target=(before ?: RuleSubscription(newId,"","",0,(rows.value.maxOfOrNull { it.order } ?: 0)+1,false,42,0,false,null,null,null)).copy(name=input.name,url=input.url,type=input.type,automatic=input.automatic,interval=input.interval,silent=input.silent)
            journal(RuleSubscriptionSave(before,target));saves++;rows.value=rows.value.filter { it.id!=target.id }+target;return target
        }
        override suspend fun recoverSave(plan:RuleSubscriptionSave):RuleSubscription { if(load(plan.target.id)!=plan.target) throw RuleSubscriptionConflict();return plan.target }
        override suspend fun delete(id:Long) { rows.value=rows.value.filter { it.id!=id } }
        override suspend fun reorder(ids:List<Long>) { reorders+=ids;rows.value=ids.mapNotNull { id->rows.value.find { it.id==id } } }
    }
    private class Store(val rules:Rules):RuleSubscriptionDraftRepository {
        val records=mutableMapOf<String,RuleSubscriptionDraft>();val released=mutableSetOf<String>();var failFinal=false;var failRead=false
        var readGate:CompletableDeferred<Unit>?=null;var saveGate:CompletableDeferred<Unit>?=null;var claimGate:CompletableDeferred<Unit>?=null
        override suspend fun read(ticket:String):RuleSubscriptionDraft? { readGate?.let { withContext(NonCancellable) { it.await() } };if(failRead) error("read failed");return records[ticket] }
        override suspend fun write(ticket:String,draft:RuleSubscriptionDraft) {
            if(draft.navigation==null && records[ticket]?.navigation!=null) { val gate=claimGate;claimGate=null;gate?.let { withContext(NonCancellable) { it.await() } } }
            check(ticket !in released);if((records[ticket]?.revision ?: -1)<=draft.revision) records[ticket]=draft }
        override suspend fun save(ticket:String,draft:RuleSubscriptionDraft):RuleSubscriptionDraft {
            saveGate?.await();var current=records[ticket]?.takeIf { it.pendingSave!=null } ?: draft;val editor=current.editor!!
            if(current.pendingSave!=null) rules.recoverSave(current.pendingSave!!)
            else rules.saveJournaled(editor.input(),editor.newId) { plan->current=current.copy(pendingSave=plan,revision=current.revision+1);records[ticket]=current }
            if(failFinal) { failFinal=false;error("final failed") };return current.copy(editor=null,pendingSave=null,revision=current.revision+1).also { records[ticket]=it }
        }
        override suspend fun release(ticket:String) { released+=ticket;records.remove(ticket) }
    }
}
