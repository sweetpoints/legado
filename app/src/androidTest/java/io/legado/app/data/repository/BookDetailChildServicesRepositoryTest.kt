package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailChildServicesRepositoryTest {
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private fun book()=Book(bookUrl="temporary",name="Name",author="Author",origin="source")
    @Test fun mainCallerDiscardsUnchangedTemporaryRowAndCascadesItsChaptersOnIo()=runBlocking {
        val before=book()
        withContext(Dispatchers.IO){database.bookDao.insert(before);database.bookChapterDao.insert(BookChapter(bookUrl=before.bookUrl,url="chapter",title="Chapter"))}
        assertTrue(withContext(Dispatchers.Main){RoomBookDetailChildServicesRepository(database).discardTemporary(BookDetailBook.from(before))})
        withContext(Dispatchers.IO){assertNull(database.bookDao.getBook(before.bookUrl));assertTrue(database.bookChapterDao.getChapterList(before.bookUrl).isEmpty())}
    }
    @Test fun cancelingOldTocCannotDeleteAReaderOrGroupOrCoverChangeMadeByAnotherOwner()=runBlocking {
        val before=book()
        withContext(Dispatchers.IO){database.bookDao.insert(before);database.bookDao.update(before.copy(durChapterIndex=5,durChapterPos=44,group=8,customCoverUrl="new"))}
        assertFalse(withContext(Dispatchers.Main){RoomBookDetailChildServicesRepository(database).discardTemporary(BookDetailBook.from(before))})
        val latest=withContext(Dispatchers.IO){database.bookDao.getBook(before.bookUrl)!!}
        assertEquals(5,latest.durChapterIndex);assertEquals(44,latest.durChapterPos);assertEquals(8L,latest.group);assertEquals("new",latest.customCoverUrl)
    }
    @Test fun alreadyRemovedTemporaryRowIsAnIdempotentCancellation()=runBlocking {
        assertTrue(RoomBookDetailChildServicesRepository(database).discardTemporary(BookDetailBook.from(book())))
        assertTrue(RoomBookDetailChildServicesRepository(database).discardTemporary(BookDetailBook.from(book())))
    }
}
