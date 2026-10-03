package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private fun book(url:String="book")=BookDetailBook.from(Book(bookUrl=url,name="Name",author="Author",origin="source",tocUrl="toc"))
    private fun data(book:BookDetailBook=book(),shelf:Boolean=true)=BookDetailData(book,null,listOf(BookDetailChapter("{}",0,"chapter","Chapter",false)),emptyList(),emptyList(),shelf)
    private fun record()=BookDetailSession(BookDetailIdentity("Name","Author","book"),data())
    private fun saved(ticket:String)=SavedStateHandle(mapOf("book.detail.ticket" to ticket))
    private fun vm(sessions:Sessions,engine:Network=Network(),handle:SavedStateHandle=saved(sessions.ticket),details:Details=Details(),service:BookDetailServiceSessionRepository?=null)=
        BookDetailViewModel(handle,details,sessions,engine,BookDetailIdentity("Name","Author","book"),serviceSession=service)
    @Test fun queuedCoverRechecksBusyAfterWriteLockAndWaitsForServiceOwnerBeforeWriting()=runTest(dispatcher) {
        val sessions=Sessions(record());val write=CompletableDeferred<Unit>();val cover=CompletableDeferred<Unit>();val service=CompletableDeferred<Unit>()
        val network=Network().apply{coverPath="rule";coverGate=cover}
        var serviceStarted=false
        val runner=object:BookDetailServiceSessionRepository {
            override suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession {
                serviceStarted=true;service.await();return record.copy(revision=record.revision+1).also{sessions.record=it}
            }
        }
        val model=vm(sessions,network,service=runner)
        try{runCurrent();sessions.writeGate=write;model.queue(BookDetailNativeKind.Share);runCurrent()
            cover.complete(Unit);runCurrent();model.service(BookDetailServiceKind.Refresh);runCurrent()
            assertTrue(model.state.value.busy);write.complete(Unit);runCurrent()
            assertTrue(serviceStarted);assertTrue(model.state.value.busy);assertNull(sessions.lastMutation)
            service.complete(Unit);runCurrent();assertEquals("rule",model.state.value.data!!.book.cover.path)
            assertTrue(sessions.lastMutation!!.change.onlyIfCoverMissing);assertFalse(model.state.value.busy)
        }finally{write.complete(Unit);cover.complete(Unit);service.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun completingCoverDoesNotReleaseBusyOwnedByAQueuedNativeClaim()=runTest(dispatcher) {
        val sessions=Sessions(record());val cover=CompletableDeferred<Unit>();val mutate=CompletableDeferred<Unit>();val claimWrite=CompletableDeferred<Unit>()
        val network=Network().apply{coverPath="rule";coverGate=cover};val model=vm(sessions,network)
        var claim:Job?=null
        try{runCurrent();model.queue(BookDetailNativeKind.Share);runCurrent();val token=model.state.value.session!!.effects.first().token
            sessions.mutateGate=mutate;cover.complete(Unit);runCurrent();assertTrue(model.state.value.busy)
            claim=launch{model.consumeEffect(token){true}};runCurrent();sessions.writeGate=claimWrite
            mutate.complete(Unit);runCurrent();assertTrue(model.state.value.busy)
            claimWrite.complete(Unit);runCurrent();claim.join();assertFalse(model.state.value.busy)
            assertEquals("rule",model.state.value.data!!.book.cover.path)
        }finally{cover.complete(Unit);mutate.complete(Unit);claimWrite.complete(Unit);claim?.cancelAndJoin();model.stop();runCurrent()}
    }
    @Test fun ruleLookupCannotWriteAfterIdentityRenameWhilePending()=runTest(dispatcher) {
        val sessions=Sessions(record());val cover=CompletableDeferred<Unit>();val network=Network().apply{coverPath="old-name-rule";coverGate=cover}
        val model=vm(sessions,network,details=Details().apply{reloaded=data(BookDetailBook.from(book().materializeBook().apply{name="Renamed"}))})
        try{runCurrent();model.reload();runCurrent();assertEquals("Renamed",model.state.value.data!!.book.name)
            cover.complete(Unit);runCurrent();assertNull(sessions.lastMutation);assertNull(model.state.value.data!!.book.cover.path)
        }finally{cover.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun missingCoverRunsRuleAndDurablyPatchesWithoutRepeatingDetailNetwork()=runTest(dispatcher) {
        val sessions=Sessions(record());val network=Network().apply{coverPath="generated"};val model=vm(sessions,network)
        try{runCurrent();assertEquals("generated",model.state.value.data!!.book.cover.path)
            assertEquals(0,network.calls);assertEquals(1,network.coverCalls)
            assertTrue(sessions.lastMutation!!.change.onlyIfCoverMissing)
            val restored=vm(sessions,network)
            try{runCurrent();assertEquals(1,network.coverCalls)}finally{restored.stop();runCurrent()}
        }finally{model.stop();runCurrent()}
    }
    @Test fun lateRuleLookupDoesNotReplaceExplicitCoverChosenWhileItWasRunning()=runTest(dispatcher) {
        val sessions=Sessions(record());val gate=CompletableDeferred<Unit>();val network=Network().apply{coverPath="rule";coverGate=gate};val model=vm(sessions,network)
        try{runCurrent();model.mutate(BookDetailMutation(BookDetailMutationKind.Cover,text="chosen"));runCurrent()
            gate.complete(Unit);runCurrent();assertEquals("chosen",model.state.value.data!!.book.cover.path)
            assertFalse(sessions.lastMutation!!.change.onlyIfCoverMissing);assertFalse(model.state.value.busy)
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun closingDuringNonCooperativeCoverLookupDoesNotPersistOrPublishItsLateResult()=runTest(dispatcher) {
        val sessions=Sessions(record());val gate=CompletableDeferred<Unit>();val network=Network().apply{coverPath="rule";coverGate=gate};val model=vm(sessions,network)
        try{runCurrent();model.close();gate.complete(Unit);runCurrent()
            assertNull(sessions.lastMutation);assertTrue(model.state.value.closed);assertEquals(1,sessions.releases)
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun restoredCachedResponseDoesNotAutomaticallyExecuteEngineAndSavedStateContainsOnlySmallIdsAndFlags()=runTest(dispatcher) {
        val sessions=Sessions(record());val engine=Network();val handle=saved(sessions.ticket);val vm=vm(sessions,engine,handle)
        try{runCurrent();assertTrue(vm.state.value.loaded);assertEquals(0,engine.calls);assertEquals("Name",vm.state.value.data!!.book.name)
            vm.introExpanded(false);assertFalse(vm.state.value.introExpanded)
            assertTrue(handle.keys().all{it in setOf("book.detail.ticket","book.detail.intro.expanded")});assertFalse(handle.keys().contains("bookUrl"))
        }finally{vm.stop();runCurrent()}
    }
    @Test fun interruptedRestoredRequestShowsRetryAndDoesNotRepeatPreUpdateScript()=runTest(dispatcher) {
        val sessions=Sessions(record().copy(running=true));val network=Network();val vm=vm(sessions,network)
        try{runCurrent();assertTrue(vm.state.value.error!!.contains("interrupted"));assertEquals(0,network.calls)
            vm.retry();runCurrent();assertEquals(1,network.calls);assertFalse(vm.state.value.networkLoading)
        }finally{vm.stop();runCurrent()}
    }
    @Test fun pendingDurablePlanRestoresThroughRepositoryWithoutCallingEngine()=runTest(dispatcher) {
        val request=data();val pending=BookDetailPendingNetwork("network",request,BookDetailNetworkResult(request.book,emptyList(),emptyList()),false)
        val sessions=Sessions(record().copy(pendingNetwork=pending));val network=Network();val vm=vm(sessions,network)
        try{runCurrent();assertEquals(1,sessions.recoveries);assertEquals(0,network.calls);assertNull(vm.state.value.session!!.pendingNetwork)}finally{vm.stop();runCurrent()}
    }
    @Test fun laterRequestWaitsForCancelledNonCooperativeEarlierRequestAndDiscardsItsResponse()=runTest(dispatcher) {
        val sessions=Sessions(record());val network=Network();val first=CompletableDeferred<Unit>();network.firstGate=first;val vm=vm(sessions,network)
        try{runCurrent();vm.refreshInfo();runCurrent();assertEquals(1,network.calls)
            vm.refreshInfo();runCurrent();assertEquals(1,network.calls);assertTrue(vm.state.value.networkLoading)
            first.complete(Unit);runCurrent();assertEquals(2,network.calls);assertEquals(1,sessions.networkCommits)
            assertEquals("result-2",vm.state.value.data!!.book.bookUrl);assertFalse(vm.state.value.networkLoading)
        }finally{first.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun successfulNativeClaimIsConsumedOnceAndRestoredOwnerDoesNotDeliverItAgain()=runTest(dispatcher) {
        val sessions=Sessions(record());val vm=vm(sessions)
        try{runCurrent();vm.queue(BookDetailNativeKind.Share);runCurrent();val token=vm.state.value.session!!.effects.single().token
            assertEquals(BookDetailNativeKind.Share,vm.consumeEffect(token){true}!!.kind);assertNull(vm.consumeEffect(token){true})
            val restored=vm(sessions);try{runCurrent();assertTrue(restored.state.value.session!!.effects.isEmpty())}finally{restored.stop();runCurrent()}
        }finally{vm.stop();runCurrent()}
    }
    @Test fun pauseDuringDurableClaimRollsBackBeforeAllowingNewResumedDelivery()=runTest(dispatcher) {
        val sessions=Sessions(record());val vm=vm(sessions);var active=true;val gate=CompletableDeferred<Unit>()
        try{runCurrent();vm.queue(BookDetailNativeKind.Reader);runCurrent();val token=vm.state.value.session!!.effects.single().token
            sessions.claimGate=gate;var delivered:BookDetailNativeEffect?=null
            val claim=launch{delivered=vm.consumeEffect(token){active}};runCurrent();active=false;gate.complete(Unit);runCurrent();claim.join()
            assertNull(delivered);assertEquals(token,sessions.record!!.effects.single().token)
            active=true;assertNotNull(vm.consumeEffect(token){active});assertTrue(sessions.record!!.effects.isEmpty())
        }finally{gate.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun cancellationAcrossActualIoReturnHopPreservesUnDeliveredEffect()=runTest(dispatcher) {
        val sessions=Sessions(record());val vm=vm(sessions);val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        try{runCurrent();vm.queue(BookDetailNativeKind.Reader);runCurrent();val token=vm.state.value.session!!.effects.single().token
            sessions.realIoClaim=true;sessions.claimGate=gate;sessions.claimEntered=entered
            var delivered=false;val claim=launch{vm.consumeEffect(token){true};delivered=true}
            runCurrent();entered.await();claim.cancel();gate.complete(Unit);claim.join();runCurrent()
            assertFalse(delivered);assertEquals(token,sessions.record!!.effects.single().token)
            assertNotNull(vm.consumeEffect(token){true})
        }finally{gate.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun stopAndExplicitCloseIgnoreNonCooperativeLoadFailureAndReleaseSessionWithoutUiEvents()=runTest(dispatcher) {
        val sessions=Sessions(null);val details=Details();val gate=CompletableDeferred<Unit>();details.loadGate=gate
        val vm=vm(sessions,details=details)
        try{runCurrent();vm.close();runCurrent();gate.complete(Unit);runCurrent()
            assertTrue(vm.state.value.closed);assertNull(vm.state.value.error);assertEquals(1,sessions.releases);assertTrue(vm.state.value.session==null)
        }finally{gate.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun coverMutationQueuesReaderSyncAfterRepositoryCompletion()=runTest(dispatcher) {
        val sessions=Sessions(record());val vm=vm(sessions)
        try{runCurrent();vm.mutate(BookDetailMutation(BookDetailMutationKind.Cover,text="cover"));runCurrent()
            assertEquals("cover",vm.state.value.data!!.book.materializeBook().customCoverUrl);assertEquals(BookDetailNativeKind.ReaderSync,vm.state.value.session!!.effects.single().kind)
            assertFalse(vm.state.value.busy)
        }finally{vm.stop();runCurrent()}
    }
    @Test fun explicitReloadDiscardsConflictingPendingPlanAndUsesCurrentRowRatherThanReplayingIt()=runTest(dispatcher) {
        val request=data();val pending=BookDetailPendingNetwork("failed",request,BookDetailNetworkResult(book("changed"),emptyList(),emptyList()),false)
        val sessions=Sessions(record().copy(pendingNetwork=pending));sessions.recoverFailure=true
        val details=Details();details.reloaded=data(book("external"));val vm=vm(sessions,details=details)
        try{runCurrent();assertNotNull(vm.state.value.error);assertNotNull(vm.state.value.session!!.pendingNetwork)
            vm.reload();runCurrent();assertEquals("external",vm.state.value.data!!.book.bookUrl);assertNull(vm.state.value.session!!.pendingNetwork)
            assertNull(vm.state.value.error);assertEquals(1,sessions.recoveries)
        }finally{vm.stop();runCurrent()}
    }
    private class Details:BookDetailRepository {
        var loadGate:CompletableDeferred<Unit>?=null;var reloaded:BookDetailData?=null
        override suspend fun resolve(identity:BookDetailIdentity):BookDetailData? {
            loadGate?.let{withContext(NonCancellable){it.await();error("late load failed")}}
            return null
        }
        override suspend fun reload(bookUrl:String):BookDetailData?=reloaded
        override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean)=BookDetailData(book,null,emptyList(),emptyList(),emptyList(),inBookshelf)
    }
    private class Network:BookDetailNetworkRepository {
        var calls=0;var firstGate:CompletableDeferred<Unit>?=null
        var coverCalls=0;var coverPath:String?=null;var coverGate:CompletableDeferred<Unit>?=null
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult {
            val call=++calls;if(call==1)firstGate?.let{withContext(NonCancellable){it.await()}}
            val native=book.materializeBook();native.bookUrl="result-$call"
            return BookDetailNetworkResult(BookDetailBook.from(native),emptyList(),emptyList())
        }
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=info(book,source,false,runPreUpdate)
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
        override suspend fun cover(book:BookDetailBook):BookDetailBook? {
            coverCalls++;coverGate?.let{withContext(NonCancellable){it.await()}}
            val path=coverPath ?: return null;return BookDetailBook.from(book.materializeBook().apply{customCoverUrl=path})
        }
    }
    private class Sessions(var record:BookDetailSession?):BookDetailSessionRepository {
        val ticket=UUID.randomUUID().toString();var recoveries=0;var networkCommits=0;var releases=0;var recoverFailure=false
        var lastMutation:BookDetailOperation?=null;var writeGate:CompletableDeferred<Unit>?=null;var mutateGate:CompletableDeferred<Unit>?=null
        var claimGate:CompletableDeferred<Unit>?=null;var claimEntered:CompletableDeferred<Unit>?=null;var realIoClaim=false
        override suspend fun read(ticket:String)=record
        override suspend fun write(ticket:String,record:BookDetailSession) {
            writeGate?.let{writeGate=null;it.await()}
            if(this.record?.effects?.isNotEmpty()==true && record.effects.isEmpty()) {
                val wait=claimGate;claimGate=null
                if(wait!=null) {
                    if(realIoClaim)withContext(Dispatchers.IO+NonCancellable){claimEntered?.complete(Unit);wait.await();this@Sessions.record=record}
                    else withContext(NonCancellable){wait.await();this@Sessions.record=record}
                    return
                }
            }
            this.record=record
        }
        override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation):BookDetailSession {
            lastMutation=operation;mutateGate?.await()
            val data=checkNotNull(record.data);val native=data.book.materializeBook();native.customCoverUrl=operation.change.text
            val next=record.copy(data=data.copy(book=BookDetailBook.from(native)),effects=record.effects+BookDetailNativeEffect(operation.token,BookDetailNativeKind.ReaderSync),revision=record.revision+1)
            this.record=next;return next
        }
        override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession {
            networkCommits++;val next=record.copy(data=request.copy(book=result.book,chapters=result.chapters),running=false,revision=record.revision+1);this.record=next;return next
        }
        override suspend fun recover(ticket:String):BookDetailSession? {recoveries++;if(recoverFailure)throw BookDetailConflict();record=record?.copy(pendingNetwork=null,pendingMutation=null,running=false);return record}
        override suspend fun release(ticket:String){releases++;record=null}
    }
}
