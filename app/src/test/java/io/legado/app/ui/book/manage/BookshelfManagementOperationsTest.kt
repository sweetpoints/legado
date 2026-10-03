package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.legado.app.utils.GSON

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfManagementOperationsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repo : BookshelfManagementRepository {
        val groups = mutableListOf<Triple<List<String>, Long, ShelfGroupMutation>>()
        override fun observe(groupId: Long, query: String) = flowOf(ManagedShelfSnapshot(listOf("a", "b").map { ManagedShelfBook(it, it, "", "", false, 1, "Group", 1, true) }, emptyList(), groupId, null, 3, false))
        override suspend fun group(ids: List<String>, group: Long, mode: ShelfGroupMutation) { groups += Triple(ids, group, mode) }
        override suspend fun canUpdate(ids: List<String>, enabled: Boolean) = Unit
        override suspend fun order(assignments: List<ShelfOrderAssignment>, resetAll: Boolean) = Unit
        override suspend fun openTitle(value: Boolean) = Unit
    }
    private class Maintenance : BookshelfMaintenanceRepository {
        var deletes = 0; var clears = 0; var exports = 0; var gate: CompletableDeferred<Unit>? = null; var export: File? = null
        override suspend fun delete(ids: List<String>, original: Boolean): Int { deletes++; gate?.await(); return ids.size }
        override suspend fun clearCache(ids: List<String>): Int { clears++; return ids.size }
        override suspend fun exportSources(): File { exports++; return requireNotNull(export) }
        override suspend fun books(ids: List<String>) = ids.map { Book(bookUrl = it, name = "latest-$it") }
        override suspend fun updateCandidates(ids: List<String>) = books(ids)
        override suspend fun createTasks(ids: List<String>, cron: String) = ids.size
    }
    private open class Drafts : BookshelfManagementDraftRepository {
        var value = BookshelfManagementDraft(); var failOpen = false; var failCompletion = false
        override suspend fun open(session: String): BookshelfManagementDraft { if (failOpen) error("failed open"); return value }
        override suspend fun write(session: String, draft: BookshelfManagementDraft) { if (failCompletion && draft.operation == null && draft.effects.isNotEmpty()) error("failed completion"); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) = Unit
    }
    private inner class Fixture(val repo: Repo = Repo(), val maintenance: Maintenance = Maintenance(), val drafts: Drafts = Drafts(),
        val saved: SavedStateHandle = SavedStateHandle(), source: BookshelfSourceRepository? = null) {
        val vm = BookshelfManagementViewModel(repo, drafts, saved, maintenance = maintenance, sources = source)
        val owner = ViewModelStore().apply { put("vm", vm) }; fun close() { owner.clear() }
    }
    @Test fun preparedDeleteIsDurableBeforeItsIoMutationAndBusyBlocksChangingSelection() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(maintenance = Maintenance().apply { this.gate = gate })
        try { runCurrent(); f.vm.toggle("a"); runCurrent(); f.vm.confirmAction(ShelfManagementAction.Delete); runCurrent(); f.vm.deleteOriginal(true); f.vm.executeConfirmed(); runCurrent()
            assertEquals(listOf("a"), f.drafts.value.operation!!.ids); assertTrue(f.drafts.value.operation!!.deleteOriginal); assertTrue(f.vm.state.value.busy)
            f.vm.toggle("b"); assertEquals(listOf("a"), f.vm.state.value.visibleSelection); gate.complete(Unit); runCurrent()
            assertNull(f.drafts.value.operation); assertEquals(1, f.maintenance.deletes); assertFalse(f.vm.state.value.busy)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun acceptedCompletionWriteFailureRetriesOnlyTheReceiptAndNeverRepeatsTheMutation() = runTest(dispatcher) {
        val f = Fixture(drafts = Drafts().apply { failCompletion = true })
        try { runCurrent(); f.vm.execute(ShelfManagementAction.ClearCache, listOf("a")); runCurrent(); assertTrue(f.vm.state.value.pendingCommit)
            assertTrue(f.vm.state.value.draft!!.effects.isEmpty()); f.vm.execute(ShelfManagementAction.Delete, listOf("b")); runCurrent(); assertEquals(0, f.maintenance.deletes)
            f.drafts.failCompletion = false; f.vm.retry(); runCurrent(); assertFalse(f.vm.state.value.pendingCommit); assertEquals(1, f.maintenance.clears)
            assertEquals(R.string.clear_cache_success, f.drafts.value.effects.single().resource)
        } finally { f.close() }
    }
    @Test fun restoredPreparedDeletionCannotAutomaticallyRepeatItsSideEffect() = runTest(dispatcher) {
        val drafts = Drafts().apply { value = BookshelfManagementDraft(revision = 8, operation = ShelfManagementOperation("accepted", ShelfManagementAction.Delete, listOf("a"))) }; val f = Fixture(drafts = drafts)
        try { runCurrent(); assertTrue(f.vm.state.value.interrupted); assertEquals(0, f.maintenance.deletes)
            f.vm.retry(); runCurrent(); assertEquals(0, f.maintenance.deletes); f.vm.retryOperationConfirmed(); runCurrent(); assertEquals(1, f.maintenance.deletes); assertNull(f.drafts.value.operation)
        } finally { f.close() }
    }
    @Test fun effectConsumptionIsSavedBeforeDeliveryAndRestorePrunesOnlyThroughTheConsumedReceipt() = runTest(dispatcher) {
        val first = ShelfManagementReceipt("first", ShelfManagementEffect.Toast, R.string.success); val second = first.copy(id = "second")
        val drafts = Drafts().apply { value = BookshelfManagementDraft(8, effects = listOf(first, second)) }; val f = Fixture(drafts = drafts)
        try { runCurrent(); assertTrue(f.vm.consumeEffect("first")); assertFalse(f.vm.consumeEffect("first")); assertEquals("first", f.saved.get<String>("shelfManageConsumed"))
            // Restore from the older disk before the consumption writer gets a chance to run.
            val restored = Fixture(drafts = Drafts().apply { value = BookshelfManagementDraft(8, effects = listOf(first, second)) }, saved = SavedStateHandle(mapOf("shelfManageConsumed" to "first")))
            try { runCurrent(); assertEquals(listOf(second), restored.vm.state.value.draft!!.effects); assertTrue(restored.vm.consumeEffect("second")); assertFalse(restored.vm.consumeEffect("first")) } finally { restored.close() }
        } finally { f.close() }
    }
    @Test fun earlyOwnedGroupResultWaitsForPrivateInitializationAndKeepsTheOriginalTargetIds() = runTest(dispatcher) {
        val drafts = Drafts().apply { failOpen = true; value = BookshelfManagementDraft(8, selected = listOf("b"), groupRequest = ShelfGroupRequest("owned", listOf("a"), 1, ShelfGroupMutation.Add)) }; val f = Fixture(drafts = drafts)
        try { f.vm.groupPicked("owned", 4); runCurrent(); assertTrue(f.repo.groups.isEmpty()); drafts.failOpen = false; f.vm.retry(); runCurrent()
            assertEquals(listOf(Triple(listOf("a"), 4L, ShelfGroupMutation.Add)), f.repo.groups); assertNull(f.drafts.value.groupRequest)
            f.vm.groupPicked("owned", 8); runCurrent(); assertEquals(1, f.repo.groups.size)
        } finally { f.close() }
    }
    @Test fun staleGroupNonceCannotChangeNewRequestAndPrivateConfirmationKeepsCronSelection() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.toggle("a"); runCurrent(); f.vm.pickGroup(ShelfGroupMutation.Remove); runCurrent()
            val id = f.drafts.value.groupRequest!!.id; assertTrue(f.vm.consumeEffect(id)); runCurrent()
            f.vm.groupPicked("older", 8); runCurrent(); assertTrue(f.repo.groups.isEmpty()); f.vm.groupPicked(id, 4); runCurrent(); assertEquals(ShelfGroupMutation.Remove, f.repo.groups.single().third)
            f.vm.confirmAction(ShelfManagementAction.CreateTasks); f.vm.confirmationText("invalid schedule", 2, 6); f.vm.executeConfirmed(); runCurrent()
            assertTrue(f.vm.state.value.invalidCron); assertEquals(2, f.drafts.value.confirmation!!.selectionStart); assertEquals(6, f.drafts.value.confirmation!!.selectionEnd); assertNull(f.drafts.value.operation)
        } finally { f.close() }
    }
    @Test fun exportCompletionRetryKeepsTheSamePreparedFileAndRegistersPrivateOwnership() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("shelf-vm-export").toFile(); val export = File(directory, "synthetic.json").apply { writeText("synthetic") }
        val f = Fixture(maintenance = Maintenance().apply { this.export = export }, drafts = Drafts().apply { failCompletion = true })
        try { runCurrent(); f.vm.execute(ShelfManagementAction.ExportSources); runCurrent(); assertTrue(f.vm.state.value.pendingCommit); assertTrue(export.exists())
            f.drafts.failCompletion = false; f.vm.retry(); runCurrent(); assertEquals(1, f.maintenance.exports); assertEquals(export.absolutePath, f.drafts.value.effects.single().file)
            assertEquals(listOf(export.absolutePath), f.drafts.value.exports)
        } finally { f.close(); directory.deleteRecursively() }
    }
    @Test fun canceledSourceNonCooperativeLateFailureCannotPublishAnErrorOrReceipt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val source = object : BookshelfSourceRepository {
            override fun change(ids: List<String>, sourceId: String): Flow<ShelfSourceEvent> = flow {
                emit(ShelfSourceEvent.Progress(1, 1)); withContext(NonCancellable) { gate.await(); error("late failure") }
            }
        }; val f = Fixture(source = source)
        try { runCurrent(); f.vm.execute(ShelfManagementAction.ChangeSource, listOf("a"), sourceId = "source"); runCurrent()
            assertTrue(f.vm.state.value.busy); f.vm.cancelOperation(); gate.complete(Unit); runCurrent()
            assertFalse(f.vm.state.value.busy); assertNull(f.vm.state.value.error); assertNull(f.vm.state.value.draft!!.operation); assertTrue(f.drafts.value.effects.isEmpty())
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun diskAcceptedExportOwnsItsFileBeforeCanceledIoReturnAndRestoresWithoutRepeatingExport() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("shelf-accepted-export").toFile()
        val export = File(directory, "synthetic.json").apply { writeText("complete synthetic source export") }
        val draftFile = File(directory, "draft.json"); val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>(); val gate = CountDownLatch(1)
        val drafts = object : Drafts() {
            override suspend fun open(session: String): BookshelfManagementDraft = withContext(Dispatchers.IO) {
                if (draftFile.exists()) draftFile.reader().use { GSON.fromJson(it, BookshelfManagementDraft::class.java) } else BookshelfManagementDraft()
            }
            override suspend fun write(session: String, draft: BookshelfManagementDraft) = withContext(Dispatchers.IO + NonCancellable) {
                super.write(session, draft); draftFile.writeText(GSON.toJson(value))
                if (draft.operation == null && draft.effects.any { it.effect == ShelfManagementEffect.ExportSources }) {
                    entered.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)); released.complete(Unit)
                }
            }
        }
        val maintenance = Maintenance().apply { this.export = export }; val f = Fixture(maintenance = maintenance, drafts = drafts)
        try {
            f.vm.state.first { !it.loading }
            f.vm.execute(ShelfManagementAction.ExportSources); runCurrent(); entered.await()
            // The IO write has reached disk, but its return is gated while the owner is canceled.
            f.vm.stop(); gate.countDown(); released.await(); f.vm.flush(); runCurrent()
            assertTrue(export.exists()); assertEquals("complete synthetic source export", export.readText())
            val restored = Fixture(maintenance = maintenance, drafts = drafts, saved = SavedStateHandle(mapOf("shelfManageSession" to f.vm.session)))
            try {
                restored.vm.state.first { !it.loading }
                assertEquals(export.absolutePath, restored.vm.state.value.draft!!.effects.single().file)
                assertTrue(export.exists()); assertEquals(1, maintenance.exports); assertFalse(restored.vm.state.value.interrupted)
            } finally { restored.close() }
        } finally { gate.countDown(); f.close(); directory.deleteRecursively() }
    }

    @Test fun canceledGroupTicketRestoresWithoutOpeningDialogAndStaleCancelKeepsNewTicket() = runTest(dispatcher) {
        val old = ShelfGroupRequest("old", listOf("a"), 1, ShelfGroupMutation.Replace)
        val f = Fixture(drafts = Drafts().apply { value = BookshelfManagementDraft(8, groupRequest = old) })
        try { runCurrent(); f.vm.cancelGroup("stale"); assertEquals("old", f.vm.state.value.draft!!.groupRequest!!.id)
            f.vm.cancelGroup("old")
            val restored = Fixture(drafts = Drafts().apply { value = BookshelfManagementDraft(8, groupRequest = old) }, saved = SavedStateHandle(mapOf("shelfGroupConsumed" to "old")))
            try { runCurrent(); assertNull(restored.vm.state.value.draft!!.groupRequest); restored.vm.groupPicked("old", 8); runCurrent(); assertTrue(restored.repo.groups.isEmpty()) } finally { restored.close() }
        } finally { f.close() }
    }
    @Test fun earlyExportResultWaitsForLoadRejectsStaleTicketAndKeepsLargeUriOutOfSavedState() = runTest(dispatcher) {
        val drafts = Drafts().apply { failOpen = true; value = BookshelfManagementDraft(8, exportTicket = "owned") }; val f = Fixture(drafts = drafts)
        val uri = "content://synthetic/" + "large".repeat(100000)
        try { f.vm.exportResult("owned", uri); runCurrent(); assertTrue(f.vm.state.value.failed)
            drafts.failOpen = false; f.vm.retry(); runCurrent(); assertEquals(uri, f.drafts.value.exportResult); assertNull(f.drafts.value.exportTicket)
            f.vm.exportResult("old", "content://stale"); runCurrent(); assertEquals(uri, f.drafts.value.exportResult)
            f.saved.keys().forEach { assertFalse(f.saved.get<Any>(it).toString().contains(uri)) }; f.vm.closeExportResult(); runCurrent(); assertNull(f.drafts.value.exportResult)
        } finally { f.close() }
    }
    @Test fun rowDeleteConfirmationPreservesLocalOnlyControlAndReadsCurrentPreference() = runTest(dispatcher) {
        val f = Fixture()
        try { runCurrent(); f.vm.confirmAction(ShelfManagementAction.Delete, listOf("a"), showOriginal = false); runCurrent()
            assertFalse(f.vm.state.value.draft!!.confirmation!!.showOriginal); f.vm.dismissConfirmation(); runCurrent()
            f.vm.confirmAction(ShelfManagementAction.Delete, listOf("a")); runCurrent(); assertTrue(f.vm.state.value.draft!!.confirmation!!.showOriginal)
        } finally { f.close() }
    }

}
