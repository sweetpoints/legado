package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.Book
import io.legado.app.model.download.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ChapterDownloadSessionRepositoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory:File
    @Before fun before(){directory=File(context.cacheDir,"chapter-download-${UUID.randomUUID()}")}
    @After fun after(){directory.deleteRecursively()}
    @Test fun largeBookRestoresDetachedNativePayloadAndPendingRangeWithoutBundleFields()=runBlocking {
        val repository=FileChapterDownloadSessionRepository(context,directory)
        val book=Book(bookUrl="url".repeat(100000),name="Exact name",variable="V".repeat(2000000),durChapterIndex=17)
        book.infoHtml="Transient HTML"
        val ticket=withContext(Dispatchers.Main){repository.create(book,ChapterDownloadMode.Book,18,200)}
        val initial=repository.read(ticket)!!
        val pending=ChapterDownloadSelection("native-ticket",ChapterDownloadRange(3,99))
        repository.write(ticket,initial.copy(revision=7,pending=pending))
        val restored=FileChapterDownloadSessionRepository(context,directory).read(ticket)!!
        assertEquals(pending,restored.pending);assertEquals(18,restored.initialChapter);assertEquals(200,restored.chapterCount)
        val native=withContext(Dispatchers.Main){repository.book(restored)}
        assertEquals(book.bookUrl,native.bookUrl);assertEquals(book.variable,native.variable);assertEquals("Transient HTML",native.infoHtml)
        native.name="Changed";assertEquals("Exact name",repository.book(restored).name)
    }
    @Test fun staleRevisionCannotUndoCompletionAndReleaseFencesLateWritesAndAtomicSidecars()=runBlocking {
        val repository=FileChapterDownloadSessionRepository(context,directory)
        val ticket=repository.create(Book(bookUrl="owned"),ChapterDownloadMode.Audio,2,9)
        val sibling=repository.create(Book(bookUrl="other"),ChapterDownloadMode.Book,1,4)
        val initial=repository.read(ticket)!!
        repository.write(ticket,initial.copy(revision=9,completed=true));repository.write(ticket,initial.copy(revision=8))
        assertTrue(repository.read(ticket)!!.completed)
        File(directory,"$ticket.json.bak").writeText(File(directory,"$ticket.json").readText())
        File(directory,"$ticket.json.new").writeText("Partial write")
        repository.release(ticket)
        assertNull(repository.read(ticket));assertTrue(runCatching{repository.write(ticket,initial.copy(revision=100))}.isFailure)
        assertTrue(listOf("json","json.bak","json.new").none{File(directory,"$ticket.$it").exists()})
        assertNotNull(repository.read(sibling));assertTrue(runCatching{repository.read("../escape")}.isFailure)
    }
    @Test fun cancellationReturningFromAcceptedIoCreationCleansUnclaimedRequestOnly()=runBlocking {
        val plain=FileChapterDownloadSessionRepository(context,directory)
        val sibling=plain.create(Book(bookUrl="other"),ChapterDownloadMode.Book,1,4)
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val repository=FileChapterDownloadSessionRepository(context,directory){entered.complete(Unit);gate.await()}
        var delivered=false
        val job=launch(Dispatchers.Main){repository.create(Book(bookUrl="cancelled"),ChapterDownloadMode.Book,1,4);delivered=true}
        entered.await();job.cancel();gate.complete(Unit);job.join()
        assertFalse(delivered);assertTrue(job.isCancelled)
        assertEquals(listOf("$sibling.json"),directory.listFiles()!!.filter{it.name.endsWith(".json")}.map{it.name})
        assertNotNull(plain.read(sibling))
    }
}
