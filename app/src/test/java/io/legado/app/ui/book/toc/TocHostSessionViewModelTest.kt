package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TocHostSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<TocHostSessionViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : TocHostSessionRepository {
        val disk = mutableMapOf<String, TocHostSession>()
        var readGate: CompletableDeferred<Unit>? = null
        var fail = false
        var holdQuery: String? = null
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun read(session: String): TocHostSession? {
            val value = disk[session]
            withContext(NonCancellable) { readGate?.await() }
            if (fail) error("Disk unavailable")
            return value
        }

        override suspend fun write(session: String, value: TocHostSession) {
            if (value.query == holdQuery) withContext(NonCancellable) { writeGate?.await() }
            if (fail) error("Disk unavailable")
            if ((disk[session]?.revision ?: -1) <= value.revision) disk[session] = value
        }

        override suspend fun release(session: String) {
            disk.remove(session)
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        TocHostSessionViewModel(repo, saved).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun controlsIgnoreInputUntilInitialRequestIsPersistedAndReady() = test {
        val repo = Fake()
        repo.readGate = CompletableDeferred()
        val model = model(repo)
        model.bind("book")
        runCurrent()
        model.query("Ignored")
        model.tab(2)
        model.search(true)
        assertFalse(model.state.value.ready)
        repo.readGate!!.complete(Unit)
        runCurrent()
        assertEquals("", model.state.value.query)
        assertEquals(0, model.state.value.tab)
        assertTrue(model.state.value.ready)
        assertEquals("book", repo.disk.values.single().bookUrl)
    }

    @Test
    fun fullLargeUrlAndQueryRestoreExactlyWithOnlyTinyIdsAndPresentationInSavedState() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val url = "URL".repeat(100000)
        val query = "Query".repeat(100000)
        val first = model(repo, saved)
        first.bind(url)
        runCurrent()
        first.query(query, 17, 25)
        first.tab(2)
        first.search(true)
        first.menu(true)
        runCurrent()
        first.stop()
        assertTrue(
            saved.keys().all {
                (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false
            }
        )
        val restored = model(repo, copy(saved))
        restored.bind(url)
        runCurrent()
        assertEquals(query, restored.state.value.query)
        assertEquals(2, restored.state.value.tab)
        assertTrue(restored.state.value.searchOpen)
        assertTrue(restored.state.value.menuOpen)
        assertEquals(17, restored.state.value.selectionStart)
        assertEquals(25, restored.state.value.selectionEnd)
    }

    @Test
    fun changingBookOwnerClearsQueryAndPresentationWithoutDeliveringOldRead() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.bind("first")
        runCurrent()
        first.query("Old")
        first.tab(2)
        first.search(true)
        runCurrent()
        first.stop()
        repo.readGate = CompletableDeferred()
        val restored = model(repo, copy(saved))
        restored.bind("first")
        runCurrent()
        restored.bind("new")
        runCurrent()
        repo.readGate!!.complete(Unit)
        runCurrent()
        assertEquals("new", restored.state.value.bookUrl)
        assertEquals("", restored.state.value.query)
        assertEquals(0, restored.state.value.tab)
        assertFalse(restored.state.value.searchOpen)
    }

    @Test
    fun oldNonCooperativeWriteCannotOverwriteNewQueryWhenItReturnsLate() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind("book")
        runCurrent()
        repo.holdQuery = "Old"
        repo.writeGate = CompletableDeferred()
        model.query("Old")
        runCurrent()
        model.query("Latest")
        runCurrent()
        repo.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals("Latest", repo.disk.values.single().query)
        assertEquals("Latest", model.state.value.query)
    }

    @Test
    fun initialAndSubsequentDiskFailuresHaveExplicitRetryAndKeepEditableDraft() = test {
        val repo = Fake()
        repo.fail = true
        val model = model(repo)
        model.bind("book")
        runCurrent()
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.ready)
        repo.fail = false
        model.retry()
        runCurrent()
        assertTrue(model.state.value.ready)
        repo.fail = true
        model.query("Draft")
        runCurrent()
        assertNotNull(model.state.value.error)
        assertEquals("Draft", model.state.value.query)
        repo.fail = false
        model.retry()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals("Draft", repo.disk.values.single().query)
    }

    @Test
    fun savedRevisionLagUsesCurrentDiskBaselineAndDuplicateBindDoesNotReloadDraft() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        first.bind("book")
        runCurrent()
        val oldSaved = copy(saved)
        first.query("Latest")
        runCurrent()
        val revision = repo.disk.values.single().revision
        first.stop()
        val restored = model(repo, oldSaved)
        restored.bind("book")
        runCurrent()
        assertEquals("Latest", restored.state.value.query)
        assertTrue(repo.disk.values.single().revision > revision)
        restored.query("Unsaved locally")
        restored.bind("book")
        assertEquals("Unsaved locally", restored.state.value.query)
    }

    @Test
    fun querySelectionIsClampedAndCursorOnlyChangeDoesNotPersistLargeQueryAgain() = test {
        val repo = Fake()
        val model = model(repo)
        model.bind("book")
        runCurrent()
        model.query("Text", -5, 99)
        runCurrent()
        assertEquals(0, model.state.value.selectionStart)
        assertEquals(4, model.state.value.selectionEnd)
        val revision = repo.disk.values.single().revision
        model.select(1, 2)
        runCurrent()
        assertEquals(revision, repo.disk.values.single().revision)
    }

    @Test
    fun tabSwitchPreservesQueryClosesMenuAndInvalidIndexesAreIgnored() = test {
        val model = model(Fake())
        model.bind("book")
        runCurrent()
        model.query("Find")
        model.menu(true)
        model.tab(1)
        model.tab(9)
        assertEquals(1, model.state.value.tab)
        assertEquals("Find", model.state.value.query)
        assertFalse(model.state.value.menuOpen)
        model.search(true)
        model.search(false)
        assertEquals("Find", model.state.value.query)
    }

    @Test
    fun stoppedNonCooperativeInitialReadCannotPublishReady() = test {
        val repo = Fake()
        repo.readGate = CompletableDeferred()
        val model = model(repo)
        model.bind("book")
        runCurrent()
        model.stop()
        repo.readGate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.ready)
        assertTrue(repo.disk.isEmpty())
    }
}
