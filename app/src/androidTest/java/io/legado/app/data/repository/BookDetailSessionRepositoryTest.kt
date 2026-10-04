package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailSessionRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "detail-session-test-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repository(beforeWrite: (BookDetailSession) -> Unit = {}) =
        FileBookDetailSessionRepository(
            context,
            RoomBookDetailStorageRepository(database),
            RoomBookDetailRepository(database),
            RoomBookDetailNetworkStorageRepository(database, { _, _ -> }, {}),
            directory,
            beforeWrite,
        )

    private fun ticket() = UUID.randomUUID().toString()

    private fun book() =
        Book(bookUrl = "book", name = "Name", author = "Author", origin = "source", group = 1)

    private fun data(book: Book, shelf: Boolean = true) =
        BookDetailData(
            BookDetailBook.from(book),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            shelf,
        )

    private fun record(book: Book = book()) =
        BookDetailSession(BookDetailIdentity(book.name, book.author, book.bookUrl), data(book))

    private suspend fun insert(book: Book) =
        withContext(Dispatchers.IO) { database.bookDao.insert(book) }

    private suspend fun read(url: String) =
        withContext(Dispatchers.IO) { database.bookDao.getBook(url) }

    @Test
    fun largeUrlsCachedHtmlAndNativePayloadRoundTripThroughDiskAndStaleRevisionCannotOverwriteThem() =
        runBlocking {
            val full = book().copy(bookUrl = "https://book/" + "x".repeat(1_000_000))
            full.infoHtml = "HTML".repeat(300_000)
            full.tocHtml = "toc"
            full.downloadUrls = listOf("file")
            val snapshot = BookDetailBook.from(full)
            val id = ticket()
            val record =
                record(full)
                    .copy(
                        effects =
                            listOf(
                                BookDetailNativeEffect(
                                    "open",
                                    BookDetailNativeKind.Reader,
                                    snapshot,
                                )
                            ),
                        revision = 3,
                    )
            repository().write(id, record)
            repository().write(id, record.copy(revision = 2, data = null))
            val restored = repository().read(id)!!
            assertEquals(full.bookUrl, restored.identity.bookUrl)
            assertEquals(full.infoHtml, restored.data!!.book.materializeBook().infoHtml)
            assertEquals(
                full.downloadUrls,
                restored.effects.single().book!!.materializeBook().downloadUrls,
            )
            assertEquals(3L, restored.revision)
        }

    @Test
    fun mutationFinalReceiptFailureRecoversCommittedRoomWithoutLosingDraftAndDeliveriesAreNotRecreatedAfterConsume() =
        runBlocking {
            val old = book()
            insert(old)
            val id = ticket()
            val initial = record(old)
            repository().write(id, initial)
            var fail = true
            val failing = repository {
                if (fail && it.pendingMutation == null && "group" in it.completedOperations)
                    error("final disk write failed")
            }
            val operation =
                BookDetailOperation(
                    "group",
                    BookDetailMutation(BookDetailMutationKind.Group, group = 9),
                )
            assertTrue(runCatching { failing.mutate(id, initial, operation) }.isFailure)
            assertEquals(9L, read("book")!!.group)
            assertNotNull(repository().read(id)!!.pendingMutation)
            fail = false
            val restored = repository().recover(id)!!
            assertNull(restored.pendingMutation)
            assertEquals(9L, restored.data!!.book.materializeBook().group)
            assertEquals(1, restored.effects.count { it.kind == BookDetailNativeKind.ReaderSync })
            val consumed = restored.copy(effects = emptyList(), revision = restored.revision + 1)
            repository().write(id, consumed)
            val repeated = repository().mutate(id, initial, operation)
            assertTrue(repeated.effects.isEmpty())
            assertEquals(consumed.revision, repeated.revision)
        }

    @Test
    fun fullParsedResponseIsSavedBeforeRoomAndFinalFailureRestoresHtmlWithoutRefetchOrDuplicateNavigation() =
        runBlocking {
            val old = book()
            insert(old)
            val id = ticket()
            val initial = record(old)
            repository().write(id, initial)
            val parsed = old.copy(bookUrl = "changed", tocUrl = "toc", totalChapterNum = 1)
            parsed.infoHtml = "cached info"
            parsed.tocHtml = "cached toc"
            val result =
                BookDetailNetworkResult(
                    BookDetailBook.from(parsed),
                    listOf(
                        BookDetailChapter.from(
                            BookChapter(bookUrl = "changed", url = "chapter", title = "Chapter")
                        )
                    ),
                    emptyList(),
                )
            val failing = repository {
                if (it.pendingNetwork == null && "network" in it.completedOperations)
                    error("final receipt failed")
            }
            assertTrue(
                runCatching {
                    failing.completeNetwork(
                        id,
                        initial,
                        initial.data!!,
                        result,
                        false,
                        "network",
                    )
                }
                    .isFailure
            )
            assertNull(read("book"))
            assertNotNull(read("changed"))
            assertNotNull(repository().read(id)!!.pendingNetwork!!.plan)
            val restored = repository().recover(id)!!
            assertEquals("cached info", restored.data!!.book.materializeBook().infoHtml)
            assertEquals("cached toc", restored.data.book.materializeBook().tocHtml)
            assertEquals("Chapter", restored.data.chapters.single().title)
            assertFalse(restored.running)
            assertNull(restored.pendingNetwork)
            assertEquals(1, restored.effects.count { it.kind == BookDetailNativeKind.ReaderSync })
            assertEquals(
                restored,
                repository().completeNetwork(id, initial, initial.data!!, result, false, "network"),
            )
        }

    @Test
    fun firstJournalFailureLeavesRoomUnchangedAndRecoveredConflictNeverOverwritesNewExternalProgress() =
        runBlocking {
            val old = book()
            insert(old)
            val id = ticket()
            val initial = record(old)
            repository().write(id, initial)
            val parsed = old.copy(bookUrl = "changed")
            val result =
                BookDetailNetworkResult(BookDetailBook.from(parsed), emptyList(), emptyList())
            val failing = repository {
                if (it.pendingNetwork != null) error("first journal failed")
            }
            assertTrue(
                runCatching {
                    failing.completeNetwork(
                        id,
                        initial,
                        initial.data!!,
                        result,
                        false,
                        "network",
                    )
                }
                    .isFailure
            )
            assertNotNull(read("book"))
            assertNull(read("changed"))
            assertNull(repository().read(id)!!.pendingNetwork)
            val finalFail = repository {
                if (it.pendingNetwork == null && "network" in it.completedOperations)
                    error("final write failed")
            }
            assertTrue(
                runCatching {
                    finalFail.completeNetwork(
                        id,
                        initial,
                        initial.data!!,
                        result,
                        false,
                        "network",
                    )
                }
                    .isFailure
            )
            insert(read("changed")!!.copy(durChapterPos = 900))
            assertTrue(runCatching { repository().recover(id) }.isFailure)
            assertEquals(900, read("changed")!!.durChapterPos)
            assertNotNull(repository().read(id)!!.pendingNetwork)
        }

    @Test
    fun explicitReleaseRemovesAtomicFilesAndClosedBackupFencesAllLateWritersAcrossInstances() =
        runBlocking {
            val id = ticket()
            val record = record()
            repository().write(id, record)
            repository().release(id)
            assertNull(repository().read(id))
            assertTrue(runCatching { repository().write(id, record.copy(revision = 9)) }.isFailure)
            val marker = File(directory, "$id.closed")
            assertTrue(marker.renameTo(File(marker.path + ".bak")))
            assertTrue(runCatching { repository().write(id, record.copy(revision = 10)) }.isFailure)
            assertTrue(listOf("", ".bak", ".new").none { File(directory, "$id.json$it").exists() })
        }

    @Test
    fun releaseWaitsForInFlightWriterThenLateWriteCannotResurrectSession() = runBlocking {
        val id = ticket()
        val entered = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val writer = repository {
            entered.countDown()
            check(continueWrite.await(5, TimeUnit.SECONDS))
        }
        val write = async(Dispatchers.IO) { writer.write(id, record()) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        val release = async(Dispatchers.IO) { repository().release(id) }
        try {
            yield()
            assertFalse(release.isCompleted)
        } finally {
            continueWrite.countDown()
        }
        write.await()
        release.await()
        assertNull(repository().read(id))
        assertTrue(runCatching { repository().write(id, record().copy(revision = 1)) }.isFailure)
    }

    @Test
    fun privatePreviewEditsRemainPrivateButOperationReceiptsStillPreventDuplicateDraftEdits() =
        runBlocking {
            val original = book()
            val id = ticket()
            val initial = record(original).copy(data = data(original, false))
            repository().write(id, initial)
            val operation =
                BookDetailOperation(
                    "cover",
                    BookDetailMutation(BookDetailMutationKind.Cover, text = "private"),
                )
            val updated = repository().mutate(id, initial, operation)
            assertNull(read("book"))
            assertEquals("private", updated.data!!.book.materializeBook().customCoverUrl)
            assertTrue(updated.effects.isEmpty())
            assertEquals(updated, repository().mutate(id, initial, operation))
        }
}
