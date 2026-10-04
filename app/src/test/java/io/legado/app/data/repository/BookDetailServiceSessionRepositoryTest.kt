package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailServiceSessionRepositoryTest {
    private fun book(url: String = "remote") =
        BookDetailBook.from(
            Book(bookUrl = url, name = "Name", author = "Author", origin = "source", tocUrl = "toc")
        )

    private fun data(book: BookDetailBook = book()) =
        BookDetailData(book, null, emptyList(), emptyList(), emptyList(), true)

    private fun record() = BookDetailSession(BookDetailIdentity("Name", "Author", "remote"), data())

    private fun request(kind: BookDetailServiceKind) =
        BookDetailServiceRequest("operation", kind, book())

    private fun runner(
        services: Services,
        sessions: Sessions,
        network: Network = Network(),
        io: CoroutineDispatcher,
    ) = DefaultBookDetailServiceSessionRepository(services, Details(), sessions, network, io)

    @Test
    fun refreshInputReceiptRestartsFailedInfoStageWithoutRepeatingRemoteRefresh() = runTest {
        val services = Services()
        val sessions = Sessions(record())
        val network = Network()
        network.fail = true
        val repo = runner(services, sessions, network, StandardTestDispatcher(testScheduler))
        val intent = request(BookDetailServiceKind.Refresh)
        assertTrue(runCatching { repo.execute("ticket", sessions.record, intent) }.isFailure)
        assertNotNull(sessions.record.pendingService!!.result)
        assertEquals(listOf("refresh"), services.events)
        network.fail = false
        val restored = repo.execute("ticket", sessions.record, intent)
        assertEquals(listOf("refresh"), services.events)
        assertNull(restored.pendingService)
        assertEquals(listOf("info:remote:false:true", "info:remote:false:true"), network.calls)
        assertTrue("operation:info" in restored.completedOperations)
    }

    @Test
    fun firstJournalFailureDoesNotRunEngineAndRemoteDeleteFailureDoesNotQueueSuccess() = runTest {
        val services = Services()
        val sessions = Sessions(record())
        val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
        sessions.failFirst = true
        assertTrue(
            runCatching {
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
            }
                .isFailure
        )
        assertTrue(services.events.isEmpty())
        assertNull(sessions.record.pendingService)
        sessions.failFirst = false
        services.deleteFailure = true
        assertTrue(
            runCatching {
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
            }
                .isFailure
        )
        assertEquals(listOf("delete"), services.events)
        assertNotNull(sessions.record.pendingService)
        assertTrue(sessions.record.effects.isEmpty())
        assertTrue(sessions.record.completedOperations.isEmpty())
    }

    @Test
    fun completedDeleteReceiptSurvivesFinalWriteFailureAndRetryDoesNotRepeatEngine() = runTest {
        val services = Services()
        val sessions = Sessions(record())
        val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
        sessions.failCompletion = true
        assertTrue(
            runCatching {
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
            }
                .isFailure
        )
        assertNotNull(sessions.record.pendingService!!.result)
        assertTrue(sessions.record.effects.isEmpty())
        sessions.failCompletion = false
        val restored =
            repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
        assertEquals(listOf("delete"), services.events)
        assertNull(restored.pendingService)
        assertEquals(BookDetailNativeKind.Deleted, restored.effects.single().kind)
        assertTrue(restored.effects.single().flag)
        assertEquals(
            restored,
            repo.execute("ticket", restored, request(BookDetailServiceKind.Delete)),
        )
        assertEquals(1, services.events.size)
    }

    @Test
    fun existingRemoteRequestsConfirmationAndConditionalConflictNeverAutomaticallyOverwrites() =
        runTest {
            val services = Services()
            services.exists = true
            val sessions = Sessions(record())
            val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
            val checked =
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.UploadCheck))
            assertEquals(BookDetailPromptKind.OverwriteUpload, checked.prompt!!.kind)
            assertEquals(listOf("exists"), services.events)
            services.uploadFailure = BookDetailUploadConflict()
            val conflict =
                repo.execute(
                    "ticket",
                    checked,
                    request(BookDetailServiceKind.Upload).copy(token = "upload", readAfter = true),
                )
            assertEquals(BookDetailPromptKind.OverwriteUpload, conflict.prompt!!.kind)
            assertTrue(conflict.prompt.readAfter)
            assertEquals(listOf("exists", "upload:false"), services.events)
            assertTrue(conflict.effects.isEmpty())
        }

    @Test
    fun missingRemoteUploadsWithoutAnotherPromptAndImportedUploadErrorStillContinuesReading() =
        runTest {
            val services = Services()
            val sessions = Sessions(record())
            val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
            services.uploadFailure = IllegalStateException("transport unavailable")
            val result =
                repo.execute(
                    "ticket",
                    sessions.record,
                    request(BookDetailServiceKind.UploadCheck).copy(readAfter = true),
                )
            assertEquals(listOf("exists", "upload:false"), services.events)
            assertNull(result.pendingService)
            assertNull(result.prompt)
            assertEquals(
                listOf(BookDetailNativeKind.Toast, BookDetailNativeKind.Reader),
                result.effects.map { it.kind },
            )
            assertEquals("transport unavailable", result.effects.first().value)
        }

    @Test
    fun downloadedLocalOwnerIsTocBaselineAndFailedContinuationRetryDoesNotDownloadAgain() =
        runTest {
            val services = Services()
            services.download = BookDetailDownload(book = book("local"))
            val sessions = Sessions(record())
            val network = Network()
            network.fail = true
            val repo = runner(services, sessions, network, StandardTestDispatcher(testScheduler))
            val intent =
                request(BookDetailServiceKind.Download)
                    .copy(file = BookDetailWebFile("file", "book.txt"), readAfter = true)
            assertTrue(runCatching { repo.execute("ticket", sessions.record, intent) }.isFailure)
            assertEquals(listOf("download"), services.events)
            assertNotNull(sessions.record.pendingService!!.result)
            network.fail = false
            val restored = repo.execute("ticket", sessions.record, intent)
            assertEquals(listOf("download"), services.events)
            assertEquals("local", sessions.networkBaseline!!.book.bookUrl)
            assertTrue(sessions.networkBaseline!!.inBookshelf)
            assertEquals("local", restored.data!!.book.bookUrl)
            assertEquals(
                "local",
                restored.effects.single { it.kind == BookDetailNativeKind.Reader }.book!!.bookUrl,
            )
            assertEquals(listOf("local:true:true", "local:true:true"), network.calls)
        }

    @Test
    fun archiveSingleEntryImportsAutomaticallyWhileMultipleEntriesPreserveSelectionAndReadIntent() =
        runTest {
            val services = Services()
            services.entries = listOf("a.txt", "b.epub")
            val sessions = Sessions(record())
            val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
            val intent =
                request(BookDetailServiceKind.Download)
                    .copy(file = BookDetailWebFile("file", "book.zip"), readAfter = true)
            val many = repo.execute("ticket", sessions.record, intent)
            assertEquals(BookDetailPromptKind.ArchiveEntries, many.prompt!!.kind)
            assertEquals(services.entries, many.prompt.values)
            assertEquals("archive-uri", many.prompt.value)
            assertTrue(many.prompt.readAfter)
            assertEquals(listOf("download", "entries"), services.events)
            services.events.clear()
            services.entries = listOf("one.txt")
            sessions.record = record()
            val single = repo.execute("ticket", sessions.record, intent)
            assertEquals(listOf("download", "entries", "archive:one.txt"), services.events)
            assertNull(single.prompt)
            assertEquals(
                "local",
                single.effects.single { it.kind == BookDetailNativeKind.Reader }.book!!.bookUrl,
            )
        }

    @Test
    fun unsupportedDownloadedFileOpensUriWhileEmptyArchiveOnlyReportsUnsupported() = runTest {
        val services = Services()
        val sessions = Sessions(record())
        val repo = runner(services, sessions, io = StandardTestDispatcher(testScheduler))
        val open =
            repo.execute(
                "ticket",
                sessions.record,
                request(BookDetailServiceKind.Download)
                    .copy(file = BookDetailWebFile("file", "book.bin")),
            )
        assertEquals(BookDetailNativeKind.OpenFile, open.effects.single().kind)
        assertEquals("archive-uri", open.effects.single().value)
        sessions.record = record()
        services.entries = emptyList()
        val empty =
            repo.execute(
                "ticket",
                sessions.record,
                request(BookDetailServiceKind.ArchiveList).copy(uri = "archive-uri"),
            )
        assertEquals(BookDetailNativeKind.Toast, empty.effects.single().kind)
        assertEquals("unsupported_archive", empty.effects.single().value)
        assertFalse(services.events.any { it.startsWith("archive:") })
    }

    @Test
    fun cancelledActualIoReturnDurablyKeepsCompletedEngineReceiptWithoutPublishingNativeUi() =
        runTest {
            val services = Services()
            val sessions = Sessions(record())
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            sessions.receiptEntered = entered
            sessions.receiptGate = gate
            val repo = runner(services, sessions, io = Dispatchers.IO)
            var published = false
            val job = launch {
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
                published = true
            }
            try {
                runCurrent()
                withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
                job.cancel()
                gate.complete(Unit)
                job.join()
                assertFalse(published)
                assertEquals(listOf("delete"), services.events)
                assertNotNull(sessions.record.pendingService!!.result)
                sessions.receiptGate = null
                sessions.receiptEntered = null
                val recovered =
                    withContext(Dispatchers.IO) {
                        repo.execute(
                            "ticket",
                            sessions.record,
                            request(BookDetailServiceKind.Delete),
                        )
                    }
                assertEquals(1, services.events.size)
                assertEquals(BookDetailNativeKind.Deleted, recovered.effects.single().kind)
            } finally {
                gate.complete(Unit)
                job.cancelAndJoin()
            }
        }

    @Test
    fun independentServiceDispatcherAcceptedMutationTransfersReceiptBeforeCancelledReturnHop() =
        runTest {
            val serviceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val sessions = Sessions(record())
            val services =
                object : Services() {
                    override suspend fun delete(
                        book: BookDetailBook,
                        deleteOriginal: Boolean,
                        deleteRemote: Boolean,
                        accepted: suspend (BookDetailBook) -> Unit,
                    ): BookDetailBook =
                        withContext(serviceDispatcher) {
                            events += "delete"
                            // Models an accepted transaction on the independently-owned repository
                            // dispatcher.
                            withContext(NonCancellable) {
                                accepted(book)
                                entered.complete(Unit)
                                gate.await()
                            }
                            book
                        }
                }
            val repo = runner(services, sessions, io = Dispatchers.IO)
            var published = false
            val job = launch {
                repo.execute("ticket", sessions.record, request(BookDetailServiceKind.Delete))
                published = true
            }
            try {
                runCurrent()
                withContext(Dispatchers.IO) { withTimeout(10_000) { entered.await() } }
                assertNotNull(sessions.record.pendingService!!.result)
                job.cancel()
                gate.complete(Unit)
                job.join()
                assertFalse(published)
                val restored =
                    withContext(Dispatchers.IO) {
                        repo.execute(
                            "ticket",
                            sessions.record,
                            request(BookDetailServiceKind.Delete),
                        )
                    }
                assertEquals(listOf("delete"), services.events)
                assertEquals(BookDetailNativeKind.Deleted, restored.effects.single().kind)
            } finally {
                gate.complete(Unit)
                job.cancelAndJoin()
                serviceDispatcher.close()
            }
        }

    private class Details : BookDetailRepository {
        override suspend fun resolve(identity: BookDetailIdentity): BookDetailData? = null

        override suspend fun reload(bookUrl: String) =
            BookDetailData(
                BookDetailBook.from(
                    Book(bookUrl = bookUrl, name = "Name", origin = "local", tocUrl = "toc")
                ),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                true,
            )

        override suspend fun describe(book: BookDetailBook, inBookshelf: Boolean) =
            BookDetailData(book, null, emptyList(), emptyList(), emptyList(), inBookshelf)
    }

    private class Network : BookDetailNetworkRepository {
        val calls = mutableListOf<String>()
        var fail = false

        override suspend fun info(
            book: BookDetailBook,
            source: BookDetailSource?,
            canRename: Boolean,
            runPreUpdate: Boolean,
        ): BookDetailNetworkResult {
            calls += "info:${book.bookUrl}:$canRename:$runPreUpdate"
            if (fail) error("info failed")
            return BookDetailNetworkResult(book, emptyList(), emptyList())
        }

        override suspend fun toc(
            book: BookDetailBook,
            source: BookDetailSource?,
            runPreUpdate: Boolean,
            fromBookInfo: Boolean,
        ): BookDetailNetworkResult {
            calls += "${book.bookUrl}:$runPreUpdate:$fromBookInfo"
            if (fail) error("toc failed")
            return BookDetailNetworkResult(book, emptyList(), emptyList())
        }

        override suspend fun files(book: BookDetailBook, source: BookDetailSource?) =
            emptyList<BookDetailWebFile>()

        override suspend fun cover(book: BookDetailBook): BookDetailBook? = null
    }

    private class Sessions(@Volatile var record: BookDetailSession) : BookDetailSessionRepository {
        var failFirst = false
        var failCompletion = false
        var networkBaseline: BookDetailData? = null
        var receiptEntered: CompletableDeferred<Unit>? = null
        var receiptGate: CompletableDeferred<Unit>? = null

        override suspend fun read(ticket: String) = record

        override suspend fun write(ticket: String, record: BookDetailSession) {
            if (failFirst || (failCompletion && record.pendingService == null))
                error("disk unavailable")
            if (
                record.pendingService?.result != null && this.record.pendingService?.result == null
            ) {
                receiptEntered?.complete(Unit)
                receiptGate?.let { withContext(NonCancellable) { it.await() } }
            }
            this.record = record
        }

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
        ): BookDetailSession {
            networkBaseline = request
            return record
                .copy(
                    data = request.copy(book = result.book),
                    completedOperations = record.completedOperations + token,
                    chapterChanged = true,
                    revision = record.revision + 1,
                )
                .also { this.record = it }
        }

        override suspend fun recover(ticket: String) = record

        override suspend fun release(ticket: String) = Unit
    }

    private open class Services : BookDetailAcceptedServicesRepository {
        val events = mutableListOf<String>()
        var exists = false
        var uploadFailure: Throwable? = null
        var deleteFailure = false
        var download = BookDetailDownload(uri = "archive-uri")
        var entries = listOf("book.txt")

        override suspend fun upload(
            book: BookDetailBook,
            overwrite: Boolean,
            accepted: suspend (BookDetailBook) -> Unit,
        ): BookDetailBook = upload(book, overwrite).also { accepted(it) }

        override suspend fun delete(
            book: BookDetailBook,
            deleteOriginal: Boolean,
            deleteRemote: Boolean,
            accepted: suspend (BookDetailBook) -> Unit,
        ): BookDetailBook = delete(book, deleteOriginal, deleteRemote).also { accepted(it) }

        override suspend fun download(
            book: BookDetailBook,
            source: BookDetailSource?,
            file: BookDetailWebFile,
            accepted: suspend (BookDetailDownload) -> Unit,
        ): BookDetailDownload = download(book, source, file).also { accepted(it) }

        override suspend fun importArchive(
            book: BookDetailBook,
            uri: String,
            entry: String,
            accepted: suspend (BookDetailBook) -> Unit,
        ): BookDetailBook = importArchive(book, uri, entry).also { accepted(it) }

        override suspend fun preferences() = BookDetailPreferences(true, false, false, true)

        override suspend fun preference(field: BookDetailPreference, value: Boolean) = preferences()

        override suspend fun refreshInput(
            book: BookDetailBook,
            source: BookDetailSource?,
        ): BookDetailRefreshInput {
            events += "refresh"
            return BookDetailRefreshInput(book)
        }

        override suspend fun remoteExists(book: BookDetailBook): Boolean {
            events += "exists"
            return exists
        }

        override suspend fun upload(book: BookDetailBook, overwrite: Boolean): BookDetailBook {
            events += "upload:$overwrite"
            uploadFailure?.let { throw it }
            return book
        }

        override suspend fun delete(
            book: BookDetailBook,
            deleteOriginal: Boolean,
            deleteRemote: Boolean,
        ): BookDetailBook {
            events += "delete"
            if (deleteFailure) throw BookDetailRemoteDeleteFailed()
            return book
        }

        override suspend fun clearCache(book: BookDetailBook) {
            events += "cache"
        }

        override suspend fun download(
            book: BookDetailBook,
            source: BookDetailSource?,
            file: BookDetailWebFile,
        ): BookDetailDownload {
            events += "download"
            return download
        }

        override suspend fun archiveEntries(uri: String): List<String> {
            events += "entries"
            return entries
        }

        override suspend fun importArchive(
            book: BookDetailBook,
            uri: String,
            entry: String,
        ): BookDetailBook {
            events += "archive:$entry"
            return BookDetailBook.from(Book(bookUrl = "local", name = "Name", origin = "local"))
        }

        override suspend fun variable(
            book: BookDetailBook,
            source: BookDetailSource?,
            sourceVariable: Boolean,
            comment: String,
        ) = error("unexpected variable")

        override suspend fun sourceVariable(
            source: BookDetailSource?,
            key: String,
            value: String?,
        ) = Unit

        override suspend fun updateTask(book: BookDetailBook, name: String) =
            error("unexpected task")
    }
}
