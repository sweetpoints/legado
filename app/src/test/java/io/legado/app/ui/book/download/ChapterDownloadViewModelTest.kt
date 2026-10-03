package io.legado.app.ui.book.download

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ChapterDownloadSessionRepository
import io.legado.app.model.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterDownloadViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<ChapterDownloadViewModel>()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){models.forEach{it.stop()};Dispatchers.resetMain()}
    private class Sessions(mode:ChapterDownloadMode=ChapterDownloadMode.Book):ChapterDownloadSessionRepository {
        @Volatile var stored=ChapterDownloadSession("L".repeat(2000000),mode,4,8,91)
        var failRead=false;var failWrite=false;var released=0;var writes=0
        var claimEntered:CompletableDeferred<Unit>?=null;var claimGate:CompletableDeferred<Unit>?=null
        override suspend fun create(book:Book,mode:ChapterDownloadMode,initialChapter:Int,chapterCount:Int)="ticket"
        override suspend fun read(ticket:String):ChapterDownloadSession {if(failRead)error("Read failure");return stored}
        override suspend fun write(ticket:String,value:ChapterDownloadSession){
            if(failWrite)error("Write failure")
            if(value.completed && claimGate!=null){val gate=claimGate!!;claimGate=null;withContext(Dispatchers.IO+NonCancellable){claimEntered!!.complete(Unit);gate.await();stored=value;writes++}}
            else{stored=value;writes++}
        }
        override suspend fun book(value:ChapterDownloadSession)=Book(bookUrl="owned",variable=value.bookJson)
        override suspend fun release(ticket:String){released++}
    }
    private fun model(store:Sessions,saved:SavedStateHandle=SavedStateHandle(),scope:CoroutineScope)=ChapterDownloadViewModel(saved,store,"ticket",scope).also{models+=it}
    private fun copy(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)})
    @Test fun fullBookStaysOutOfSavedStateAndSmallPartialInputRestoresWithoutSubmitting()=runTest(dispatcher){
        val store=Sessions();val saved=SavedStateHandle();val first=model(store,saved,this);runCurrent()
        first.start("");first.end("123");first.stop()
        val restored=model(store,copy(saved),this);runCurrent()
        assertEquals("",restored.state.value.start);assertEquals("123",restored.state.value.end);assertNull(restored.state.value.pending)
        assertTrue(saved.keys().all{saved.get<Any?>(it).toString().length<=5});assertEquals(0,store.writes)
    }
    @Test fun editingRejectsNonDigitsAndOversizeButBookEmptyBoundsAreAcceptedWithDiskRevisionBaseline()=runTest(dispatcher){
        val store=Sessions();val model=model(store,scope=this);runCurrent();model.start("123456");model.end("1.5")
        assertEquals("4",model.state.value.start);assertEquals("8",model.state.value.end)
        model.start("");model.end("");model.confirm();model.confirm();runCurrent()
        assertEquals(ChapterDownloadRange(-1,7),store.stored.pending!!.range);assertEquals(92L,store.stored.revision);assertEquals(1,store.writes)
    }
    @Test fun audioInvalidBoundsKeepEditorOpenWithoutWritingAndValidEndUsesExistingClamp()=runTest(dispatcher){
        val store=Sessions(ChapterDownloadMode.Audio);val model=model(store,scope=this);runCurrent();model.start("");model.confirm();runCurrent()
        assertTrue(model.state.value.invalidRange);assertEquals(0,store.writes)
        model.start("3");model.end("99");model.confirm();runCurrent();assertEquals(ChapterDownloadRange(2,7),store.stored.pending!!.range)
    }
    @Test fun readAndPendingWriteFailuresRetryWithoutNativeDelivery()=runTest(dispatcher){
        val store=Sessions().apply{failRead=true};val model=model(store,scope=this);runCurrent();assertFalse(model.state.value.loaded)
        store.failRead=false;model.retry();runCurrent();store.failWrite=true;model.confirm();runCurrent();assertNotNull(model.state.value.error);assertNull(model.state.value.pending)
        store.failWrite=false;model.retry();runCurrent();assertNotNull(model.state.value.pending);assertFalse(store.stored.completed)
    }
    @Test fun exactReceiptIsPersistedConsumedBeforeDeliveryAndNeverDeliversTwice()=runTest(dispatcher){
        val store=Sessions();val model=model(store,scope=this);runCurrent();model.confirm();runCurrent();val token=model.state.value.pending!!.token
        assertNull(model.claim("foreign"){true});assertFalse(store.stored.completed)
        val delivered=model.claim(token){true}!!
        assertTrue(store.stored.completed);assertNull(store.stored.pending);assertEquals("owned",delivered.book.bookUrl)
        assertEquals(ChapterDownloadRange(3,7),delivered.range);assertNull(model.claim(token){true})
        val restored=model(store,scope=this);runCurrent();assertTrue(restored.state.value.finished);assertNull(restored.state.value.pending)
    }
    @Test fun pauseAfterAcceptedWriteRollsReceiptBackForNextResume()=runTest(dispatcher){
        val store=Sessions();val model=model(store,scope=this);runCurrent();model.confirm();runCurrent();val token=model.state.value.pending!!.token
        var checks=0;assertNull(model.claim(token){++checks<3})
        assertFalse(store.stored.completed);assertEquals(token,store.stored.pending!!.token);assertFalse(model.state.value.busy)
        assertNotNull(model.claim(token){true});assertTrue(store.stored.completed)
    }
    @Test fun actualIoReturnToCancelledCallerRestoresUndeliveredReceipt()=runTest(dispatcher){
        val store=Sessions();val model=model(store,scope=this);runCurrent();model.confirm();runCurrent();val token=model.state.value.pending!!.token
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();store.claimEntered=entered;store.claimGate=gate
        val delivery=async{model.claim(token){true}};runCurrent();entered.await();delivery.cancel();gate.complete(Unit)
        try{delivery.await();fail("Expected cancellation")}catch(_:CancellationException){}
        delivery.join()
        assertFalse(store.stored.completed);assertEquals(token,store.stored.pending!!.token);assertFalse(model.state.value.finished)
    }
    @Test fun closeAndRestoredClosedStateReleaseOnlyOnceAndNeverLoadOrSubmit()=runTest(dispatcher){
        val store=Sessions();val saved=SavedStateHandle();val model=model(store,saved,this);runCurrent();model.close();model.close();runCurrent()
        assertEquals(1,store.released);val restored=model(store,copy(saved),this);runCurrent();assertTrue(restored.state.value.closed)
        restored.confirm();runCurrent();assertEquals(0,store.writes);assertFalse(restored.state.value.loaded)
    }
}
