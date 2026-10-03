package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailChapter
import io.legado.app.data.repository.BookDetailData
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailMutation
import io.legado.app.data.repository.BookDetailMutationKind
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailNetworkRepository
import io.legado.app.data.repository.BookDetailNetworkResult
import io.legado.app.data.repository.BookDetailOperation
import io.legado.app.data.repository.BookDetailPreference
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailPrompt
import io.legado.app.data.repository.BookDetailPromptKind
import io.legado.app.data.repository.BookDetailRepository
import io.legado.app.data.repository.BookDetailServiceKind
import io.legado.app.data.repository.BookDetailServiceRequest
import io.legado.app.data.repository.BookDetailServiceSessionRepository
import io.legado.app.data.repository.BookDetailServicesRepository
import io.legado.app.data.repository.BookDetailSession
import io.legado.app.data.repository.BookDetailSessionRepository
import io.legado.app.data.repository.BookDetailSource
import io.legado.app.data.repository.BookDetailWebFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailCommandsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        scope: CoroutineScope,
        web: Boolean = false,
        shelf: Boolean = true,
        chapters: Boolean = true,
    ) {
        val book =
            BookDetailBook.from(
                Book(
                    bookUrl = "book",
                    name = "Name",
                    author = "Author",
                    origin = "source",
                    tocUrl = "toc",
                    coverUrl = "cover",
                    type = if (web) BookType.webFile else BookType.text,
                )
            )
        val data =
            BookDetailData(
                book,
                BookDetailSource.from(
                    BookSource(bookSourceUrl = "source", bookSourceName = "Source")
                ),
                if (chapters) listOf(BookDetailChapter("{}", 0, "chapter", "Chapter", false))
                else emptyList(),
                emptyList(),
                emptyList(),
                shelf,
            )
        val sessions =
            Sessions(
                BookDetailSession(
                    BookDetailIdentity(bookUrl = "book"),
                    data,
                    webFiles =
                        listOf(
                            BookDetailWebFile("file-txt", "one.txt"),
                            BookDetailWebFile("file-zip", "two.zip"),
                            BookDetailWebFile("file-bin", "three.bin"),
                        ),
                )
            )
        val preferences = Preferences()
        val preferencesModel = BookDetailPreferencesViewModel(SavedStateHandle(), preferences)
        val services = mutableListOf<BookDetailServiceRequest>()
        val navigation = mutableListOf<Pair<BookDetailMutation, BookDetailNativeKind>>()
        val errors = mutableListOf<String>()
        val model =
            BookDetailViewModel(
                SavedStateHandle(),
                object : BookDetailRepository {
                    override suspend fun resolve(identity: BookDetailIdentity) = data

                    override suspend fun reload(bookUrl: String) = data

                    override suspend fun describe(book: BookDetailBook, inBookshelf: Boolean) =
                        data.copy(book = book, inBookshelf = inBookshelf)
                },
                sessions,
                object : BookDetailNetworkRepository {
                    override suspend fun info(
                        book: BookDetailBook,
                        source: BookDetailSource?,
                        canRename: Boolean,
                        runPreUpdate: Boolean,
                    ) = error("unexpected network")

                    override suspend fun toc(
                        book: BookDetailBook,
                        source: BookDetailSource?,
                        runPreUpdate: Boolean,
                        fromBookInfo: Boolean,
                    ) = error("unexpected toc")

                    override suspend fun files(book: BookDetailBook, source: BookDetailSource?) =
                        emptyList<BookDetailWebFile>()

                    override suspend fun cover(book: BookDetailBook): BookDetailBook? = null
                },
                null,
                serviceSession =
                    object : BookDetailServiceSessionRepository {
                        override suspend fun execute(
                            ticket: String,
                            record: BookDetailSession,
                            request: BookDetailServiceRequest,
                        ): BookDetailSession {
                            services += request
                            return record.copy(revision = record.revision + 1).also {
                                sessions.record = it
                            }
                        }
                    },
            )
        val commands =
            BookDetailCommands(
                model,
                preferencesModel,
                scope,
                { errors += it },
                { change, kind -> navigation += change to kind },
                { model.queue(BookDetailNativeKind.ClearCacheRequest) },
                BookDetailCommandTexts("no remote", "empty chapters", "missing files", "loading"),
            )

        fun stop() {
            model.stop()
            preferencesModel.stop()
        }
    }

    @Test
    fun tocUsesSettledNavigationAndEmptyChapterListDoesNotOpenReader() =
        runTest(dispatcher) {
            val empty = Fixture(this, chapters = false)
            val populated = Fixture(this)
            try {
                runCurrent()
                empty.commands.action(BookDetailAction.Toc)
                assertEquals(listOf("empty chapters"), empty.errors)
                assertTrue(empty.navigation.isEmpty())
                populated.commands.action(BookDetailAction.Toc)
                populated.commands.action(BookDetailAction.Read)
                assertEquals(
                    listOf(BookDetailNativeKind.Toc, BookDetailNativeKind.Reader),
                    populated.navigation.map { it.second },
                )
                assertEquals(
                    listOf(BookDetailMutationKind.PrepareToc, BookDetailMutationKind.PrepareRead),
                    populated.navigation.map { it.first.kind },
                )
            } finally {
                empty.stop()
                populated.stop()
                runCurrent()
            }
        }

    @Test
    fun webFileSelectionUsesLatestCommittedUploadPreferenceAndCanceledConfirmationDoesNotDownload() =
        runTest(dispatcher) {
            val fixture = Fixture(this, web = true, shelf = false)
            val gate = CompletableDeferred<Unit>()
            try {
                runCurrent()
                fixture.commands.action(BookDetailAction.Read)
                runCurrent()
                val prompt = fixture.model.state.value.session!!.prompt!!
                assertTrue(prompt.readAfter)
                fixture.preferences.gate = gate
                fixture.commands.uploadImported(true)
                runCurrent()
                fixture.commands.confirm(prompt, 0)
                runCurrent()
                assertTrue(fixture.services.isEmpty())
                gate.complete(Unit)
                runCurrent()
                val request = fixture.services.single()
                assertEquals(BookDetailServiceKind.Download, request.kind)
                assertEquals("file-txt", request.file!!.url)
                assertTrue(request.uploadImported)
                assertTrue(request.readAfter)
                fixture.commands.action(BookDetailAction.Read)
                runCurrent()
                val cancel = fixture.model.state.value.session!!.prompt!!
                val second = CompletableDeferred<Unit>()
                fixture.preferences.gate = second
                fixture.commands.uploadImported(false)
                runCurrent()
                fixture.commands.confirm(cancel, 1)
                runCurrent()
                fixture.commands.dismiss(cancel)
                runCurrent()
                second.complete(Unit)
                runCurrent()
                assertEquals(1, fixture.services.size)
                assertNull(fixture.model.state.value.session!!.prompt)
            } finally {
                gate.complete(Unit)
                fixture.preferences.gate?.complete(Unit)
                fixture.stop()
                runCurrent()
            }
        }

    @Test
    fun unsupportedFileRequiresExplicitOpenAndArchiveSelectionRetainsOriginalReadAndUploadIntent() =
        runTest(dispatcher) {
            val fixture = Fixture(this, web = true, shelf = false)
            try {
                runCurrent()
                fixture.commands.action(BookDetailAction.Read)
                runCurrent()
                fixture.commands.confirm(fixture.model.state.value.session!!.prompt!!, 2)
                runCurrent()
                val unsupported = fixture.model.state.value.session!!.prompt!!
                assertEquals(BookDetailPromptKind.UnsupportedFile, unsupported.kind)
                assertEquals("three.bin", unsupported.values.single())
                assertTrue(fixture.services.isEmpty())
                fixture.commands.confirm(unsupported)
                runCurrent()
                assertEquals("file-bin", fixture.services.single().file!!.url)
                assertFalse(fixture.services.single().readAfter)
                val archive =
                    BookDetailPrompt(
                        BookDetailPromptKind.ArchiveEntries,
                        value = "archive-uri",
                        values = listOf("a.txt", "b.epub"),
                        readAfter = true,
                        uploadImported = true,
                    )
                fixture.model.prompt(archive)
                runCurrent()
                fixture.commands.confirm(archive, 1)
                runCurrent()
                val selected = fixture.services.last()
                assertEquals(BookDetailServiceKind.ArchiveImport, selected.kind)
                assertEquals("b.epub", selected.entry)
                assertEquals("archive-uri", selected.uri)
                assertTrue(selected.readAfter)
                assertTrue(selected.uploadImported)
            } finally {
                fixture.stop()
                runCurrent()
            }
        }

    @Test
    fun deletionConfirmationUsesCommittedOriginalFilePreferenceAndRemoteChoiceWhileOverwriteDeclineContinuesReading() =
        runTest(dispatcher) {
            val fixture = Fixture(this)
            try {
                runCurrent()
                fixture.commands.action(BookDetailAction.Shelf)
                runCurrent()
                val initial = fixture.model.state.value.session!!.prompt!!
                assertEquals(BookDetailPromptKind.Delete, initial.kind)
                fixture.commands.deleteOriginal(true)
                fixture.commands.deleteRemote(initial, true)
                runCurrent()
                fixture.commands.confirm(fixture.model.state.value.session!!.prompt!!)
                runCurrent()
                val request = fixture.services.single()
                assertEquals(BookDetailServiceKind.Delete, request.kind)
                assertTrue(request.deleteOriginal)
                assertTrue(request.deleteRemote)
                val overwrite =
                    BookDetailPrompt(BookDetailPromptKind.OverwriteUpload, readAfter = true)
                fixture.model.prompt(overwrite)
                runCurrent()
                fixture.commands.dismiss(overwrite)
                runCurrent()
                assertEquals(BookDetailNativeKind.Reader, fixture.navigation.single().second)
                fixture.model.prompt(overwrite)
                runCurrent()
                fixture.commands.confirm(overwrite)
                runCurrent()
                assertEquals(BookDetailServiceKind.Upload, fixture.services.last().kind)
                assertTrue(fixture.services.last().overwrite)
                assertTrue(fixture.services.last().readAfter)
            } finally {
                fixture.stop()
                runCurrent()
            }
        }

    @Test
    fun clicksPreserveLongPressFlagsAndSourceLabelsAndCacheCallbackQueuesNativeReceipt() =
        runTest(dispatcher) {
            val fixture = Fixture(this)
            try {
                runCurrent()
                fixture.commands.click(BookDetailClick.Name, null, true)
                fixture.commands.click(BookDetailClick.Kind, "Kind", false)
                fixture.commands.click(BookDetailClick.Cover, null, false)
                fixture.commands.click(BookDetailClick.Cover, null, true)
                fixture.commands.action(BookDetailAction.ClearCache)
                runCurrent()
                val effects = fixture.model.state.value.session!!.effects
                assertEquals(
                    listOf(
                        BookDetailNativeKind.SearchName,
                        BookDetailNativeKind.SearchKind,
                        BookDetailNativeKind.Photo,
                        BookDetailNativeKind.ChangeCover,
                        BookDetailNativeKind.ClearCacheRequest,
                    ),
                    effects.map { it.kind },
                )
                assertTrue(effects.first().flag)
                assertEquals("Kind", effects[1].value)
                assertEquals("cover", effects[2].value)
                assertTrue(effects[2].flag)
            } finally {
                fixture.stop()
                runCurrent()
            }
        }

    private class Sessions(var record: BookDetailSession) : BookDetailSessionRepository {
        override suspend fun read(ticket: String) = record

        override suspend fun write(ticket: String, record: BookDetailSession) {
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
        ) = error("unexpected network commit")

        override suspend fun recover(ticket: String) = record

        override suspend fun release(ticket: String) = Unit
    }

    private class Preferences : BookDetailServicesRepository {
        var actual = BookDetailPreferences(true, false, false, true)
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun preferences() = actual

        override suspend fun preference(
            field: BookDetailPreference,
            value: Boolean,
        ): BookDetailPreferences {
            gate?.await()
            actual =
                when (field) {
                    BookDetailPreference.DeleteAlert -> actual.copy(deleteAlert = value)
                    BookDetailPreference.DeleteOriginal -> actual.copy(deleteOriginal = value)
                    BookDetailPreference.UploadImported -> actual.copy(uploadImported = value)
                }
            return actual
        }

        override suspend fun refreshInput(book: BookDetailBook, source: BookDetailSource?) =
            error("unexpected refresh")

        override suspend fun remoteExists(book: BookDetailBook) = false

        override suspend fun upload(book: BookDetailBook, overwrite: Boolean) =
            error("unexpected upload")

        override suspend fun delete(
            book: BookDetailBook,
            deleteOriginal: Boolean,
            deleteRemote: Boolean,
        ) = error("unexpected delete")

        override suspend fun clearCache(book: BookDetailBook) = Unit

        override suspend fun download(
            book: BookDetailBook,
            source: BookDetailSource?,
            file: BookDetailWebFile,
        ) = error("unexpected download")

        override suspend fun archiveEntries(uri: String) = emptyList<String>()

        override suspend fun importArchive(book: BookDetailBook, uri: String, entry: String) =
            error("unexpected archive")

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
            error("unexpected update")
    }
}
