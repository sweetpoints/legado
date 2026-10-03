package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.lifecycle.SavedStateHandle
import io.legado.app.ui.book.info.edit.BookMetadataEditorViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookMetadataEditorSessionRepositoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>();private lateinit var directory:File;private lateinit var database:AppDatabase
    @Before fun before(){directory=File(context.cacheDir,"book-editor-session-test-"+UUID.randomUUID()).apply{mkdirs()};database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()}
    @After fun after(){database.close();directory.deleteRecursively()}
    private fun books(move:(Book,Book)->Unit={_,_->})=RoomBookMetadataEditorRepository(database,move)
    private fun sessions(books:BookMetadataEditorRepository=books(),beforeWrite:(BookMetadataDraft)->Unit={})=FileBookMetadataEditorSessionRepository(context,books,directory,beforeWrite)
    private suspend fun initial(repo:BookMetadataEditorRepository):BookMetadataDraft {
        withContext(Dispatchers.IO){database.bookDao.insert(Book(bookUrl="book",name="Original",author="Author",coverUrl="source"))}
        val book=repo.load("book")!!
        return BookMetadataDraft("book",book,BookMetadataInput("book","Edited","Author",0,"source","large intro".repeat(100000),setOf(BookMetadataField.Name,BookMetadataField.Intro)),book.preview(),revision=1)
    }
    @Test fun realAtomicFileRestoresMillionCharacterDraftAndOneShotCompletion()=runBlocking {
        val ticket=UUID.randomUUID().toString();val repo=books();val draft=initial(repo);sessions(repo).write(ticket,draft)
        assertEquals(draft,sessions(repo).read(ticket));val completed=sessions(repo).save(ticket,draft)
        assertNotNull(completed.completion);assertEquals("Edited",repo.load("book")!!.name)
        assertEquals(completed,sessions(repo).save(ticket,draft));assertEquals(completed,sessions(repo).read(ticket))
    }
    @Test fun firstJournalFailureLeavesRoomUntouchedAndOriginalInputAvailableForRetry()=runBlocking {
        val ticket=UUID.randomUUID().toString();val repo=books();val draft=initial(repo);sessions(repo).write(ticket,draft)
        val broken=sessions(repo){if(it.pendingSave!=null)error("journal failed")}
        assertTrue(runCatching{broken.save(ticket,draft)}.isFailure);assertEquals("Original",repo.load("book")!!.name);assertNull(sessions(repo).read(ticket)!!.pendingSave)
        val completed=sessions(repo).save(ticket,draft);assertEquals("Edited",completed.completion!!.book.name)
    }
    @Test fun roomCommitThenFinalReceiptFailureRecoversOriginalPlanWithoutOverwritingExternalChanges()=runBlocking {
        val ticket=UUID.randomUUID().toString();val repo=books();val draft=initial(repo);sessions(repo).write(ticket,draft)
        val broken=sessions(repo){if(it.completion!=null)error("final receipt failed")}
        assertTrue(runCatching{broken.save(ticket,draft)}.isFailure);assertEquals("Edited",repo.load("book")!!.name)
        val pending=sessions(repo).read(ticket)!!;assertNotNull(pending.pendingSave)
        val completed=sessions(repo).save(ticket,pending);assertNotNull(completed.completion);assertNull(completed.pendingSave)
        // A separate saved session must fail instead of rewriting a concurrently edited full row.
        val second=UUID.randomUUID().toString();sessions(repo).write(second,pending)
        withContext(Dispatchers.IO){database.bookDao.getBook("book")!!.let{database.bookDao.update(it.copy(author="External",durChapterPos=999))}}
        assertTrue(runCatching{sessions(repo).save(second,pending)}.exceptionOrNull() is BookMetadataConflict)
        assertEquals("External",repo.load("book")!!.author);assertNotNull(sessions(repo).read(second)!!.pendingSave)
    }
    @Test fun olderOwnerCannotResurrectCompletionAndReleaseFencesLateWriterIncludingBackupMarker()=runBlocking {
        val ticket=UUID.randomUUID().toString();val repo=books();val draft=initial(repo);val first=sessions(repo);val second=sessions(repo)
        first.write(ticket,draft);val completed=second.save(ticket,draft);val claimed=completed.copy(completion=null,finished=true,revision=completed.revision+1)
        second.write(ticket,claimed);first.write(ticket,completed);assertTrue(first.read(ticket)!!.finished);assertNull(first.read(ticket)!!.completion)
        second.release(ticket);assertNull(first.read(ticket));assertTrue(runCatching{first.write(ticket,claimed.copy(revision=999))}.isFailure)
        assertTrue(File(directory,"$ticket.closed").renameTo(File(directory,"$ticket.closed.bak")))
        assertTrue(runCatching{sessions(repo).save(ticket,draft)}.isFailure)
        assertTrue(listOf("", ".bak", ".new").none{File(directory,"$ticket.json$it").exists()})
    }    @Test fun actualIoDispatcherCancellationRollsBackCompletionBeforeAnyNativeDelivery()=runBlocking {
        val repo=books();val draft=initial(repo);val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
        val store=sessions(repo){if(it.finished && first.compareAndSet(true,false)){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        lateinit var model:BookMetadataEditorViewModel
        withContext(Dispatchers.Main){model=BookMetadataEditorViewModel(SavedStateHandle(),repo,store,object:BookMetadataCoverImportRepository{override suspend fun install(uri:String)=uri},draft.bookUrl)}
        var work:Job?=null
        try {
            model.state.first{it.loaded};withContext(Dispatchers.Main){model.text(BookMetadataField.Name,"Edited");model.save()}
            val effect=model.state.first{it.draft?.completion!=null}.draft!!.completion!!
            work=launch(Dispatchers.Main){model.consumeCompletion(effect.token){true};error("Canceled native callback")}
            assertTrue(entered.await(5,TimeUnit.SECONDS));work.cancel();release.countDown();work.join();assertTrue(work.isCancelled)
            assertFalse(store.read(model.ticket)!!.finished);assertEquals(effect,store.read(model.ticket)!!.completion)
            withContext(Dispatchers.Main){assertEquals(effect,model.consumeCompletion(effect.token){true});assertNull(model.consumeCompletion(effect.token){true})}
            assertEquals("Edited",repo.load("book")!!.name)
        } finally {release.countDown();work?.cancelAndJoin();withContext(Dispatchers.Main){model.stop()}}
    }

}
