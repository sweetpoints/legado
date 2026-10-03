package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailChapter
import io.legado.app.data.repository.BookDetailData
import io.legado.app.data.repository.BookDetailEntryRepository
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailNetworkRepository
import io.legado.app.data.repository.BookDetailNetworkResult
import io.legado.app.data.repository.BookDetailOperation
import io.legado.app.data.repository.BookDetailRepository
import io.legado.app.data.repository.BookDetailSession
import io.legado.app.data.repository.BookDetailSessionRepository
import io.legado.app.data.repository.BookDetailSource
import io.legado.app.data.repository.BookDetailWebFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailPreparedEntryTest {
    @Test
    fun preparedIdentityRestoresFromOnlyTicketAndRetainsFirstInfoFetch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val expectedIdentity = BookDetailIdentity("Name", "Author", "book-" + "url".repeat(100_000))
        val sessions = Sessions()
        val ticket = BookDetailEntryRepository(sessions, dispatcher).prepare(expectedIdentity)
        val saved = SavedStateHandle(mapOf("book.detail.ticket" to ticket))
        val book =
            BookDetailBook.from(
                Book(
                    bookUrl = expectedIdentity.bookUrl,
                    name = expectedIdentity.name,
                    author = expectedIdentity.author,
                    origin = "source",
                    coverUrl = "cover",
                )
            )
        val data =
            BookDetailData(
                book,
                null,
                listOf(BookDetailChapter("{}", 0, "chapter", "Existing chapter", false)),
                emptyList(),
                emptyList(),
                false,
            )
        var infoRequests = 0
        val model =
            BookDetailViewModel(
                saved,
                object : BookDetailRepository {
                    override suspend fun resolve(identity: BookDetailIdentity): BookDetailData {
                        assertEquals(expectedIdentity, identity)
                        return data
                    }

                    override suspend fun reload(bookUrl: String) = data

                    override suspend fun describe(book: BookDetailBook, inBookshelf: Boolean) = data
                },
                sessions,
                object : BookDetailNetworkRepository {
                    override suspend fun info(
                        book: BookDetailBook,
                        source: BookDetailSource?,
                        canRename: Boolean,
                        runPreUpdate: Boolean,
                    ): BookDetailNetworkResult {
                        infoRequests++
                        error("controlled initial request failure")
                    }

                    override suspend fun toc(
                        book: BookDetailBook,
                        source: BookDetailSource?,
                        runPreUpdate: Boolean,
                        fromBookInfo: Boolean,
                    ): BookDetailNetworkResult = error("unexpected toc")

                    override suspend fun files(book: BookDetailBook, source: BookDetailSource?) =
                        emptyList<BookDetailWebFile>()

                    override suspend fun cover(book: BookDetailBook): BookDetailBook? = null
                },
                null,
            )
        val store = ViewModelStore().apply { put("detail", model) }
        val ownerJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
        try {
            // The production bootstrap deliberately parses the potentially large book JSON on IO.
            // Wait for that real dispatcher and its Main return, rather than treating runCurrent
            // as completion of work outside the virtual test scheduler.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    model.state.first {
                        it.loaded &&
                            !it.networkLoading &&
                            it.error == "controlled initial request failure"
                    }
                }
            }
            assertTrue(model.state.value.loaded)
            assertEquals(expectedIdentity.bookUrl, model.state.value.data!!.book.bookUrl)
            assertEquals(1, infoRequests)
            assertEquals(data, model.state.value.data)
            assertFalse(saved.contains("name"))
            assertFalse(saved.contains("author"))
            assertFalse(saved.contains("bookUrl"))
            assertEquals(ticket, saved.get<String>("book.detail.ticket"))
        } finally {
            store.clear()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { ownerJob.join() }
            }
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedOrCancelledPreparationReleasesOnlyItsUndeliveredTicket() = runTest {
        val sessions = Sessions().apply { failWrite = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val result = runCatching {
            BookDetailEntryRepository(sessions, dispatcher)
                .prepare(BookDetailIdentity(bookUrl = "book"))
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, sessions.released.size)
        assertTrue(sessions.records.isEmpty())
    }

    private class Sessions : BookDetailSessionRepository {
        val records = mutableMapOf<String, BookDetailSession>()
        val released = mutableListOf<String>()
        var failWrite = false

        override suspend fun read(ticket: String) = records[ticket]

        override suspend fun write(ticket: String, record: BookDetailSession) {
            records[ticket] = record
            if (failWrite) throw CancellationException("cancelled accepted disk write")
        }

        override suspend fun release(ticket: String) {
            released += ticket
            records.remove(ticket)
        }

        override suspend fun recover(ticket: String) = read(ticket)

        override suspend fun mutate(
            ticket: String,
            record: BookDetailSession,
            operation: BookDetailOperation,
        ) = error("unexpected mutation")

        override suspend fun completeNetwork(
            ticket: String,
            record: BookDetailSession,
            request: BookDetailData,
            result: BookDetailNetworkResult,
            sourceChanged: Boolean,
            token: String,
        ) = error("unexpected network commit")
    }
}
