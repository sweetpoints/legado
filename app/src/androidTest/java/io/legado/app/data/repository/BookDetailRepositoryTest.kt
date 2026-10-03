package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailRepositoryTest {
    private lateinit var database:AppDatabase
    @Before fun before(){database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()}
    @After fun after(){database.close()}
    private fun repo(size:(Book)->Long={0})=RoomBookDetailRepository(database,size)
    private suspend fun insert(vararg books:Book)=withContext(Dispatchers.IO){database.bookDao.insert(*books)}
    @Test fun nameAuthorWinsOverUrlAndUrlWinsOverSearchWhileMissingDoesNotWriteOrCreateBook()=runBlocking {
        val name=Book(bookUrl="by-name",name="Name",author="Author");val url=Book(bookUrl="by-url",name="Other",author="Author");insert(name,url)
        withContext(Dispatchers.IO){database.searchBookDao.insert(SearchBook(bookUrl="search",name="Search",author="Author",origin="source"))}
        val repo=repo();assertEquals("by-name",repo.resolve(BookDetailIdentity("Name","Author","by-url"))!!.book.bookUrl)
        assertEquals("by-url",repo.resolve(BookDetailIdentity("Absent","Author","by-url"))!!.book.bookUrl)
        assertEquals("search",repo.resolve(BookDetailIdentity("Absent","Author","search"))!!.book.bookUrl)
        assertNull(repo.resolve(BookDetailIdentity("Absent","Author","missing")));assertNull(repo.reload("search"))
    }
    @Test fun nameFallbackUsesSourceBackedSearchOrderAndSearchPreviewStaysOutsideBookshelf()=runBlocking {
        withContext(Dispatchers.IO){
            database.bookSourceDao.insert(BookSource(bookSourceUrl="source",bookSourceName="Source"))
            database.searchBookDao.insert(SearchBook(bookUrl="orphan",name="Name",author="Author",origin="removed-source",originOrder=0),
                SearchBook(bookUrl="second",name="Name",author="Author",origin="source",originOrder=2),SearchBook(bookUrl="first",name="Name",author="Author",origin="source",originOrder=1))
        }
        val result=repo().resolve(BookDetailIdentity("Name","Author"))!!;assertEquals("first",result.book.bookUrl);assertFalse(result.inBookshelf);assertEquals("source",result.source!!.url);assertNull(repo().reload("first"))
    }
    @Test fun roomSourceGroupsAndOrderedChaptersAreFreshDetachedAndTemporaryNotShelfRemainsFalse()=runBlocking {
        val book=Book(bookUrl="book",name="Name",author="Author",origin="source",group=3,type=BookType.text or BookType.notShelf);insert(book)
        withContext(Dispatchers.IO){
            database.bookSourceDao.insert(BookSource(bookSourceUrl="source",bookSourceName="Source",customButton=true,loginUrl="https://login"))
            database.bookGroupDao.insert(BookGroup(groupId=1,groupName="First"),BookGroup(groupId=2,groupName="Second"))
            database.bookChapterDao.insert(BookChapter(bookUrl="book",url="chapter2",index=2,title="Second"),BookChapter(bookUrl="book",url="chapter1",index=1,title="First"))
        }
        val repo=repo();val first=repo.reload("book")!!;assertFalse(first.inBookshelf);assertEquals(setOf("First","Second"),first.groupNames.toSet());assertEquals(listOf(1,2),first.chapters.map{it.index});assertTrue(first.source!!.hasLogin);assertTrue(first.source.customButton)
        insert(book.copy(name="Changed",durChapterPos=77,type=BookType.text));val latest=repo.reload("book")!!;assertTrue(latest.inBookshelf);assertEquals("Changed",latest.book.name);assertEquals(77,latest.book.chapterPos);assertEquals("Name",first.book.name)
    }
    @Test fun localFileMetadataUsesIoSizeSkipsSourceAndKeepsOriginalKindListWithoutSizeMutation()=runBlocking {
        val book=Book(bookUrl="file",name="Local",originName="local.txt",type=BookType.text or BookType.local,kind="One,Two",wordCount="20 words");insert(book)
        var calls=0;val result=withContext(Dispatchers.Main){repo{calls++;assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());1024}.reload("file")!!}
        assertEquals(1,calls);assertNull(result.source);assertEquals(listOf("20 words","One","Two"),result.book.kinds);assertEquals(4,result.kinds.size);assertTrue(result.book.isLocalTxt)
        val failure=repo{error("Unreachable permission")}.reload("file")!!;assertEquals(result.book.kinds,failure.kinds)
    }
    @Test fun reloadDeletedBookDoesNotFallBackToStaleSearchOrResurrectRoomOwner()=runBlocking {
        val book=Book(bookUrl="book",name="Name",author="Author");insert(book);val repo=repo();assertNotNull(repo.reload("book"))
        withContext(Dispatchers.IO){database.bookDao.delete(book);database.searchBookDao.insert(SearchBook(bookUrl="book",name="Name",author="Author",origin="source"))}
        assertNull(repo.reload("book"));assertNull(withContext(Dispatchers.IO){database.bookDao.getBook("book")})
    }
    @Test fun describePrivateSearchSnapshotPreservesFullMetadataAndDoesNotInstallItIntoRoom()=runBlocking {
        val book=Book(bookUrl="private-search",name="Name",origin="source",intro="<useweb>HTML</useweb>",variable="private state")
        val result=repo().describe(BookDetailBook.from(book),false);assertEquals(book.intro,result.book.intro);assertEquals("private state",result.book.materializeBook().variable)
        assertFalse(result.inBookshelf);assertNull(repo().reload("private-search"))
    }
}
