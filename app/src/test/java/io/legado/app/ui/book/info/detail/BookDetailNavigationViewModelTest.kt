package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailNavigationViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private fun book()=BookDetailBook.from(Book(bookUrl="book",origin="source",tocUrl="toc"))
    private fun record()=BookDetailSession(BookDetailIdentity(bookUrl="book"),BookDetailData(book(),null,emptyList(),emptyList(),emptyList(),true))
    private fun vm(sessions:Sessions,network:Network=Network(),children:Children?=null,runner:Runner?=null,details:Details=Details())=BookDetailViewModel(
        SavedStateHandle(mapOf("book.detail.ticket" to UUID.randomUUID().toString())),details,sessions,network,null,children=children,serviceSession=runner)
    @Test fun readerWaitsForCancelledPreUpdateAndNeverLaunchesFromItsLateParsedOwner()=runTest(dispatcher) {
        val sessions=Sessions(record());val network=Network();network.gate=CompletableDeferred();val model=vm(sessions,network)
        try{runCurrent();model.refreshInfo();runCurrent();model.navigate(BookDetailMutation(BookDetailMutationKind.PrepareRead),BookDetailNativeKind.Reader);runCurrent()
            assertTrue(sessions.mutations.isEmpty());assertTrue(model.state.value.busy)
            network.gate!!.complete(Unit);runCurrent();assertEquals(0,sessions.networkCommits)
            assertEquals(BookDetailMutationKind.PrepareRead,sessions.mutations.single().change.kind)
            assertEquals("book",model.state.value.session!!.effects.single().book!!.bookUrl);assertFalse(model.state.value.networkLoading)
        }finally{network.gate!!.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun tocLaunchUsesTypedPrepareOperationAndExplicitCloseCancelsWaitingNavigation()=runTest(dispatcher) {
        val sessions=Sessions(record());val model=vm(sessions)
        try{runCurrent();model.navigate(BookDetailMutation(BookDetailMutationKind.PrepareToc),BookDetailNativeKind.Toc);runCurrent()
            assertEquals(BookDetailNativeKind.Toc,sessions.record.effects.single().kind);assertEquals(BookDetailMutationKind.PrepareToc,sessions.mutations.single().change.kind)
        }finally{model.stop();runCurrent()}
        val waiting=Sessions(record());val network=Network();network.gate=CompletableDeferred();val closed=vm(waiting,network)
        try{runCurrent();closed.refreshInfo();runCurrent();closed.navigate(BookDetailMutation(BookDetailMutationKind.PrepareRead),BookDetailNativeKind.Reader);runCurrent();closed.close();runCurrent()
            network.gate!!.complete(Unit);runCurrent();assertTrue(waiting.mutations.isEmpty());assertTrue(closed.state.value.closed)
        }finally{network.gate!!.complete(Unit);closed.stop();runCurrent()}
    }
    @Test fun sourceEditorRefreshStartsOnlyAfterChildAckAndUsesFreshSourceWithStableContinuationToken()=runTest(dispatcher) {
        val sessions=Sessions(record());val children=Children();val runner=Runner(sessions,children);val model=vm(sessions,children=children,runner=runner)
        val owner=BookDetailChildOwner("editor",BookDetailChildKind.SourceEditor,"book","source")
        try{runCurrent();model.registerChild(owner);model.acceptChild(BookDetailChildResult(owner));runCurrent()
            assertEquals(setOf("editor"),children.value.completed.toSet());assertEquals(1,runner.requests.size)
            assertEquals("editor:refresh",runner.requests.single().token);assertEquals("fresh source",runner.requests.single().source!!.name)
            assertFalse(model.state.value.childPending);assertNull(model.state.value.session!!.pendingService);assertFalse(model.state.value.busy)
        }finally{model.stop();runCurrent()}
    }
    @Test fun stoppedBetweenChildAckAndRefreshRetainsContinuationForExplicitRestoredRetryWithoutLateEngine()=runTest(dispatcher) {
        val sessions=Sessions(record());val children=Children();val gate=CompletableDeferred<Unit>();children.ackGate=gate
        val runner=Runner(sessions,children);val model=vm(sessions,children=children,runner=runner)
        val owner=BookDetailChildOwner("editor",BookDetailChildKind.SourceEditor,"book","source")
        try{runCurrent();model.registerChild(owner);model.acceptChild(BookDetailChildResult(owner));runCurrent()
            assertNotNull(sessions.record.pendingService);model.stop();gate.complete(Unit);runCurrent();assertTrue(runner.requests.isEmpty())
            children.ackGate=null;val restored=vm(sessions,children=children,runner=runner)
            try{runCurrent();assertTrue(runner.requests.isEmpty());assertNotNull(restored.state.value.error)
                restored.retry();runCurrent();assertEquals("editor:refresh",runner.requests.single().token);assertNull(restored.state.value.session!!.pendingService)
            }finally{restored.stop();runCurrent()}
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun sourceEditorOnPrivateSearchPreviewReloadsSourceWithoutRequiringRoomBookOrPromotingShelf()=runTest(dispatcher) {
        val initial=record();val sessions=Sessions(initial.copy(data=initial.data!!.copy(inBookshelf=false)))
        val children=Children();val runner=Runner(sessions,children);val model=vm(sessions,children=children,runner=runner,details=Details(true))
        val owner=BookDetailChildOwner("editor",BookDetailChildKind.SourceEditor,"book","source")
        try{runCurrent();model.registerChild(owner);model.acceptChild(BookDetailChildResult(owner));runCurrent()
            assertEquals("fresh source",runner.requests.single().source!!.name);assertFalse(model.state.value.data!!.inBookshelf)
            assertNull(model.state.value.error);assertFalse(model.state.value.childPending)
        }finally{model.stop();runCurrent()}
    }
    private class Details(private val missing:Boolean=false):BookDetailRepository {
        override suspend fun resolve(identity:BookDetailIdentity):BookDetailData?=null
        override suspend fun reload(bookUrl:String)=if(missing)null else BookDetailData(BookDetailBook.from(Book(bookUrl=bookUrl,origin="source",tocUrl="toc")),
            BookDetailSource.from(BookSource(bookSourceUrl="source",bookSourceName="fresh source")),emptyList(),emptyList(),emptyList(),true)
        override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean)=BookDetailData(book,BookDetailSource.from(BookSource(bookSourceUrl="source",bookSourceName="fresh source")),emptyList(),emptyList(),emptyList(),inBookshelf)
    }
    private class Sessions(var record:BookDetailSession):BookDetailSessionRepository {
        val mutations=mutableListOf<BookDetailOperation>();var networkCommits=0
        override suspend fun read(ticket:String)=record
        override suspend fun write(ticket:String,record:BookDetailSession){this.record=record}
        override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation):BookDetailSession {
            mutations+=operation
            return record.copy(effects=record.effects+BookDetailNativeEffect(operation.token,checkNotNull(operation.navigation),record.data!!.book),revision=record.revision+1).also{this.record=it}
        }
        override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession{networkCommits++;return record}
        override suspend fun recover(ticket:String)=record
        override suspend fun release(ticket:String)=Unit
    }
    private class Network:BookDetailNetworkRepository {
        var gate:CompletableDeferred<Unit>?=null
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult {
            gate?.let{withContext(NonCancellable){it.await()}}
            return BookDetailNetworkResult(BookDetailBook.from(book.materializeBook().copy(bookUrl="late owner")),emptyList(),emptyList())
        }
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=error("unexpected toc")
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
        override suspend fun cover(book:BookDetailBook):BookDetailBook?=null
    }
    private class Children:BookDetailChildRepository {
        var value=BookDetailChildren();var ackGate:CompletableDeferred<Unit>?=null
        override suspend fun read(ticket:String)=value
        override suspend fun owner(ticket:String,value:BookDetailChildOwner){this.value=this.value.owner(value)}
        override suspend fun result(ticket:String,value:BookDetailChildResult){this.value=this.value.result(value)}
        override suspend fun complete(ticket:String,token:String){withContext(NonCancellable){ackGate?.await();value=value.complete(token)}}
        override suspend fun release(ticket:String)=Unit
    }
    private class Runner(private val sessions:Sessions,private val children:Children):BookDetailServiceSessionRepository {
        val requests=mutableListOf<BookDetailServiceRequest>()
        override suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession {
            assertTrue("editor" in children.value.completed);requests+=request
            return record.copy(pendingService=null,completedOperations=record.completedOperations+request.token,revision=record.revision+1).also{sessions.record=it}
        }
    }
}
