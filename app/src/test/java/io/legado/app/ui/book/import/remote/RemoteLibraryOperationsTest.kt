package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import io.legado.app.utils.GSON
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLibraryOperationsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repository : RemoteLibraryRepository {
        val imports = mutableListOf<String>(); var failure: Exception? = null
        val rows = listOf(RemoteLibraryEntry("a", "a.txt", "a", 1, 1, "txt", false), RemoteLibraryEntry("b", "b.txt", "b", 1, 2, "txt", false), RemoteLibraryEntry("archive", "archive.zip", "archive", 3, 3, "zip", true))
        override suspend fun connect() = RemoteLibraryConnection("connection", "root", true, null)
        override suspend fun list(connection: RemoteLibraryConnection, path: String?) = rows
        override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) { imports += entry.id; failure?.let { throw it } }
        override suspend fun close() {}
    }
    private class Reading : RemoteLibraryReadingRepository {
        var storage = true; val storageWrites = mutableListOf<String>(); val archiveImports = mutableListOf<Pair<String, String>>()
        var target: RemoteLibraryReadTarget = RemoteLibraryReadTarget.Open("book")
        override suspend fun storageConfigured() = storage
        override suspend fun storageHelp() = "Synthetic help"
        override suspend fun storageUri(value: String) { storageWrites += value; storage = true }
        override suspend fun prepare(entry: RemoteLibraryEntry) = target
        override suspend fun chooseArchive(uri: String, name: String) = RemoteLibraryReadTarget.ImportArchive(uri, name)
        override suspend fun importArchive(uri: String, name: String): String { archiveImports += uri to name; return "imported" }
        override suspend fun readBook(id: String) = Book(bookUrl = id, name = "fresh")
    }
    private class Drafts : RemoteLibraryDraftRepository {
        var value = RemoteLibraryDraft(); var failOpen = false; var failCheckpoint = false; var failCompletion = false
        override suspend fun open(session: String): RemoteLibraryDraft { if (failOpen) error("load failure"); return value }
        override suspend fun write(session: String, draft: RemoteLibraryDraft) {
            if (failCheckpoint && draft.task?.completed?.isNotEmpty() == true) error("checkpoint failure")
            if (failCompletion && draft.task == null && draft.rows.any { it.id == "a" && it.onShelf }) error("completion failure")
            if (draft.revision >= value.revision) value = draft
        }
        override suspend fun release(session: String) {}
    }
    private inner class Fixture(val repository: Repository = Repository(), val reading: Reading = Reading(), val drafts: Drafts = Drafts(), val saved: SavedStateHandle = SavedStateHandle()) {
        val vm = RemoteLibraryViewModel(repository, reading, drafts, saved); val store = ViewModelStore().apply { put("remote", vm) }; fun close() { store.clear() }
    }
    @Test fun selectedImportsMarkRowsOnlyAfterAcceptedWriteAndClearSelectionWithoutImportingExistingRows() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.selectAll(true); runCurrent(); f.vm.importSelected(); runCurrent()
            assertEquals(listOf("b", "a"), f.repository.imports); assertTrue(f.vm.state.value.draft!!.selected.isEmpty()); assertNull(f.vm.state.value.draft!!.task)
            assertTrue(f.vm.state.value.draft!!.rows.all { it.onShelf }); assertEquals(0, f.vm.state.value.checkableCount)
        } finally { f.close() }
    }
    @Test fun acceptedCheckpointFailureRetriesOnlyReceiptThenExplicitlyImportsRemainingIds() = runTest(dispatcher) {
        val f = Fixture(drafts = Drafts().apply { failCheckpoint = true })
        try { runCurrent(); f.vm.selectAll(true); runCurrent(); f.vm.importSelected(); runCurrent(); assertTrue(f.vm.state.value.pendingCommit); assertEquals(listOf("b"), f.repository.imports)
            f.drafts.failCheckpoint = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.pendingCommit); assertTrue(f.vm.state.value.interrupted); assertEquals(listOf("b"), f.drafts.value.task!!.completed)
            f.vm.retryTaskConfirmed(); runCurrent(); assertEquals(listOf("b", "a"), f.repository.imports); assertNull(f.drafts.value.task)
        } finally { f.close() }
    }
    @Test fun completedTaskWriteFailureDoesNotRepeatAnyDownloadAndRecoverySkipsCommittedIds() = runTest(dispatcher) {
        val f = Fixture(drafts = Drafts().apply { failCompletion = true })
        try { runCurrent(); f.vm.selectAll(true); runCurrent(); f.vm.importSelected(); runCurrent(); assertTrue(f.vm.state.value.pendingCommit); assertEquals(2, f.repository.imports.size)
            f.drafts.failCompletion = false; f.vm.retry(); runCurrent(); assertEquals(2, f.repository.imports.size); assertNull(f.drafts.value.task)
            val restored = Fixture(drafts = Drafts().apply { value = RemoteLibraryDraft(8, rows = f.repository.rows, task = RemoteLibraryTask("task", RemoteLibraryTaskKind.ImportBooks, listOf("a", "b"), completed = listOf("a"))) })
            try { runCurrent(); assertTrue(restored.repository.imports.isEmpty()); restored.vm.retryTaskConfirmed(); runCurrent(); assertEquals(listOf("b"), restored.repository.imports) } finally { restored.close() }
        } finally { f.close() }
    }
    @Test fun archiveChoiceAndImportRequireExplicitConfirmationThenQueueOneFreshReadingReceipt() = runTest(dispatcher) {
        val f = Fixture(reading = Reading().apply { target = RemoteLibraryReadTarget.ChooseArchive("content://archive", listOf("a.txt", "b.txt")) })
        try { runCurrent(); f.vm.read("archive"); runCurrent(); assertTrue(f.reading.archiveImports.isEmpty()); assertEquals(RemoteLibraryPrompt.ChooseArchive, f.drafts.value.confirmation!!.kind)
            f.vm.archiveChoice("missing"); runCurrent(); assertEquals(RemoteLibraryPrompt.ChooseArchive, f.drafts.value.confirmation!!.kind)
            f.vm.archiveChoice("b.txt"); runCurrent(); assertTrue(f.reading.archiveImports.isEmpty()); assertEquals(RemoteLibraryPrompt.ImportArchive, f.drafts.value.confirmation!!.kind)
            f.vm.confirm(); runCurrent(); assertEquals(listOf("content://archive" to "b.txt"), f.reading.archiveImports)
            val receipt = f.drafts.value.effects.single(); assertEquals(RemoteLibraryEffect.OpenBook, receipt.effect); assertEquals("imported", receipt.bookId); assertTrue(f.vm.consumeEffect(receipt.id)); assertFalse(f.vm.consumeEffect(receipt.id))
        } finally { f.close() }
    }
    @Test fun earlyPickerWaitsForPrivateLoadRejectsStaleNonceAndCancellationClosesInitialSetupOnly() = runTest(dispatcher) {
        val drafts = Drafts().apply { failOpen = true; value = RemoteLibraryDraft(8, storageTicket = "owned") }; val f = Fixture(drafts = drafts)
        try { f.vm.storagePicked("owned", "content://synthetic/tree"); runCurrent(); assertTrue(f.reading.storageWrites.isEmpty())
            drafts.failOpen = false; f.vm.retry(); runCurrent(); assertEquals(listOf("content://synthetic/tree"), f.reading.storageWrites); assertNull(f.drafts.value.storageTicket)
            f.vm.storagePicked("stale", "content://stale"); runCurrent(); assertEquals(1, f.reading.storageWrites.size)
            val initial = Fixture(reading = Reading().apply { storage = false })
            try { runCurrent(); initial.vm.confirm(); runCurrent(); val id = initial.vm.storageTicket()!!; assertTrue(initial.vm.consumeEffect(id)); initial.vm.storagePicked(id, null); runCurrent(); assertEquals(RemoteLibraryEffect.Close, initial.vm.state.value.draft!!.effects.single().effect) } finally { initial.close() }
        } finally { f.close() }
    }
    @Test fun permissionFailureClearsSelectionAndOffersOwnedPickerWithoutAutomaticallyRepeatingImport() = runTest(dispatcher) {
        val f = Fixture(repository = Repository().apply { failure = SecurityException("synthetic permission") })
        try { runCurrent(); f.vm.selectAll(true); runCurrent(); f.vm.importSelected(); runCurrent(); assertTrue(f.vm.state.value.interrupted); assertTrue(f.drafts.value.selected.isEmpty())
            val id = f.vm.storageTicket()!!; assertEquals(RemoteLibraryEffect.PickStorage, f.drafts.value.effects.first().effect); assertTrue(f.vm.consumeEffect(id))
            f.vm.storagePicked(id, null); runCurrent(); assertEquals(1, f.repository.imports.size); assertTrue(f.vm.state.value.interrupted); assertTrue(f.vm.state.value.draft!!.effects.none { it.effect == RemoteLibraryEffect.Close })
        } finally { f.close() }
    }
    @Test fun actualIoCancellationAfterCompletedCheckpointPreservesDiskReceiptAndRestoreNeverRepeatsImport() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("remote-import-checkpoint").toFile(); val file = File(directory, "private-draft.json")
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>(); val latch = CountDownLatch(1); var imports = 0
        val store = object : RemoteLibraryStore {
            override suspend fun connect() = RemoteLibraryConnection("connection", "root", true, null)
            override suspend fun list(connection: RemoteLibraryConnection, path: String) = listOf(RemoteBook("a.txt", "a", 1, 1, "txt", false))
            override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) { imports++ }
            override suspend fun importWithReceipt(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry, accepted: suspend () -> Unit) {
                withContext(NonCancellable) { import(connection, entry); accepted() }
            }
            override fun close() {}
        }
        val drafts = object : RemoteLibraryDraftRepository {
            override suspend fun open(session: String) = withContext(Dispatchers.IO) { if (file.exists()) file.reader().use { GSON.fromJson(it, RemoteLibraryDraft::class.java) } else RemoteLibraryDraft() }
            override suspend fun write(session: String, draft: RemoteLibraryDraft): Unit = withContext(Dispatchers.IO + NonCancellable) {
                val previous = if (file.exists()) file.reader().use { GSON.fromJson(it, RemoteLibraryDraft::class.java) } else null
                if (previous == null || draft.revision >= previous.revision) file.writeText(GSON.toJson(draft))
                if (draft.task?.completed?.isNotEmpty() == true) { entered.complete(Unit); check(latch.await(10, TimeUnit.SECONDS)); released.complete(Unit) }
            }
            override suspend fun release(session: String) {}
        }
        val repository = DefaultRemoteLibraryRepository(store); val vm = RemoteLibraryViewModel(repository, Reading(), drafts, SavedStateHandle()); val owner = ViewModelStore().apply { put("remote", vm) }
        try {
            vm.state.first { !it.loading }; vm.selectAll(true); runCurrent(); vm.importSelected(); runCurrent(); entered.await()
            vm.stop(); latch.countDown(); released.await(); vm.flush(); runCurrent()
            val disk = drafts.open(vm.session); assertEquals(listOf("a"), disk.task!!.completed); assertEquals(1, imports)
            val restored = RemoteLibraryViewModel(repository, Reading(), drafts, SavedStateHandle(mapOf("remoteLibrarySession" to vm.session))); val restoredOwner = ViewModelStore().apply { put("restored", restored) }
            try { restored.state.first { !it.loading }; assertTrue(restored.state.value.interrupted); assertEquals(1, imports)
                restored.retryTaskConfirmed(); restored.state.first { !it.busy && it.draft?.task == null }; assertEquals(1, imports)
            } finally { restoredOwner.clear() }
        } finally { latch.countDown(); owner.clear(); directory.deleteRecursively() }
    }

    @Test fun filteringKeepsHiddenSelectionsInTheActualImportPayloadAndSuccessClearsAllOfThem() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.selectAll(true); runCurrent(); f.vm.query("a.txt"); runCurrent()
            assertEquals(listOf("a"), f.vm.state.value.visibleSelection); assertEquals(listOf("b", "a"), f.vm.state.value.selection)
            f.vm.importSelected(); runCurrent(); assertEquals(listOf("b", "a"), f.repository.imports); assertTrue(f.vm.state.value.selection.isEmpty())
        } finally { f.close() }
    }

}
