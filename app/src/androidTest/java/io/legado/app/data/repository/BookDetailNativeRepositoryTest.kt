package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailNativeRepositoryTest {
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private val texts=BookDetailNativeTexts("Source comment","Book comment","Update task")
    @Test fun mainCallerPreparesActualRoomHighlightsAndDetachedFullBookSourceOnIo()=runBlocking {
        val highlight=BookHighlight(time=1,bookUrl="book",note="Note")
        withContext(Dispatchers.IO){database.bookHighlightDao.insert(highlight)}
        val book=Book(bookUrl="book",name="Name",intro="large".repeat(200_000));val source=BookSource(bookSourceUrl="source",bookSourceName="Source")
        withContext(Dispatchers.IO){database.bookDao.insert(book)}
        val stale=book.copy(intro="Stale").apply{infoHtml="cached";tocHtml="cached toc";downloadUrls=listOf("file")}
        val effect=BookDetailNativeEffect("sync",BookDetailNativeKind.ReaderSync,BookDetailBook.from(stale),BookDetailSource.from(source),expectedBookUrl="old")
        val prepared=withContext(Dispatchers.Main){RoomBookDetailNativeRepository(database).prepare(effect,texts)}
        assertEquals(book.intro,prepared.book!!.intro);assertEquals("cached",prepared.book.infoHtml);assertNotSame(book,prepared.book);assertNotSame(source,prepared.source)
        assertEquals(listOf(highlight),prepared.highlights);prepared.highlights.single().note="Changed locally"
        assertEquals("Note",withContext(Dispatchers.IO){database.bookHighlightDao.getByBook("book").single().note})
    }
    @Test fun changedSourceUsesFreshRoomMetadataWithoutAttachingOldSourceEngineHtmlOrDownloadUrls()=runBlocking {
        val old=Book(bookUrl="book",name="Old",origin="old-source").apply{infoHtml="old info";tocHtml="old toc";downloadUrls=listOf("old file")}
        val effect=BookDetailNativeEffect("sync",BookDetailNativeKind.ReaderSync,BookDetailBook.from(old))
        withContext(Dispatchers.IO){database.bookDao.insert(old.copy(name="New",origin="new-source",customCoverUrl="new cover"))}
        val prepared=withContext(Dispatchers.Main){RoomBookDetailNativeRepository(database).prepare(effect,texts)}
        assertEquals("New",prepared.book!!.name);assertEquals("new-source",prepared.book.origin);assertEquals("new cover",prepared.book.customCoverUrl)
        assertNull(prepared.book.infoHtml);assertNull(prepared.book.tocHtml);assertNull(prepared.book.downloadUrls)
    }
    @Test fun deletedRoomOwnerPreparesNoReaderBookAndNoHighlightsRatherThanPublishingTheOldReceipt()=runBlocking {
        val book=Book(bookUrl="removed",name="Old")
        val effect=BookDetailNativeEffect("sync",BookDetailNativeKind.ReaderSync,BookDetailBook.from(book))
        withContext(Dispatchers.IO){database.bookHighlightDao.insert(BookHighlight(time=4,bookUrl="removed",note="Orphan"))}
        val prepared=withContext(Dispatchers.Main){RoomBookDetailNativeRepository(database).prepare(effect,texts)}
        assertNull(prepared.book);assertTrue(prepared.highlights.isEmpty())
    }
    @Test fun shareAndCopyTocPrepareExactLegacyNativePayloadWithoutSerializingOnMain()=runBlocking {
        val book=Book(bookUrl="https://book",name="Name",tocUrl="https://toc").apply{putCustomVariable("custom")}
        val snapshot=BookDetailBook.from(book);val repository=RoomBookDetailNativeRepository(database)
        val share=withContext(Dispatchers.Main){repository.prepare(BookDetailNativeEffect("share",BookDetailNativeKind.Share,snapshot),texts)}
        assertEquals("${book.bookUrl}#${GSON.toJson(snapshot.materializeBook())}",share.text)
        val toc=withContext(Dispatchers.Main){repository.prepare(BookDetailNativeEffect("toc",BookDetailNativeKind.CopyTocUrl,snapshot),texts)}
        assertEquals(book.tocUrl,toc.text);assertTrue(toc.highlights.isEmpty())
    }
}
