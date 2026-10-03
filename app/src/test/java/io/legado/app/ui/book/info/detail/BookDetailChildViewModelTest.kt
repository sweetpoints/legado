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
class BookDetailChildViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private fun data(intro:String="intro"):BookDetailData {
        val book=Book(bookUrl="book",name="Name",author="Author",origin="source",tocUrl="toc",intro=intro)
        return BookDetailData(BookDetailBook.from(book),null,listOf(BookDetailChapter("{}",0,"chapter","Chapter",false)),emptyList(),emptyList(),false)
    }
    private class Ledger:BookDetailChildRepository {
        var current=BookDetailChildren();var completeFailure=false;var releases=0;var firstReadGate:CompletableDeferred<Unit>?=null
        override suspend fun read(ticket:String):BookDetailChildren {
            val gate=firstReadGate;firstReadGate=null;gate?.await();return current
        }
        override suspend fun owner(ticket:String,value:BookDetailChildOwner){current=current.owner(value)}
        override suspend fun result(ticket:String,value:BookDetailChildResult){current=current.result(value)}
        override suspend fun complete(ticket:String,token:String){if(completeFailure)error("receipt write failed");current=current.complete(token)}
        override suspend fun release(ticket:String){releases++;current=BookDetailChildren()}
    }
    private class Details(val data:BookDetailData):BookDetailRepository {
        var gate:CompletableDeferred<Unit>?=null;var latest=data
        override suspend fun resolve(identity:BookDetailIdentity):BookDetailData{gate?.await();return data}
        override suspend fun reload(bookUrl:String)=latest
        override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean)=data.copy(book=book,inBookshelf=inBookshelf)
    }
    private class Sessions(var current:BookDetailSession?):BookDetailSessionRepository {
        var mutations=0;var commits=0;var mutation:BookDetailOperation?=null
        override suspend fun read(ticket:String)=current
        override suspend fun write(ticket:String,record:BookDetailSession){current=record}
        override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation):BookDetailSession {
            if(operation.token in record.completedOperations)return record
            mutations++;mutation=operation
            val effect=operation.navigation?.let{BookDetailNativeEffect(operation.token+":navigate",it,record.data!!.book,position=operation.change.position,highlightTitleLength=operation.highlightTitleLength,highlightAnchor=operation.highlightAnchor)}
            return record.copy(effects=record.effects+listOfNotNull(effect),completedOperations=record.completedOperations+operation.token,revision=record.revision+1).also{current=it}
        }
        override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession {
            commits++;return record.copy(data=request.copy(book=result.book),revision=record.revision+1).also{current=it}
        }
        override suspend fun recover(ticket:String)=current
        override suspend fun release(ticket:String){current=null}
    }
    private class Network:BookDetailNetworkRepository {
        var calls=0
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult {calls++;return BookDetailNetworkResult(book,emptyList(),emptyList())}
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=info(book,source,false,runPreUpdate)
        override suspend fun cover(book:BookDetailBook):BookDetailBook?=null
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
    }
    private class ChildServices:BookDetailChildServicesRepository {
        var removals=0;var defer=false;var removed=true
        override suspend fun discardTemporary(book:BookDetailBook):Boolean{removals++;return removed}
        override suspend fun folder(uri:String)=Unit
        override suspend fun deferHighlight(book:BookDetailBook)=defer
    }
    private class Fixture(val data:BookDetailData,stored:Boolean=true) {
        val sessions=Sessions(if(stored)BookDetailSession(BookDetailIdentity(bookUrl="book"),data)else null)
        val ledger=Ledger();val details=Details(data);val network=Network();val child=ChildServices()
        val saved=SavedStateHandle(mapOf("book.detail.ticket" to UUID.randomUUID().toString()))
        fun vm()=BookDetailViewModel(saved,details,sessions,network,BookDetailIdentity(bookUrl="book"),children=ledger,childServices=child)
        fun owner(kind:BookDetailChildKind)=BookDetailChildOwner("child",kind,"book")
    }
    @Test fun resultBeforeInitialLoadingIsDurableThenAppliedWithoutExtraEngineRequestOrLargeSavedState()=runTest(dispatcher) {
        val f=Fixture(data(),stored=false);val gate=CompletableDeferred<Unit>();f.details.gate=gate;val vm=f.vm()
        try {
            runCurrent();val owner=f.owner(BookDetailChildKind.Group);vm.registerChild(owner)
            vm.acceptChild(BookDetailChildResult(owner,number=7));runCurrent()
            assertEquals(7L,f.ledger.current.pending.single().number);assertEquals(0,f.sessions.mutations)
            gate.complete(Unit);runCurrent()
            assertEquals(1,f.sessions.mutations);assertEquals(7L,f.sessions.mutation!!.change.group);assertEquals(0,f.network.calls)
            assertTrue(f.ledger.current.pending.isEmpty());assertFalse(vm.state.value.childPending)
            assertEquals(setOf("book.detail.ticket"),f.saved.keys())
        }finally{gate.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun restoringPendingEffectCannotClaimWhileTheUnacknowledgedChildLedgerIsStillLoading()=runTest(dispatcher) {
        val f=Fixture(data());f.child.defer=true;val owner=f.owner(BookDetailChildKind.Toc)
        val position=BookDetailPosition(3,18,1,2);val gate=CompletableDeferred<Unit>()
        f.ledger.firstReadGate=gate
        f.ledger.current=BookDetailChildren().owner(owner).result(BookDetailChildResult(owner,position=position,highlightTitleLength=14,highlightAnchor="anchor"))
        f.sessions.current=f.sessions.current!!.copy(completedOperations=listOf(owner.token),effects=listOf(
            BookDetailNativeEffect(owner.token+":navigate",BookDetailNativeKind.Reader,f.data.book,highlightTitleLength=14,highlightAnchor="anchor")))
        val vm=f.vm();var delivered:BookDetailNativeEffect?=null
        try {
            runCurrent();assertFalse(vm.state.value.loaded)
            val claim=launch{delivered=vm.consumeEffect(owner.token+":navigate"){true}};runCurrent()
            assertNull(delivered);assertTrue(claim.isActive);assertNull(f.sessions.current!!.effects.single().position)
            gate.complete(Unit);runCurrent();claim.join()
            assertNull(delivered);assertFalse(vm.state.value.childPending);assertEquals(position,f.sessions.current!!.effects.single().position)
            val readyEffect=vm.consumeEffect(owner.token+":navigate"){true}
            assertEquals(position,readyEffect!!.position);assertEquals("anchor",readyEffect.highlightAnchor)
        }finally{gate.complete(Unit);vm.stop();runCurrent()}
    }
    @Test fun duplicateCoverReturnAndRestoreAfterCompletionDoNotApplyTwice()=runTest(dispatcher) {
        val f=Fixture(data());val vm=f.vm()
        try {
            runCurrent();val owner=f.owner(BookDetailChildKind.Cover);vm.registerChild(owner)
            val result=BookDetailChildResult(owner,value="cover");vm.acceptChild(result);runCurrent();vm.acceptChild(result);runCurrent()
            assertEquals(1,f.sessions.mutations)
            val restored=f.vm();try{runCurrent();assertEquals(1,f.sessions.mutations);assertFalse(restored.state.value.childPending)}finally{restored.stop();runCurrent()}
        }finally{vm.stop();runCurrent()}
    }
    @Test fun lateTocResultForAnotherBookIsConsumedWithoutNavigationOrDatabaseMutation()=runTest(dispatcher) {
        val f=Fixture(data());val owner=f.owner(BookDetailChildKind.Toc).copy(bookUrl="old-book")
        f.ledger.current=BookDetailChildren().owner(owner).result(BookDetailChildResult(owner,position=BookDetailPosition(1,2,3,4)))
        val vm=f.vm();try{runCurrent();assertEquals(0,f.sessions.mutations);assertTrue(vm.state.value.session!!.effects.isEmpty());assertTrue(f.ledger.current.pending.isEmpty())}finally{vm.stop();runCurrent()}
    }
    @Test fun tocCancellationRemovesOnlyTemporaryBookAndDoesNotEmitShelfDeleteCallback()=runTest(dispatcher) {
        val f=Fixture(data());val vm=f.vm()
        try {
            runCurrent();val owner=f.owner(BookDetailChildKind.Toc);vm.registerChild(owner);vm.acceptChild(BookDetailChildResult(owner,canceled=true));runCurrent()
            assertEquals(1,f.child.removals);assertTrue(vm.state.value.session!!.effects.isEmpty());assertFalse(vm.state.value.closed)
        }finally{vm.stop();runCurrent()}
    }
    @Test fun highlightReturnDefersPositionWriteButDurablyPreservesFullNativeReaderPositionAndAnchor()=runTest(dispatcher) {
        val f=Fixture(data());f.child.defer=true;val vm=f.vm();val anchor="word".repeat(100_000)
        try {
            runCurrent();val owner=f.owner(BookDetailChildKind.Toc);vm.registerChild(owner)
            val position=BookDetailPosition(5,22,1,2)
            vm.acceptChild(BookDetailChildResult(owner,position=position,chapterChanged=true,highlightTitleLength=14,highlightAnchor=anchor));runCurrent()
            assertNull(f.sessions.mutation!!.change.position)
            val effect=vm.state.value.session!!.effects.single();assertEquals(position,effect.position);assertEquals(anchor,effect.highlightAnchor)
            assertTrue(vm.state.value.session!!.chapterChanged);assertFalse(f.saved.keys().contains("highlightAnchor"))
        }finally{vm.stop();runCurrent()}
    }
    @Test fun failedChildAcknowledgmentShowsRetryAndPreventsPrematureNativeDeliveryWithoutRepeatingMutation()=runTest(dispatcher) {
        val f=Fixture(data());f.ledger.completeFailure=true;val vm=f.vm()
        try {
            runCurrent();val owner=f.owner(BookDetailChildKind.Toc);vm.registerChild(owner);vm.acceptChild(BookDetailChildResult(owner,position=BookDetailPosition(1,0,0,1)));runCurrent()
            assertTrue(vm.state.value.childPending);assertFalse(vm.state.value.canInteract);assertNotNull(vm.state.value.error)
            f.ledger.completeFailure=false;vm.retry();runCurrent()
            assertEquals(1,f.sessions.mutations);assertFalse(vm.state.value.childPending);assertEquals(1,vm.state.value.session!!.effects.size)
        }finally{vm.stop();runCurrent()}
    }
    @Test fun infoEditorChangedIntroResetsExpansionWhileUnchangedIntroPreservesCollapsedDraft()=runTest(dispatcher) {
        val f=Fixture(data());val vm=f.vm()
        try {
            runCurrent();vm.introExpanded(false);var owner=f.owner(BookDetailChildKind.InfoEditor);vm.registerChild(owner)
            vm.acceptChild(BookDetailChildResult(owner));runCurrent();assertFalse(vm.state.value.introExpanded)
            f.details.latest=data("new intro");owner=owner.copy(token="second");vm.registerChild(owner);vm.acceptChild(BookDetailChildResult(owner));runCurrent()
            assertTrue(vm.state.value.introExpanded)
        }finally{vm.stop();runCurrent()}
    }
}
