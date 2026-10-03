package io.legado.app.ui.book.info.edit

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookMetadataEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookMetadataEditorViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        models.forEach { it.stop() }
        gates.forEach { it.complete(Unit) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(
        books: Books = Books(),
        store: Store = Store(books),
        cover: Cover = Cover(),
        saved: SavedStateHandle = SavedStateHandle(),
        url: String? = "book",
    ) = BookMetadataEditorViewModel(saved, books, store, cover, url).also { models += it }

    private fun restored(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun initialValuesUseOriginalSourceCoverAndCustomIntroButPreviewCanUsePersistedCover() =
        runTest(dispatcher) {
            val vm = model()
            runCurrent()
            val draft = vm.state.value.draft!!
            assertTrue(vm.state.value.canEdit)
            assertEquals("source-cover", draft.input!!.cover)
            assertEquals("custom intro", draft.input.intro)
            assertTrue(draft.input.changed.isEmpty())
            assertEquals("cached-cover", draft.preview!!.path)
        }

    @Test
    fun movingCursorWithoutChangingTextDoesNotTurnAnUntouchedFieldIntoAnOverride() =
        runTest(dispatcher) {
            val vm = model()
            runCurrent()
            vm.text(BookMetadataField.Name, "Original", 2, 5)
            runCurrent()
            assertEquals(BookMetadataCursor(2, 5), vm.state.value.cursors[BookMetadataField.Name])
            assertTrue(vm.state.value.draft!!.input!!.changed.isEmpty())
            vm.text(BookMetadataField.Name, "Changed", 1, 3)
            assertEquals(setOf(BookMetadataField.Name), vm.state.value.draft!!.input!!.changed)
        }

    @Test
    fun editedFieldsAndAllTypeChoicesAreRecordedWhileTypingCoverDoesNotRefreshPreview() =
        runTest(dispatcher) {
            val vm = model()
            runCurrent()
            val old = vm.state.value.draft!!.preview
            vm.text(BookMetadataField.Name, "Changed")
            vm.text(BookMetadataField.Author, "Changed author")
            vm.text(BookMetadataField.Cover, "new-cover")
            vm.text(BookMetadataField.Intro, "new intro")
            vm.type(4)
            assertEquals(BookMetadataField.entries.toSet(), vm.state.value.draft!!.input!!.changed)
            assertEquals(4, vm.state.value.draft!!.input!!.typeIndex)
            assertEquals(old, vm.state.value.draft!!.preview)
            vm.refreshCover()
            assertEquals("new-cover", vm.state.value.draft!!.preview!!.path)
            assertTrue(vm.state.value.draft!!.input!!.refreshCover)
        }

    @Test
    fun fullLargeIntroDraftAndSmallCursorRestoreWithoutRawInputInSavedBundle() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val saved = SavedStateHandle()
            val vm = model(books, store, saved = saved)
            runCurrent()
            val large = "intro".repeat(200000)
            vm.text(BookMetadataField.Intro, large, 12, 23)
            runCurrent()
            vm.flush()
            vm.stop()
            val next = model(books, store, saved = restored(saved), url = null)
            runCurrent()
            assertEquals(large, next.state.value.draft!!.input!!.intro)
            assertEquals(
                BookMetadataCursor(12, 23),
                next.state.value.cursors[BookMetadataField.Intro],
            )
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { n -> n < 100 } != false
                }
            )
        }

    @Test
    fun savePersistsBeforeOneShotCompletionAndNeverRepeatsWhenConfirmedTwice() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val saved = SavedStateHandle()
            val vm = model(books, store, saved = saved)
            runCurrent()
            vm.text(BookMetadataField.Name, "Changed")
            vm.save()
            vm.save()
            runCurrent()
            assertEquals(1, books.saves)
            assertEquals("Changed", books.lastInput!!.name)
            assertFalse(vm.state.value.canEdit)
            val effect = vm.state.value.draft!!.completion!!
            assertNotNull(store.records[vm.ticket]!!.completion)
            assertNull(vm.consumeCompletion(effect.token) { false })
            assertEquals(effect, vm.consumeCompletion(effect.token) { true })
            assertTrue(store.records[vm.ticket]!!.finished)
            assertNull(vm.consumeCompletion(effect.token) { true })
            vm.stop()
            val next = model(books, store, saved = restored(saved))
            runCurrent()
            assertTrue(next.state.value.finished)
            assertEquals(1, books.saves)
        }

    @Test
    fun failedFinalReceiptRetainsPendingPlanAndExplicitRetryRecoversWithoutSecondInsert() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            store.failFinal = true
            val vm = model(books, store)
            runCurrent()
            vm.text(BookMetadataField.Name, "Changed")
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saveFailed)
            assertFalse(vm.state.value.canEdit)
            assertNotNull(vm.state.value.draft!!.pendingSave)
            assertEquals("Changed", vm.state.value.draft!!.input!!.name)
            store.failFinal = false
            vm.retry()
            runCurrent()
            assertEquals(1, books.saves)
            assertEquals(1, books.recovers)
            assertNotNull(vm.state.value.draft!!.completion)
            assertNull(vm.state.value.error)
        }

    @Test
    fun recoveryConflictKeepsInputAndExplicitReloadReadsExternalMetadataWithoutWritingIt() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            store.failFinal = true
            val vm = model(books, store)
            runCurrent()
            vm.text(BookMetadataField.Name, "User draft")
            vm.save()
            runCurrent()
            books.conflict = true
            books.book = books.book!!.copy(name = "External", author = "External author")
            store.failFinal = false
            vm.retry()
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertEquals("User draft", vm.state.value.draft!!.input!!.name)
            assertFalse(vm.state.value.canEdit)
            vm.reload()
            runCurrent()
            assertEquals("External", vm.state.value.draft!!.input!!.name)
            assertTrue(vm.state.value.canEdit)
            assertEquals(1, books.saves)
            assertNull(vm.state.value.draft!!.pendingSave)
        }

    @Test
    fun deletedBookLoadAndReloadRemainAnErrorAndNeverRecreateIt() =
        runTest(dispatcher) {
            val books = Books()
            books.book = null
            val vm = model(books)
            runCurrent()
            assertFalse(vm.state.value.loaded)
            assertNotNull(vm.state.value.error)
            vm.text(BookMetadataField.Name, "Lost")
            vm.save()
            runCurrent()
            assertEquals(0, books.saves)
            val existing = Books()
            val loaded = model(existing)
            runCurrent()
            existing.book = null
            loaded.reload()
            runCurrent()
            assertNotNull(loaded.state.value.error)
            assertEquals(0, existing.saves)
        }

    @Test
    fun unreadDraftIsNeverOverwrittenAndRetryRestoresTheExactPendingInput() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            store.records["ticket"] =
                draft(books.book!!)
                    .copy(input = input(books.book!!).copy(name = "Persisted draft"), revision = 9)
            store.failRead = true
            val vm =
                model(
                    books,
                    store,
                    saved = SavedStateHandle(mapOf(BookMetadataEditorViewModel.KEY to "ticket")),
                )
            runCurrent()
            assertFalse(vm.state.value.loaded)
            vm.text(BookMetadataField.Name, "Lost")
            assertEquals("Persisted draft", store.records["ticket"]!!.input!!.name)
            store.failRead = false
            vm.retry()
            runCurrent()
            assertEquals("Persisted draft", vm.state.value.draft!!.input!!.name)
        }

    @Test
    fun stoppedNonCooperativeLoadPublishesNothingAndDoesNotCreateADraft() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val gate = CompletableDeferred<Unit>()
            gates += gate
            books.gate = gate
            val vm = model(books, store)
            runCurrent()
            vm.stop()
            val before = vm.state.value
            gate.complete(Unit)
            runCurrent()
            assertEquals(before, vm.state.value)
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun imageResultWaitsForInitialLoadRejectsOldOwnerAndIgnoresDuplicateAfterImport() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val cover = Cover()
            store.records["ticket"] = draft(books.book!!).copy(pickerOwner = "owned")
            val vm =
                model(
                    books,
                    store,
                    cover,
                    SavedStateHandle(mapOf(BookMetadataEditorViewModel.KEY to "ticket")),
                )
            vm.coverResult("owned", "content://image")
            runCurrent()
            assertEquals(listOf("content://image"), cover.requests)
            assertEquals("/installed-cover.png", vm.state.value.draft!!.input!!.cover)
            assertNull(vm.state.value.draft!!.coverUri)
            vm.coverResult("owned", "duplicate")
            vm.coverResult("other", "old")
            runCurrent()
            assertEquals(1, cover.requests.size)
        }

    @Test
    fun coverImportFailureKeepsOwnedUriForExplicitRetryAndNoPartialPreview() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val cover = Cover()
            val vm = model(books, store, cover)
            runCurrent()
            vm.navigate(BookMetadataAction.PickCover)
            runCurrent()
            val nav = vm.state.value.draft!!.navigation!!
            vm.consumeNavigation(nav.token) { true }
            cover.fail = true
            vm.coverResult(nav.token, "content://image")
            runCurrent()
            assertEquals("content://image", vm.state.value.draft!!.coverUri)
            assertEquals("source-cover", vm.state.value.draft!!.input!!.cover)
            assertNotNull(vm.state.value.error)
            cover.fail = false
            vm.retry()
            runCurrent()
            assertEquals("/installed-cover.png", vm.state.value.draft!!.preview!!.path)
            assertNull(vm.state.value.draft!!.coverUri)
            assertNull(vm.state.value.error)
        }

    @Test
    fun pickerCancellationChangesNoFieldsAndDefaultCoverCallbackInvalidatesCache() =
        runTest(dispatcher) {
            val vm = model()
            runCurrent()
            vm.navigate(BookMetadataAction.PickCover)
            runCurrent()
            val nav = vm.state.value.draft!!.navigation!!
            vm.consumeNavigation(nav.token) { true }
            vm.coverResult(nav.token, null)
            runCurrent()
            assertNull(vm.state.value.draft!!.pickerOwner)
            assertTrue(vm.state.value.draft!!.input!!.changed.isEmpty())
            vm.coverChanged("use_default_cover")
            runCurrent()
            assertEquals("use_default_cover", vm.state.value.draft!!.input!!.cover)
            assertEquals("use_default_cover", vm.state.value.draft!!.preview!!.path)
            assertTrue(vm.state.value.draft!!.input!!.refreshCover)
        }

    @Test
    fun canceledNonCooperativeNavigationClaimRestoresReceiptBeforeNextResume() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val vm = model(books, store)
            runCurrent()
            vm.navigate(BookMetadataAction.PickCover)
            runCurrent()
            val nav = vm.state.value.draft!!.navigation!!
            val gate = CompletableDeferred<Unit>()
            gates += gate
            store.gate = gate
            val job = launch {
                vm.consumeNavigation(nav.token) { true }
                error("Canceled navigation delivered")
            }
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals(nav, store.records[vm.ticket]!!.navigation)
            assertNull(vm.state.value.draft!!.pickerOwner)
            assertEquals(nav, vm.consumeNavigation(nav.token) { true })
        }

    @Test
    fun canceledCompletionClaimRestoresCallbackWithoutRepeatingRoomSave() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val vm = model(books, store)
            runCurrent()
            vm.save()
            runCurrent()
            val effect = vm.state.value.draft!!.completion!!
            val gate = CompletableDeferred<Unit>()
            gates += gate
            store.gate = gate
            val job = launch {
                vm.consumeCompletion(effect.token) { true }
                error("Canceled completion delivered")
            }
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals(effect, vm.state.value.draft!!.completion)
            assertFalse(vm.state.value.finished)
            assertEquals(1, books.saves)
            assertEquals(effect, vm.consumeCompletion(effect.token) { true })
        }

    @Test
    fun realCloseCleansSessionAndClosedRestoreNeverLoadsOrSavesBook() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val saved = SavedStateHandle()
            val vm = model(books, store, saved = saved)
            runCurrent()
            vm.text(BookMetadataField.Intro, "private")
            runCurrent()
            vm.close()
            runCurrent()
            assertTrue(vm.state.value.closed)
            assertTrue(store.records.isEmpty())
            val loads = books.loads
            val next = model(books, store, saved = restored(saved))
            runCurrent()
            assertTrue(next.state.value.closed)
            assertEquals(loads, books.loads)
            assertEquals(0, books.saves)
        }

    @Test
    fun childCoverCallbackArrivingBeforeLoadWaitsForDraftAndClosedHostIgnoresLateCallback() =
        runTest(dispatcher) {
            val books = Books()
            val gate = CompletableDeferred<Unit>()
            gates += gate
            books.gate = gate
            val vm = model(books)
            vm.receiveCover("use_default_cover")
            runCurrent()
            assertNull(vm.state.value.draft)
            gate.complete(Unit)
            runCurrent()
            assertEquals("use_default_cover", vm.state.value.draft!!.input!!.cover)
            assertEquals("use_default_cover", vm.state.value.draft!!.preview!!.path)
            assertTrue(vm.state.value.draft!!.input!!.refreshCover)
            vm.close()
            runCurrent()
            val closed = vm.state.value
            vm.receiveCover("late cover")
            runCurrent()
            assertEquals(closed, vm.state.value)
        }

    @Test
    fun consumedSuccessfulSaveRetainsOkResultAfterCloseDeletesPrivateDraftAndHostRestores() =
        runTest(dispatcher) {
            val books = Books()
            val store = Store(books)
            val saved = SavedStateHandle()
            val vm = model(books, store, saved = saved)
            runCurrent()
            vm.save()
            runCurrent()
            val effect = vm.state.value.draft!!.completion!!
            assertNotNull(vm.consumeCompletion(effect.token) { true })
            vm.close()
            runCurrent()
            assertTrue(store.records.isEmpty())
            val beforeLoads = books.loads
            val next = model(books, store, saved = restored(saved), url = null)
            runCurrent()
            assertTrue(next.state.value.closed)
            assertTrue(next.state.value.finished)
            assertNull(next.state.value.draft)
            assertNull(next.consumeCompletion(effect.token) { true })
            assertEquals(1, books.saves)
            assertEquals(beforeLoads, books.loads)
        }

    private class Books : BookMetadataEditorRepository {
        var book: BookMetadataSnapshot? =
            BookMetadataSnapshot(
                "book",
                "Original",
                "Author",
                8,
                "origin",
                "source-cover",
                null,
                "cached-cover",
                "source intro",
                "custom intro",
            )
        var loads = 0
        var saves = 0
        var recovers = 0
        var lastInput: BookMetadataInput? = null
        var conflict = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(bookUrl: String): BookMetadataSnapshot? {
            loads++
            val before = book
            gate?.let { withContext(NonCancellable) { it.await() } }
            return before
        }

        override suspend fun save(
            input: BookMetadataInput,
            journal: (BookMetadataSave) -> Unit,
        ): BookMetadataSnapshot {
            val current = book ?: throw BookMetadataMissing()
            lastInput = input
            journal(BookMetadataSave("before", "target"))
            saves++
            return current.copy(name = "Saved", author = "Saved author").also { book = it }
        }

        override suspend fun recover(plan: BookMetadataSave): BookMetadataSnapshot {
            recovers++
            if (conflict) throw BookMetadataConflict()
            return book ?: throw BookMetadataMissing()
        }
    }

    private class Cover : BookMetadataCoverImportRepository {
        val requests = mutableListOf<String>()
        var fail = false

        override suspend fun install(uri: String): String {
            requests += uri
            if (fail) error("cover failed")
            return "/installed-cover.png"
        }
    }

    private class Store(private val books: Books) : BookMetadataEditorSessionRepository {
        val records = mutableMapOf<String, BookMetadataDraft>()
        val released = mutableSetOf<String>()
        var failRead = false
        var failFinal = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun read(ticket: String): BookMetadataDraft? {
            if (failRead) error("read failed")
            return records[ticket]
        }

        override suspend fun write(ticket: String, draft: BookMetadataDraft) {
            val old = records[ticket]
            if (
                (old?.navigation != null && draft.navigation == null) ||
                    (old?.completion != null && draft.completion == null)
            ) {
                val current = gate
                gate = null
                current?.let { withContext(NonCancellable) { it.await() } }
            }
            check(ticket !in released)
            if ((records[ticket]?.revision ?: -1) <= draft.revision) records[ticket] = draft
        }

        override suspend fun save(ticket: String, draft: BookMetadataDraft): BookMetadataDraft {
            var current =
                records[ticket]?.takeIf {
                    it.pendingSave != null || it.completion != null || it.finished
                } ?: draft
            if (current.completion != null || current.finished) return current
            val book =
                if (current.pendingSave != null) books.recover(current.pendingSave!!)
                else
                    books.save(current.input!!) { plan ->
                        current = current.copy(pendingSave = plan, revision = current.revision + 1)
                        records[ticket] = current
                    }
            if (failFinal) error("receipt failed")
            return current
                .copy(
                    pendingSave = null,
                    completion = BookMetadataCompletion("completed", book),
                    revision = current.revision + 1,
                )
                .also { records[ticket] = it }
        }

        override suspend fun release(ticket: String) {
            released += ticket
            records.remove(ticket)
        }
    }

    companion object {
        private fun input(book: BookMetadataSnapshot) =
            BookMetadataInput(
                book.bookUrl,
                book.name,
                book.author,
                book.typeIndex,
                book.coverText,
                book.introText,
            )

        private fun draft(book: BookMetadataSnapshot) =
            BookMetadataDraft(book.bookUrl, book, input(book), book.preview())
    }
}
