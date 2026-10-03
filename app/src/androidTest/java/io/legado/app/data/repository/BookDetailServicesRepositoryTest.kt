package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.lib.webdav.WebDavException
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailServicesRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun before() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun after() {
        database.close()
    }

    private fun book() =
        Book(
            bookUrl = "book",
            name = "Name",
            author = "Author",
            origin = "local",
            type = BookType.local or BookType.text,
        )

    private suspend fun insert(book: Book) =
        withContext(Dispatchers.IO) { database.bookDao.insert(book) }

    private suspend fun read() = withContext(Dispatchers.IO) { database.bookDao.getBook("book") }

    private fun repo(engine: Engine, snapshot: (Book) -> Unit = {}) =
        AppBookDetailServicesRepository(database, engine, snapshot, { 1234L })

    @Test
    fun mainCallerUploadWritesOnlyOriginAndTimestampAfterFreshConcurrentMetadataIsRead() =
        runBlocking {
            val initial = book()
            insert(initial)
            val engine = Engine()
            engine.uploadGate = CompletableDeferred()
            val repository = repo(engine)
            val upload =
                async(Dispatchers.Main) { repository.upload(BookDetailBook.from(initial), false) }
            try {
                engine.uploadEntered.await()
                val latest =
                    initial.copy(
                        durChapterPos = 99,
                        group = 17,
                        customCoverUrl = "custom",
                        persistedCoverUrl = "cached",
                        variable = "{\"new\":\"variable\"}",
                        customIntro = "external intro",
                    )
                latest.setSplitLongChapter(false)
                insert(latest)
                engine.uploadGate!!.complete(Unit)
                val result = upload.await().materializeBook()
                val persisted = read()!!
                assertEquals("remote-origin", persisted.origin)
                assertEquals(1234L, persisted.lastCheckTime)
                assertEquals(99, persisted.durChapterPos)
                assertEquals(17L, persisted.group)
                assertEquals("custom", persisted.customCoverUrl)
                assertEquals("cached", persisted.persistedCoverUrl)
                assertEquals("external intro", persisted.customIntro)
                assertEquals("variable", persisted.getVariable("new"))
                assertFalse(persisted.getSplitLongChapter())
                assertEquals(persisted.variable, result.variable)
                assertEquals(listOf("upload:false"), engine.events)
            } finally {
                engine.uploadGate!!.complete(Unit)
                upload.cancelAndJoin()
            }
        }

    @Test
    fun deletedDuringUploadIsNeverRecreatedAndConcurrentOriginReplacementIsNotOverwritten() =
        runBlocking {
            val initial = book()
            insert(initial)
            val engine = Engine()
            engine.uploadGate = CompletableDeferred()
            val repository = repo(engine)
            val upload = async {
                runCatching { repository.upload(BookDetailBook.from(initial), true) }
            }
            try {
                engine.uploadEntered.await()
                withContext(Dispatchers.IO) { database.bookDao.delete(initial) }
                engine.uploadGate!!.complete(Unit)
                assertTrue(upload.await().exceptionOrNull() is BookDetailMissing)
                assertNull(read())
            } finally {
                engine.uploadGate!!.complete(Unit)
                upload.cancelAndJoin()
            }
            insert(initial)
            val second = Engine()
            second.uploadGate = CompletableDeferred()
            val conflict = async {
                runCatching { repo(second).upload(BookDetailBook.from(initial), true) }
            }
            try {
                second.uploadEntered.await()
                insert(initial.copy(origin = "external-origin"))
                second.uploadGate!!.complete(Unit)
                assertTrue(conflict.await().exceptionOrNull() is BookDetailConflict)
                assertEquals("external-origin", read()!!.origin)
            } finally {
                second.uploadGate!!.complete(Unit)
                conflict.cancelAndJoin()
            }
        }

    @Test
    fun conditionalUploadConflictIsTypedOnlyFor409And412AndLeavesRoomUntouched() = runBlocking {
        val initial = book()
        insert(initial)
        for (status in listOf(409, 412, 401)) {
            val engine = Engine()
            engine.uploadFailure = WebDavException("fixture failure", status)
            val result = runCatching { repo(engine).upload(BookDetailBook.from(initial), false) }
            if (status == 401) assertTrue(result.exceptionOrNull() is WebDavException)
            else assertTrue(result.exceptionOrNull() is BookDetailUploadConflict)
            assertEquals("local", read()!!.origin)
        }
        val engine = Engine()
        engine.uploadFailure = WebDavException("overwrite failed", 412)
        assertTrue(
            runCatching { repo(engine).upload(BookDetailBook.from(initial), true) }
                .exceptionOrNull() is WebDavException
        )
    }

    @Test
    fun failedRemoteDeleteDoesNotDeleteLocalRowsChaptersOrOriginalFile() = runBlocking {
        val initial = book()
        insert(initial)
        withContext(Dispatchers.IO) {
            database.bookChapterDao.insert(BookChapter(bookUrl = "book", url = "chapter"))
        }
        val engine = Engine()
        engine.deletedRemote = false
        var snapshots = 0
        assertTrue(
            runCatching {
                repo(engine) { snapshots++ }.delete(BookDetailBook.from(initial), true, true)
            }
                .exceptionOrNull() is BookDetailRemoteDeleteFailed
        )
        assertNotNull(read())
        assertEquals(
            1,
            withContext(Dispatchers.IO) { database.bookChapterDao.getChapterList("book").size },
        )
        assertEquals(listOf("remote-delete"), engine.events)
        assertEquals(0, snapshots)
    }

    @Test
    fun successfulRemoteThenLocalDeleteSnapshotsLatestBookAndCascadesRoomChaptersBeforeDeletingOriginal() =
        runBlocking {
            val initial = book()
            insert(initial.copy(durChapterPos = 99))
            withContext(Dispatchers.IO) {
                database.bookChapterDao.insert(BookChapter(bookUrl = "book", url = "chapter"))
            }
            val engine = Engine()
            var snapshots = 0
            val deleted =
                withContext(Dispatchers.Main) {
                    repo(engine) {
                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                            assertEquals(99, it.durChapterPos)
                            snapshots++
                        }
                        .delete(BookDetailBook.from(initial), true, true)
                }
            assertEquals(99, deleted.chapterPos)
            assertNull(read())
            assertTrue(
                withContext(Dispatchers.IO) {
                    database.bookChapterDao.getChapterList("book").isEmpty()
                }
            )
            assertEquals(listOf("remote-delete", "local-delete:true"), engine.events)
            assertEquals(1, snapshots)
        }

    @Test
    fun refreshFallbackStillReturnsLocalInputForFollowingInfoStageAndDoesNotMutateOriginalSnapshot() =
        runBlocking {
            val initial = book()
            val original = BookDetailBook.from(initial)
            val engine = Engine()
            engine.refreshFailure = ObjectNotFoundException("missing remote")
            val result = withContext(Dispatchers.Main) { repo(engine).refreshInput(original, null) }
            assertEquals(BookType.localTag, result.book.origin)
            assertEquals("", result.book.materializeBook().tocUrl)
            assertNull(result.warning)
            assertEquals("local", original.origin)
            engine.refreshFailure = IllegalStateException("network failure")
            assertEquals("network failure", repo(engine).refreshInput(original, null).warning)
        }

    @Test
    fun fileDownloadArchiveAndClearCacheDelegateExistingEngineOnIoAndReturnDetachedSnapshots() =
        runBlocking {
            val initial = BookDetailBook.from(book())
            val engine = Engine()
            val repository = repo(engine)
            val source =
                BookDetailSource.from(
                    BookSource(bookSourceUrl = "source", bookSourceName = "Source")
                )
            val downloaded =
                withContext(Dispatchers.Main) {
                    repository.download(
                        initial,
                        source,
                        BookDetailWebFile("raw/options", "book.epub"),
                    )
                }
            assertEquals("local-import", downloaded.book!!.bookUrl)
            assertEquals(listOf("download:raw/options:epub"), engine.events)
            assertEquals(listOf("book.txt"), repository.archiveEntries("archive-uri"))
            val imported = repository.importArchive(initial, "archive-uri", "entry.txt")
            engine.imported!!.name = "late mutation"
            assertEquals("Imported", imported.name)
            repository.clearCache(initial)
            assertEquals(
                listOf(
                    "download:raw/options:epub",
                    "entries:archive-uri",
                    "archive:archive-uri:entry.txt",
                    "cache",
                ),
                engine.events,
            )
            assertTrue(
                runCatching {
                    repository.download(initial, null, BookDetailWebFile("raw", "book.txt"))
                }
                    .exceptionOrNull() is BookDetailNoSource
            )
        }

    private class Engine : BookDetailServiceEngine {
        val events = mutableListOf<String>()
        val uploadEntered = CompletableDeferred<Unit>()
        var uploadGate: CompletableDeferred<Unit>? = null
        var uploadFailure: Throwable? = null
        var refreshFailure: Throwable? = null
        var deletedRemote = true
        var imported: Book? = null

        private fun io() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        }

        override suspend fun refresh(book: Book, source: BookSource?) {
            io()
            book.tocUrl = ""
            refreshFailure?.let { throw it }
        }

        override suspend fun remoteExists(book: Book): Boolean {
            io()
            return true
        }

        override suspend fun upload(book: Book, overwrite: Boolean) {
            io()
            events += "upload:$overwrite"
            uploadEntered.complete(Unit)
            uploadGate?.await()
            uploadFailure?.let { throw it }
            book.origin = "remote-origin"
        }

        override suspend fun deleteRemote(book: Book): Boolean {
            io()
            events += "remote-delete"
            return deletedRemote
        }

        override suspend fun deleteLocal(book: Book, deleteOriginal: Boolean) {
            io()
            events += "local-delete:$deleteOriginal"
        }

        override suspend fun clearCache(book: Book) {
            io()
            events += "cache"
        }

        override suspend fun download(
            book: Book,
            source: BookSource,
            file: BookDetailWebFile,
        ): BookDetailDownload {
            io()
            events += "download:${file.url}:${file.suffix}"
            return BookDetailDownload(BookDetailBook.from(book.copy(bookUrl = "local-import")))
        }

        override suspend fun archiveEntries(uri: String): List<String> {
            io()
            events += "entries:$uri"
            return listOf("book.txt")
        }

        override suspend fun importArchive(book: Book, uri: String, entry: String): Book {
            io()
            events += "archive:$uri:$entry"
            return book.copy(name = "Imported").also { imported = it }
        }
    }
}
