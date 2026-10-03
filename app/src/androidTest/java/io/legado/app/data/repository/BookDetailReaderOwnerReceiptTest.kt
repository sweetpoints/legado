package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailReaderOwnerReceiptTest {
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private lateinit var context: Context

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "book-detail-reader-receipt-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repository() =
        FileBookDetailSessionRepository(
            context,
            RoomBookDetailStorageRepository(database),
            RoomBookDetailRepository(database),
            RoomBookDetailNetworkStorageRepository(database, { _, _ -> }, {}),
            directory,
        )

    private fun record(book: Book) =
        BookDetailSession(
            BookDetailIdentity(bookUrl = book.bookUrl),
            BookDetailData(
                BookDetailBook.from(book),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                true,
            ),
        )

    @Test
    fun mutationReaderReceiptRetainsPreMutationOwnerUrlAcrossDiskRestore() = runBlocking {
        val old = Book(bookUrl = "old", name = "Name", origin = "source")
        withContext(Dispatchers.IO) { database.bookDao.insert(old) }
        val id = UUID.randomUUID().toString()
        val initial = record(old)
        repository().write(id, initial)
        repository()
            .mutate(
                id,
                initial,
                BookDetailOperation(
                    "cover",
                    BookDetailMutation(BookDetailMutationKind.Cover, text = "cover"),
                ),
            )
        val effect =
            repository().read(id)!!.effects.single { it.kind == BookDetailNativeKind.ReaderSync }
        assertEquals("old", effect.expectedBookUrl)
        assertEquals("cover", effect.book!!.materializeBook().customCoverUrl)
    }

    @Test
    fun scriptChangingUrlKeepsOldReaderOwnerInDurableReceiptAndPreparesNewOwnerHighlightsSeparately() =
        runBlocking {
            val old = Book(bookUrl = "old", name = "Name", origin = "source")
            withContext(Dispatchers.IO) { database.bookDao.insert(old) }
            val id = UUID.randomUUID().toString()
            val initial = record(old)
            repository().write(id, initial)
            val result =
                BookDetailNetworkResult(
                    BookDetailBook.from(old.copy(bookUrl = "new", tocUrl = "new-toc")),
                    emptyList(),
                    emptyList(),
                )
            repository().completeNetwork(id, initial, initial.data!!, result, false, "network")
            val effect =
                repository().read(id)!!.effects.single {
                    it.kind == BookDetailNativeKind.ReaderSync
                }
            assertEquals("old", effect.expectedBookUrl)
            assertEquals("new", effect.book!!.bookUrl)
            withContext(Dispatchers.IO) {
                assertNull(database.bookDao.getBook("old"))
                assertNotNull(database.bookDao.getBook("new"))
            }
        }
}
