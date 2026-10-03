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
class BookDetailServiceViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private fun data()=BookDetailData(BookDetailBook.from(Book(bookUrl="book",origin="source",tocUrl="toc")),null,emptyList(),emptyList(),emptyList(),true)
    private fun record()=BookDetailSession(BookDetailIdentity(bookUrl="book"),data())
    private fun vm(sessions:Sessions,runner:Runner,network:Network=Network())=BookDetailViewModel(
        SavedStateHandle(mapOf("book.detail.ticket" to UUID.randomUUID().toString())),Details(),sessions,network,null,serviceSession=runner)
    @Test fun restoredPendingServiceRequiresExplicitRetryAndBlocksUnrelatedNativeClaim()=runTest(dispatcher) {
        val record=record();val request=BookDetailServiceRequest("delete",BookDetailServiceKind.Delete,record.data!!.book)
        val sessions=Sessions(record.copy(pendingService=BookDetailPendingService(request),effects=listOf(BookDetailNativeEffect("share",BookDetailNativeKind.Share))))
        val runner=Runner(sessions);val model=vm(sessions,runner)
        try{runCurrent();assertTrue(model.state.value.loaded);assertFalse(model.state.value.canInteract);assertNotNull(model.state.value.error)
            assertTrue(runner.requests.isEmpty());assertNull(model.consumeEffect("share"){true});assertEquals(1,sessions.record.effects.size)
            model.retry();runCurrent();assertEquals(listOf(request),runner.requests);assertNull(model.state.value.session!!.pendingService)
            assertNotNull(model.consumeEffect("share"){true})
        }finally{model.stop();runCurrent()}
    }
    @Test fun explicitReloadDropsInterruptedIntentWithoutReexecutingSideEffect()=runTest(dispatcher) {
        val record=record();val request=BookDetailServiceRequest("delete",BookDetailServiceKind.Delete,record.data!!.book)
        val sessions=Sessions(record.copy(pendingService=BookDetailPendingService(request)));val runner=Runner(sessions);val model=vm(sessions,runner)
        try{runCurrent();model.reload();runCurrent();assertNull(model.state.value.session!!.pendingService)
            assertTrue(model.state.value.canInteract);assertTrue(runner.requests.isEmpty());assertNull(model.state.value.error)
        }finally{model.stop();runCurrent()}
    }
    @Test fun serviceCancelsAndJoinsEarlierNonCooperativeNetworkBeforeExecuting()=runTest(dispatcher) {
        val sessions=Sessions(record());val runner=Runner(sessions);val network=Network();network.gate=CompletableDeferred();val model=vm(sessions,runner,network)
        try{runCurrent();model.refreshInfo();runCurrent();assertEquals(1,network.calls)
            model.service(BookDetailServiceKind.Delete);runCurrent();assertTrue(model.state.value.busy);assertTrue(runner.requests.isEmpty())
            network.gate!!.complete(Unit);runCurrent();assertEquals(1,runner.requests.size);assertEquals("book",runner.requests.single().book.bookUrl)
            assertFalse(model.state.value.networkLoading);assertFalse(model.state.value.busy);assertEquals(0,sessions.networkCommits)
        }finally{network.gate!!.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun serviceFailureKeepsDurableReceiptAndRetryUsesOriginalStableToken()=runTest(dispatcher) {
        val sessions=Sessions(record());val runner=Runner(sessions);runner.fail=true;val model=vm(sessions,runner)
        try{runCurrent();model.service(BookDetailServiceKind.Delete,deleteOriginal=true,deleteRemote=true);runCurrent()
            assertNotNull(model.state.value.error);assertFalse(model.state.value.canInteract)
            val first=runner.requests.single();assertTrue(first.deleteOriginal);assertTrue(first.deleteRemote)
            runner.fail=false;model.retry();runCurrent();assertEquals(listOf(first,first),runner.requests)
            assertTrue(model.state.value.canInteract);assertNull(model.state.value.error)
        }finally{model.stop();runCurrent()}
    }
    @Test fun explicitCloseWhileNonCooperativeServiceFinishesDoesNotPublishLateUi()=runTest(dispatcher) {
        val sessions=Sessions(record());val runner=Runner(sessions);runner.gate=CompletableDeferred();val model=vm(sessions,runner)
        try{runCurrent();model.service(BookDetailServiceKind.Delete);runCurrent();model.close();runCurrent()
            runner.gate!!.complete(Unit);runCurrent();assertTrue(model.state.value.closed);assertNull(model.state.value.error)
            assertFalse(model.state.value.session!!.completedOperations.contains(runner.requests.single().token))
        }finally{runner.gate!!.complete(Unit);model.stop();runCurrent()}
    }
    private class Details:BookDetailRepository {
        override suspend fun resolve(identity:BookDetailIdentity):BookDetailData?=null
        override suspend fun reload(bookUrl:String)=BookDetailData(BookDetailBook.from(Book(bookUrl=bookUrl,origin="source",tocUrl="toc")),null,emptyList(),emptyList(),emptyList(),true)
        override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean)=BookDetailData(book,null,emptyList(),emptyList(),emptyList(),inBookshelf)
    }
    private class Sessions(var record:BookDetailSession):BookDetailSessionRepository {
        var networkCommits=0
        override suspend fun read(ticket:String)=record
        override suspend fun write(ticket:String,record:BookDetailSession){this.record=record}
        override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation)=error("unexpected mutation")
        override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession{networkCommits++;return record}
        override suspend fun recover(ticket:String)=record
        override suspend fun release(ticket:String)=Unit
    }
    private class Runner(private val sessions:Sessions):BookDetailServiceSessionRepository {
        val requests=mutableListOf<BookDetailServiceRequest>();var fail=false;var gate:CompletableDeferred<Unit>?=null
        override suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession {
            requests+=request;sessions.record=record.copy(pendingService=BookDetailPendingService(request),revision=record.revision+1)
            gate?.let{withContext(NonCancellable){it.await()}}
            if(fail)error("transport failed")
            return sessions.record.copy(pendingService=null,completedOperations=listOf(request.token),revision=sessions.record.revision+1).also{sessions.record=it}
        }
    }
    private class Network:BookDetailNetworkRepository {
        var calls=0;var gate:CompletableDeferred<Unit>?=null
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult{calls++;gate?.let{withContext(NonCancellable){it.await()}};return BookDetailNetworkResult(book,emptyList(),emptyList())}
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=error("unexpected toc")
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
        override suspend fun cover(book:BookDetailBook):BookDetailBook?=null
    }
}
