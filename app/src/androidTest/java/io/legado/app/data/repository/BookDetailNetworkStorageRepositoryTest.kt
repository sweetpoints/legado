package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookMemo
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailNetworkStorageRepositoryTest {
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private fun book(url:String="old",name:String="Name",origin:String="source")=
        Book(bookUrl=url,name=name,author="Author",origin=origin,customCoverUrl="custom",persistedCoverUrl="cached",durChapterPos=12)
    private fun data(book:Book,shelf:Boolean=true)=BookDetailData(BookDetailBook.from(book),null,emptyList(),emptyList(),emptyList(),shelf)
    private fun result(book:Book,chapters:List<BookChapter> = listOf(BookChapter(bookUrl=book.bookUrl,url="chapter",title="Chapter")))=
        BookDetailNetworkResult(BookDetailBook.from(book),chapters.map(BookDetailChapter::from),emptyList())
    private suspend fun insert(vararg books:Book)=withContext(Dispatchers.IO){database.bookDao.insert(*books)}
    private suspend fun read(url:String)=withContext(Dispatchers.IO){database.bookDao.getBook(url)}
    private suspend fun chapters(url:String)=withContext(Dispatchers.IO){database.bookChapterDao.getChapterList(url)}
    private fun repo(move:(Book,Book)->Unit={_,_->},snapshot:(Book)->Unit={})=RoomBookDetailNetworkStorageRepository(database,move,snapshot)
    @Test fun preUpdateUrlChangeMovesMemoChaptersAndCacheAfterAtomicCommitWithLatestProgress()=runBlocking {
        val old=book();insert(old)
        withContext(Dispatchers.IO){database.bookChapterDao.insert(BookChapter(bookUrl="old",url="oldchapter",title="Old"));database.bookMemoDao.insert(BookMemo("old","memo",10))}
        insert(old.copy(durChapterPos=92,group=3,customCoverUrl="external custom",persistedCoverUrl="external cached"))
        var moved=0
        val repository=repo(move={before,after->
            assertEquals("old",before.bookUrl);assertEquals("new",after.bookUrl)
            assertNull(database.bookDao.getBook("old"));assertEquals(92,database.bookDao.getBook("new")!!.durChapterPos)
            assertEquals("chapter",database.bookChapterDao.getChapterList("new").single().url);moved++
        })
        val refreshed=repository.commit(data(old),result(old.copy(bookUrl="new",tocUrl="new/toc",group=5)),false){}
        assertEquals("new",refreshed.book.bookUrl);assertEquals(3L,read("new")!!.group)
        assertEquals("external custom",read("new")!!.customCoverUrl);assertEquals("external cached",read("new")!!.persistedCoverUrl)
        assertTrue(chapters("old").isEmpty());assertEquals(1,moved)
        assertEquals("memo",withContext(Dispatchers.IO){database.bookMemoDao.get("new")!!.content})
        assertNull(withContext(Dispatchers.IO){database.bookMemoDao.get("old")})
    }
    @Test fun renamedSearchRecognizesOnlySameSourceActualShelfAndCarriesItsProgress()=runBlocking {
        val owner=book(name="Parsed");insert(owner.copy(durChapterPos=80,group=7))
        val search=book("search",name="Search")
        val same=repo().commit(data(search,false),result(search.copy(bookUrl="same",name="Parsed")),false){}
        assertTrue(same.inBookshelf);assertEquals(80,read("same")!!.durChapterPos);assertEquals(7L,read("same")!!.group);assertNull(read("old"))
        val different=book("other",name="Search",origin="other-source")
        val preview=repo().commit(data(different,false),result(different.copy(name="Parsed")),false){error("Private preview must not journal a Room write")}
        assertFalse(preview.inBookshelf);assertNull(read("other"));assertNotNull(read("same"))
    }
    @Test fun journalFailureDoesNotChangeRoomAndRecoveryRefusesDeletedOrChangedBaseline()=runBlocking {
        val old=book();insert(old);var plan:BookDetailNetworkWritePlan?=null
        assertTrue(runCatching{repo().commit(data(old),result(old.copy(bookUrl="new")),false){plan=it;error("journal failed")}}.isFailure)
        assertNotNull(read("old"));assertNull(read("new"))
        insert(old.copy(durChapterPos=999));assertTrue(runCatching{repo().recover(plan!!)}.exceptionOrNull() is BookDetailConflict)
        assertEquals(999,read("old")!!.durChapterPos)
        withContext(Dispatchers.IO){database.bookDao.delete(read("old")!!)}
        assertTrue(runCatching{repo().recover(plan!!)}.exceptionOrNull() is BookDetailMissing);assertNull(read("new"))
    }
    @Test fun committedCacheFailureReplaysCacheOnlyAndLaterExternalMutationIsNeverOverwritten()=runBlocking {
        val old=book();insert(old);var plan:BookDetailNetworkWritePlan?=null;var moves=0
        val repository=repo(move={_,_->moves++;if(moves==1)error("cache failed after commit")})
        assertTrue(runCatching{repository.commit(data(old),result(old.copy(bookUrl="new")),false){plan=it}}.isFailure)
        assertNull(read("old"));assertNotNull(read("new"));repository.recover(plan!!);assertEquals(2,moves)
        assertEquals(plan!!.targetJson,GSON.toJson(read("new")!!.copy()))
        insert(read("new")!!.copy(durChapterPos=77))
        assertTrue(runCatching{repository.recover(plan!!)}.isFailure);assertEquals(77,read("new")!!.durChapterPos);assertEquals(2,moves)
    }
    @Test fun sourceChangeUsesExistingChapterProgressMigrationAndSnapshotsOnceWithoutMovingCache()=runBlocking {
        val old=book();insert(old);var snapshots=0;var moves=0;var plan:BookDetailNetworkWritePlan?=null
        val repository=repo(move={_,_->moves++},snapshot={assertEquals("old",it.bookUrl);snapshots++})
        val updated=repository.commit(data(old),result(old.copy(bookUrl="new",origin="other-source")),true){plan=it}
        assertTrue(updated.inBookshelf);assertEquals("other-source",read("new")!!.origin);assertEquals(12,read("new")!!.durChapterPos)
        repository.recover(plan!!);assertEquals(1,snapshots);assertEquals(0,moves)
    }
    @Test fun downloadableFileRefreshRetainsExistingChapterRowsAndRecoverChecksTheirFingerprint()=runBlocking {
        val old=book().copy(type=BookType.text or BookType.webFile);insert(old)
        val chapter=BookChapter(bookUrl="old",url="cached",title="Cached")
        withContext(Dispatchers.IO){database.bookChapterDao.insert(chapter)}
        var plan:BookDetailNetworkWritePlan?=null
        repo().commit(data(old),result(old.copy(latestChapterTitle="Downloaded"),emptyList()),false){plan=it}
        assertEquals("cached",chapters("old").single().url);repo().recover(plan!!)
        withContext(Dispatchers.IO){database.bookChapterDao.insert(chapter.copy(variable="external"))}
        assertTrue(runCatching{repo().recover(plan!!)}.exceptionOrNull() is BookDetailConflict)
        assertEquals("external",chapters("old").single().variable)
    }
    @Test fun refreshNeverResurrectsDeletedShelfOrOverwritesAnotherBooksTargetUrl()=runBlocking {
        val old=book();insert(old,book("occupied",name="Other"))
        assertTrue(runCatching{repo().commit(data(old),result(old.copy(bookUrl="occupied")),false){}}.exceptionOrNull() is BookDetailConflict)
        assertEquals("Other",read("occupied")!!.name);assertNotNull(read("old"))
        withContext(Dispatchers.IO){database.bookDao.delete(old)}
        assertTrue(runCatching{repo().commit(data(old),result(old.copy(bookUrl="new")),false){}}.exceptionOrNull() is BookDetailMissing)
        assertNull(read("new"))
    }
}
