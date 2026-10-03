package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun row(id: String, type: String = "txt", shelf: Boolean = false, modified: Long = 1) = RemoteLibraryEntry(id, id, "https://example.invalid/$id", 1, modified, type, shelf)
    private inner class Repository : RemoteLibraryRepository {
        var rows = listOf(row("folder", "folder"), row("book2", modified = 2), row("book10", modified = 10), row("existing", shelf = true))
        val paths = mutableListOf<String?>(); var connectCalls = 0; var fail = false; var late: CompletableDeferred<Unit>? = null
        override suspend fun connect(): RemoteLibraryConnection { connectCalls++; return RemoteLibraryConnection("connection-$connectCalls", "root", true, null) }
        override suspend fun list(connection: RemoteLibraryConnection, path: String?): List<RemoteLibraryEntry> {
            paths += path; late?.let { withContext(NonCancellable) { it.await(); error("late list failure") } }; if (fail) error("list failure"); return rows
        }
        override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) {}
        override suspend fun close() {}
    }
    private class Reading : RemoteLibraryReadingRepository {
        var storage = true; var initialHelp = false
        override suspend fun showHelpInitially() = initialHelp
        override suspend fun storageConfigured() = storage
        override suspend fun storageHelp() = "Synthetic help"
        override suspend fun storageUri(value: String) { storage = true }
        override suspend fun prepare(entry: RemoteLibraryEntry) = RemoteLibraryReadTarget.None
        override suspend fun chooseArchive(uri: String, name: String) = RemoteLibraryReadTarget.None
        override suspend fun importArchive(uri: String, name: String): String? = null
        override suspend fun readBook(id: String): Book? = null
    }
    private class Drafts : RemoteLibraryDraftRepository {
        var value = RemoteLibraryDraft(); var failOpen = false; var failWrite = false
        override suspend fun open(session: String): RemoteLibraryDraft { if (failOpen) error("open failure"); return value }
        override suspend fun write(session: String, draft: RemoteLibraryDraft) { if (failWrite) error("write failure"); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) {}
    }
    private inner class Fixture(val repository: Repository = Repository(), val reading: Reading = Reading(), val drafts: Drafts = Drafts(), val saved: SavedStateHandle = SavedStateHandle()) {
        val vm = RemoteLibraryViewModel(repository, reading, drafts, saved); val owner = ViewModelStore().apply { put("remote", vm) }
        fun close() { owner.clear() }
    }
    @Test fun selectionExcludesDirectoriesAndExistingBooksAndFilterPayloadPreservesVisibleOrder() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("folder"); f.vm.toggle("existing"); assertTrue(f.vm.state.value.visibleSelection.isEmpty())
            f.vm.selectAll(true); runCurrent(); assertEquals(listOf("book10", "book2"), f.vm.state.value.visibleSelection)
            f.vm.query("book2"); runCurrent(); assertEquals(listOf("book2"), f.vm.state.value.visibleSelection); f.vm.inverse(); runCurrent(); assertTrue(f.vm.state.value.visibleSelection.isEmpty())
            f.vm.query(""); runCurrent(); assertEquals(listOf("book10"), f.vm.state.value.visibleSelection); f.vm.selectAll(false); runCurrent(); assertTrue(f.drafts.value.selected.isEmpty())
        } finally { f.close() }
    }
    @Test fun sameSortReversesOtherSortStartsAscendingAndEveryPathRefreshClearsSelection() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("book2"); f.vm.sort(RemoteLibrarySort.Name); runCurrent(); assertTrue(f.vm.state.value.draft!!.ascending)
            assertEquals(listOf("folder", "book2", "book10", "existing"), f.vm.state.value.visible.map { it.id }); assertTrue(f.vm.state.value.visibleSelection.isEmpty())
            f.vm.sort(RemoteLibrarySort.Name); runCurrent(); assertFalse(f.vm.state.value.draft!!.ascending)
            f.vm.openDirectory("folder"); runCurrent(); assertEquals("https://example.invalid/folder", f.repository.paths.last()); assertEquals("books/folder/", f.vm.state.value.path)
            assertTrue(f.vm.goBackDirectory()); runCurrent(); assertNull(f.repository.paths.last()); assertFalse(f.vm.goBackDirectory())
        } finally { f.close() }
    }
    @Test fun restoredLargePrivateStateKeepsOnlySessionInBundleAndRevisionExceedsDiskAfterReboot() = runTest(dispatcher) {
        val text = "large".repeat(100000); val drafts = Drafts().apply { value = RemoteLibraryDraft(revision = System.nanoTime() + 1_000_000_000_000L, query = text, selected = listOf(text)) }
        val previous = drafts.value.revision; val f = Fixture(drafts = drafts)
        try { runCurrent(); assertEquals(text, f.vm.state.value.draft!!.query); f.saved.keys().forEach { assertFalse(f.saved.get<Any>(it).toString().contains(text)) }
            f.vm.query("next"); runCurrent(); assertTrue(drafts.value.revision > previous)
        } finally { f.close() }
    }
    @Test fun storageHelpWaitsWithoutConnectingAndInitOrListFailureHasExplicitRetry() = runTest(dispatcher) {
        val f = Fixture(reading = Reading().apply { storage = false })
        try { runCurrent(); assertEquals(0, f.repository.connectCalls); assertEquals(RemoteLibraryPrompt.StorageHelp, f.vm.state.value.draft!!.confirmation!!.kind); assertEquals("Synthetic help", f.vm.state.value.draft!!.confirmation!!.help)
            f.reading.storage = true; f.repository.fail = true; f.vm.retry(); runCurrent(); assertTrue(f.vm.state.value.failed); f.vm.toggle("book2"); assertTrue(f.drafts.value.selected.isEmpty())
            f.repository.fail = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.failed); assertEquals(4, f.vm.state.value.visible.size)
        } finally { f.close() }
    }
    @Test fun stopFencesNonCooperativeLateFailureAndWriteFailureBlocksFurtherEditingUntilRetry() = runTest(dispatcher) {
        val f = Fixture(); val late = CompletableDeferred<Unit>()
        try { runCurrent(); f.drafts.failWrite = true; f.vm.query("book"); runCurrent(); assertTrue(f.vm.state.value.writeFailed); f.vm.query("other"); assertEquals("book", f.vm.state.value.draft!!.query)
            f.drafts.failWrite = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.writeFailed)
            f.repository.late = late; f.vm.refresh(); runCurrent(); f.vm.stop(); val stopped = f.vm.state.value; late.complete(Unit); runCurrent(); assertEquals(stopped, f.vm.state.value)
        } finally { late.complete(Unit); f.close() }
    }
    @Test fun processRestoreOfAcceptedTaskNeverImportsAndConsumedReceiptCannotReplay() = runTest(dispatcher) {
        val first = RemoteLibraryReceipt("first", RemoteLibraryEffect.Toast); val second = first.copy(id = "second")
        val f = Fixture(drafts = Drafts().apply { value = RemoteLibraryDraft(8, task = RemoteLibraryTask("accepted", RemoteLibraryTaskKind.ImportBooks, listOf("book2")), effects = listOf(first, second)) }, saved = SavedStateHandle(mapOf("remoteLibraryConsumed" to "first")))
        try { runCurrent(); assertTrue(f.vm.state.value.interrupted); assertEquals(listOf(second), f.vm.state.value.draft!!.effects); assertFalse(f.vm.consumeEffect("first")); assertFalse(f.vm.consumeEffect("second"))
        } finally { f.close() }
    }
    @Test fun initialHelpIsDurableOnceAndFailedServerConfigurationCanOpenSettingsThenReconnect() = runTest(dispatcher) {
        val f = Fixture(reading = Reading().apply { initialHelp = true })
        try { runCurrent(); val help = f.vm.state.value.draft!!.effects.single(); assertEquals(RemoteLibraryEffect.Help, help.effect)
            assertTrue(f.vm.consumeEffect(help.id)); runCurrent(); f.vm.refresh(); runCurrent(); assertTrue(f.vm.state.value.draft!!.effects.isEmpty()); assertTrue(f.drafts.value.initialHelpChecked)
            f.repository.fail = true; f.vm.refresh(); runCurrent(); assertTrue(f.vm.state.value.failed)
            val failure = f.vm.state.value.draft!!.effects.single(); assertEquals(RemoteLibraryEffect.Toast, failure.effect); assertTrue(f.vm.consumeEffect(failure.id)); runCurrent()
            f.vm.menu(RemoteLibraryEffect.Servers); runCurrent(); val config = f.vm.state.value.draft!!.effects.single(); assertEquals(RemoteLibraryEffect.Servers, config.effect); assertTrue(f.vm.consumeEffect(config.id)); runCurrent()
            f.repository.fail = false; val before = f.repository.connectCalls; f.vm.serverChanged(); runCurrent(); assertFalse(f.vm.state.value.failed); assertTrue(f.repository.connectCalls > before)
        } finally { f.close() }
    }

}
