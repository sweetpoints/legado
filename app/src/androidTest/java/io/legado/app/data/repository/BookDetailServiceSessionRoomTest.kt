package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BookDetailServiceSessionRoomTest {
    private lateinit var context:Context
    private lateinit var database:AppDatabase
    private lateinit var directory:File
    @Before fun before(){context=ApplicationProvider.getApplicationContext();database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();directory=File(context.cacheDir,"detail-service-${UUID.randomUUID()}")}
    @After fun after(){database.close();directory.deleteRecursively()}
    private fun details()=RoomBookDetailRepository(database){0}
    private fun sessions(before:(BookDetailSession)->Unit={})=FileBookDetailSessionRepository(context,
        RoomBookDetailStorageRepository(database),details(),RoomBookDetailNetworkStorageRepository(database,{_,_->},{}),directory,before)
    private suspend fun initial(ticket:String):BookDetailSession {
        val book=Book(bookUrl="remote",name="Name",author="Author",origin="source",tocUrl="toc",group=1)
        withContext(Dispatchers.IO){database.bookDao.insert(book)}
        return BookDetailSession(BookDetailIdentity(book.name,book.author,book.bookUrl),details().reload(book.bookUrl)).also{sessions().write(ticket,it)}
    }
    private fun runner(session:BookDetailSessionRepository,engine:Engine)=DefaultBookDetailServiceSessionRepository(
        AppBookDetailServicesRepository(database,engine,snapshotReading={engine.snapshots++}),details(),session,Network())
    @Test fun deletedFreshRoomOwnerAndReceiptRecoverAfterFinalAtomicFileFailureWithoutRepeatedDelete()=runBlocking {
        val ticket=UUID.randomUUID().toString();val initial=initial(ticket);val engine=Engine()
        val failed=sessions{if(it.pendingService==null && "delete" in it.completedOperations)error("final receipt failed")}
        val request=BookDetailServiceRequest("delete",BookDetailServiceKind.Delete,initial.data!!.book,deleteRemote=true)
        assertTrue(runCatching{runner(failed,engine).execute(ticket,initial,request)}.isFailure)
        assertNull(withContext(Dispatchers.IO){database.bookDao.getBook("remote")});assertEquals(1,engine.snapshots)
        assertNotNull(sessions().read(ticket)!!.pendingService!!.result)
        val restored=runner(sessions(),engine).execute(ticket,sessions().read(ticket)!!,request)
        assertEquals(1,engine.deletes);assertEquals(1,engine.snapshots)
        assertEquals(BookDetailNativeKind.Deleted,restored.effects.single().kind)
        assertNull(sessions().read(ticket)!!.pendingService)
    }
    @Test fun firstAtomicJournalFailureNeverDeletesRoomAndClosedTicketCannotRunEngine()=runBlocking {
        val ticket=UUID.randomUUID().toString();val initial=initial(ticket);val engine=Engine()
        val failed=sessions{if(it.pendingService!=null)error("first journal failed")}
        val request=BookDetailServiceRequest("delete",BookDetailServiceKind.Delete,initial.data!!.book,deleteRemote=true)
        assertTrue(runCatching{runner(failed,engine).execute(ticket,initial,request)}.isFailure)
        assertNotNull(withContext(Dispatchers.IO){database.bookDao.getBook("remote")});assertEquals(0,engine.deletes)
        sessions().release(ticket)
        assertTrue(runCatching{runner(sessions(),engine).execute(ticket,initial,request)}.isFailure)
        assertEquals(0,engine.deletes);assertNotNull(withContext(Dispatchers.IO){database.bookDao.getBook("remote")})
    }
    @Test fun realRoomDeleteOnIndependentServiceDispatcherKeepsReceiptWhenCallerIsCancelledBeforeReturnHop()=runBlocking {
        val ticket=UUID.randomUUID().toString();val initial=initial(ticket);val engine=Engine()
        val entered=CountDownLatch(1);val continueWrite=CountDownLatch(1)
        val ownedDispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val durable=sessions{if(it.pendingService?.result!=null){entered.countDown();check(continueWrite.await(10,TimeUnit.SECONDS))}}
        val services=AppBookDetailServicesRepository(database,engine,snapshotReading={engine.snapshots++},io=ownedDispatcher)
        val coordinator=DefaultBookDetailServiceSessionRepository(services,details(),durable,Network())
        val request=BookDetailServiceRequest("delete",BookDetailServiceKind.Delete,initial.data!!.book,deleteRemote=true)
        var published=false;val job=launch(Dispatchers.Default){coordinator.execute(ticket,initial,request);published=true}
        try {
            assertTrue(withContext(Dispatchers.IO){entered.await(10,TimeUnit.SECONDS)})
            assertNull(withContext(Dispatchers.IO){database.bookDao.getBook("remote")})
            job.cancel();continueWrite.countDown();job.join();assertFalse(published)
            val receipt=sessions().read(ticket)!!;assertNotNull(receipt.pendingService!!.result)
            val restored=runner(sessions(),engine).execute(ticket,receipt,request)
            assertEquals(1,engine.deletes);assertEquals(1,engine.snapshots);assertEquals(BookDetailNativeKind.Deleted,restored.effects.single().kind)
        }finally{continueWrite.countDown();job.cancelAndJoin();ownedDispatcher.close()}
    }
    @Test fun missingDirectoryDurablyUnblocksFolderPickerAndDoesNotRepeatDownloadOnRestore()=runBlocking {
        val ticket=UUID.randomUUID().toString();val initial=initial(ticket);val engine=Engine().apply{missingDirectory=true}
        val source=BookDetailSource.from(BookSource(bookSourceUrl="source",bookSourceName="Source"))
        val request=BookDetailServiceRequest("download",BookDetailServiceKind.Download,initial.data!!.book,source,
            file=BookDetailWebFile("https://example.invalid/book.txt","book.txt"),readAfter=true)
        val restored=runner(sessions(),engine).execute(ticket,initial,request)
        assertNull(restored.pendingService);assertEquals(1,engine.downloads)
        assertEquals(BookDetailNativeKind.ChooseFolder,restored.effects.single().kind)
        assertEquals("download:folder",restored.effects.single().token)
        val disk=sessions().read(ticket)!!
        assertEquals(restored,disk)
        assertEquals(disk,runner(sessions(),engine).execute(ticket,disk,request))
        assertEquals(1,engine.downloads)
        assertNotNull(withContext(Dispatchers.IO){database.bookDao.getBook("remote")})
    }
    private class Engine:BookDetailServiceEngine {
        var deletes=0;var snapshots=0;var downloads=0;var missingDirectory=false
        override suspend fun refresh(book:Book,source:BookSource?)=Unit
        override suspend fun remoteExists(book:Book)=false
        override suspend fun upload(book:Book,overwrite:Boolean)=Unit
        override suspend fun deleteRemote(book:Book):Boolean{deletes++;return true}
        override suspend fun deleteLocal(book:Book,deleteOriginal:Boolean)=Unit
        override suspend fun clearCache(book:Book)=Unit
        override suspend fun download(book:Book,source:BookSource,file:BookDetailWebFile):BookDetailDownload {
            downloads++;if(missingDirectory)throw io.legado.app.exception.NoBooksDirException();error("unexpected download")
        }
        override suspend fun archiveEntries(uri:String)=emptyList<String>()
        override suspend fun importArchive(book:Book,uri:String,entry:String)=error("unexpected archive")
    }
    private class Network:BookDetailNetworkRepository {
        override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean)=error("unexpected info")
        override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean)=BookDetailNetworkResult(book,emptyList(),emptyList())
        override suspend fun files(book:BookDetailBook,source:BookDetailSource?)=emptyList<BookDetailWebFile>()
        override suspend fun cover(book:BookDetailBook):BookDetailBook?=null
    }
}
