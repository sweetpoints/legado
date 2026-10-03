package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TocHostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<TocHostViewModel>()
    private val repos = mutableListOf<Fake>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : TocHostRepository {
        var book: Book? =
            Book(
                bookUrl = "book",
                name = "Book",
                type = BookType.local or BookType.text,
                originName = "book.txt",
                totalChapterNum = 3,
                readConfig = Book.ReadConfig(splitLongChapter = false),
            )
        var use = false
        var words = false
        var fail = false
        var loadGate: CompletableDeferred<Unit>? = null
        var mutationGate: CompletableDeferred<Unit>? = null
        var expandGate: CompletableDeferred<Unit>? = null
        val expansions = mutableListOf<Boolean>()
        val synced = mutableListOf<Boolean>()
        val messages = mutableListOf<Throwable?>()
        val exports = mutableListOf<Pair<String, Boolean>>()

        override suspend fun load(bookUrl: String): Book? {
            val result = book?.let(::ownedTocBook)
            withContext(NonCancellable) { loadGate?.await() }
            if (fail) error("Failed")
            return result
        }

        override suspend fun reverse(book: Book): Book {
            withContext(NonCancellable) { mutationGate?.await() }
            return ownedTocBook(book).apply { setReverseTocDisplay(!getReverseTocDisplay()) }
        }

        override suspend fun expanded(bookUrl: String, value: Boolean) {
            withContext(NonCancellable) { expandGate?.await() }
            expansions += value
        }

        override suspend fun rebuild(book: Book): Book {
            withContext(NonCancellable) { mutationGate?.await() }
            if (fail) error("Parse failure")
            return ownedTocBook(book).apply { totalChapterNum = 9 }
        }

        override suspend fun export(book: Book, directory: String, markdown: Boolean) {
            if (fail) error("Export failure")
            exports += directory to markdown
        }

        override fun synchronizeReverse(book: Book) {}

        override fun synchronizeExpanded(bookUrl: String, value: Boolean) {
            synced += value
        }

        override fun readerMessage(book: Book, error: Throwable?) {
            messages += error
        }

        override fun preferences() = use to words

        override fun useReplace(value: Boolean) {
            use = value
        }

        override fun countWords(value: Boolean) {
            words = value
        }
    }

    private fun fake() = Fake().also { repos += it }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        TocHostViewModel(repo, saved).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                repos.forEach {
                    it.loadGate?.complete(Unit)
                    it.mutationGate?.complete(Unit)
                    it.expandGate?.complete(Unit)
                }
                runCurrent()
            }
        }

    @Test
    fun missingBookIsTypedAndRetryClearsItWhenOwnerAppears() = test {
        val repo = fake()
        val original = repo.book
        repo.book = null
        val model = model(repo)
        model.load("book")
        runCurrent()
        assertTrue(model.state.value.noBook)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.loaded)
        repo.book = original
        model.retry()
        runCurrent()
        assertFalse(model.state.value.noBook)
        assertTrue(model.state.value.loaded)
    }

    @Test
    fun loadPublishesImmutableFlagsAndOwnedSnapshotAndIgnoresDuplicateLoads() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.localText)
        val snapshot = model.snapshot()!!
        snapshot.setTocExpanded(false)
        assertTrue(model.snapshot()!!.getTocExpanded())
        model.load("book")
        assertTrue(model.state.value.loaded)
    }

    @Test
    fun lateNonCooperativeLoadCannotPublishAfterOwnerStop() = test {
        val repo = fake()
        repo.loadGate = CompletableDeferred()
        val model = model(repo)
        model.load("book")
        runCurrent()
        model.stop()
        repo.loadGate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertNull(model.snapshot())
    }

    @Test
    fun reverseBlocksDuplicateMutationAndRefreshesWholeChapterLayoutExactlyOnce() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        val revision = model.state.value.chapterRevision
        repo.mutationGate = CompletableDeferred()
        model.reverse()
        model.reverse()
        runCurrent()
        assertTrue(model.state.value.busy)
        repo.mutationGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.reverse)
        assertEquals(revision + 1, model.state.value.chapterRevision)
        assertTrue(model.state.value.resetCollapse)
        assertTrue(model.state.value.replaceAll)
    }

    @Test
    fun acceptedExpandedConfigurationOutlivesClearedOwnerAndSynchronizesReadersBeforeIoReturns() =
        test {
            val repo = fake()
            val model = model(repo)
            model.load("book")
            runCurrent()
            repo.expandGate = CompletableDeferred()
            model.expanded()
            assertEquals(listOf(false), repo.synced)
            assertFalse(model.state.value.expanded)
            model.stop()
            repo.expandGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(false), repo.expansions)
        }

    @Test
    fun rapidExpandedChangesCommitInUserOrderAndFinalPreferenceMatchesVisibleState() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        repo.expandGate = CompletableDeferred()
        model.expanded()
        model.expanded()
        assertTrue(model.state.value.expanded)
        repo.expandGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), repo.expansions)
        assertEquals(listOf(false, true), repo.synced)
    }

    @Test
    fun splitAndRegexKeepMetadataRebuildChapterAndSendReaderSuccessOrFailure() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        model.split()
        runCurrent()
        assertTrue(model.state.value.split)
        assertEquals(9, model.snapshot()!!.totalChapterNum)
        assertEquals(listOf(null), repo.messages)
        model.regex("Full regex")
        runCurrent()
        assertEquals("Full regex", model.snapshot()!!.tocUrl)
        repo.fail = true
        model.regex("Failing regex")
        runCurrent()
        assertFalse(model.state.value.busy)
        assertNotNull(model.state.value.error)
        assertNotNull(repo.messages.last())
    }

    @Test
    fun preferencesRefreshFlagsAndChapterRevisionWithoutDuplicatingPageAnimations() = test {
        val model = model(fake())
        model.load("book")
        runCurrent()
        val revision = model.state.value.chapterRevision
        model.useReplace()
        assertTrue(model.state.value.useReplace)
        assertTrue(model.state.value.replaceAll)
        model.countWords()
        assertTrue(model.state.value.countWords)
        assertEquals(revision + 2, model.state.value.chapterRevision)
    }

    @Test
    fun nativeDirectoryTicketRestoresWithSmallSavedStateAndDuplicateResultsCannotExportTwice() =
        test {
            val repo = fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            first.load("book")
            runCurrent()
            first.effect(TocHostEffectKind.PickMarkdown)
            val effect = first.state.value.pending!!
            first.delivered(effect.nonce)
            first.stop()
            val restored = model(repo, copy(saved))
            restored.load("book")
            runCurrent()
            restored.picked(effect.requestCode, "directory")
            restored.picked(effect.requestCode, "directory")
            runCurrent()
            assertEquals(listOf("directory" to true), repo.exports)
            assertEquals(TocHostEffectKind.ExportSuccess, restored.state.value.pending!!.kind)
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false
                }
            )
        }

    @Test
    fun pickerCancellationAcknowledgesWithoutDatabaseOrFileWorkAndAllowsAnotherSelection() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        model.effect(TocHostEffectKind.PickJson)
        val first = model.state.value.pending!!
        model.delivered(first.nonce)
        model.picked(first.requestCode, null)
        runCurrent()
        assertTrue(repo.exports.isEmpty())
        model.effect(TocHostEffectKind.PickJson)
        assertTrue(model.state.value.pending!!.requestCode > first.requestCode)
    }

    @Test
    fun restoredEffectIsConsumedByExactNonceOnceAndChangedBookClearsOldTicket() = test {
        val repo = fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.load("book")
        runCurrent()
        first.effect(TocHostEffectKind.Log)
        val effect = first.state.value.pending!!
        first.stop()
        val restored = model(repo, copy(saved))
        restored.load("book")
        runCurrent()
        assertNull(restored.delivered("unknown"))
        assertEquals(effect, restored.delivered(effect.nonce))
        assertNull(restored.delivered(effect.nonce))
        restored.effect(TocHostEffectKind.PickJson)
        restored.load("other")
        runCurrent()
        assertNull(restored.state.value.pending)
    }

    @Test
    fun returningPickerWhileRestoredBookLoadsWaitsForLatestMetadataInsteadOfDroppingSelection() =
        test {
            val repo = fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            first.load("book")
            runCurrent()
            first.effect(TocHostEffectKind.PickJson)
            val effect = first.state.value.pending!!
            first.delivered(effect.nonce)
            first.stop()
            repo.loadGate = CompletableDeferred()
            val restored = model(repo, copy(saved))
            restored.load("book")
            runCurrent()
            restored.picked(effect.requestCode, "directory")
            runCurrent()
            assertTrue(repo.exports.isEmpty())
            repo.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("directory" to false), repo.exports)
        }

    @Test
    fun exportFailureCreatesOneSmallFailureEffectAndAllowsRetryWithAnotherDirectory() = test {
        val repo = fake()
        val model = model(repo)
        model.load("book")
        runCurrent()
        model.effect(TocHostEffectKind.PickJson)
        val pick = model.state.value.pending!!
        model.delivered(pick.nonce)
        repo.fail = true
        model.picked(pick.requestCode, "directory")
        runCurrent()
        assertEquals(TocHostEffectKind.ExportFailure, model.state.value.pending!!.kind)
        assertNotNull(model.state.value.error)
        model.delivered(model.state.value.pending!!.nonce)
        repo.fail = false
        model.effect(TocHostEffectKind.PickJson)
        val retry = model.state.value.pending!!
        model.delivered(retry.nonce)
        model.picked(retry.requestCode, "another")
        runCurrent()
        assertEquals(listOf("another" to false), repo.exports)
    }
}
