package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupOperationsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun clear() { Dispatchers.resetMain() }
    private class Drafts : BackupSettingsDraftRepository {
        var value = BackupSettingsDraft(); var failOpen = false; var failComplete = false; var released = false
        override suspend fun open(session: String): BackupSettingsDraft { check(!released); if (failOpen) error("open failed"); return value }
        override suspend fun write(session: String, draft: BackupSettingsDraft) { check(!released); if (failComplete && draft.task?.phase == BackupTaskPhase.Complete) error("receipt failed"); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) { released = true }
    }
    private class Settings : BackupSettingsRepository {
        val values = MutableStateFlow(BackupSettingsSnapshot()); val paths = mutableListOf<String?>()
        override fun observe(): Flow<BackupSettingsSnapshot> = values
        override suspend fun load() = values.value
        override suspend fun text(key: BackupSettingText, value: String) {}
        override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) {}
        override suspend fun automatic(value: AutoBackupSettings) {}
        override suspend fun path(value: String?) { paths += value; values.value = values.value.copy(backupPath = value) }
        override suspend fun localPassword(value: String) {}
        override suspend fun needsHelp() = false
    }
    private class Choices : BackupChoicesRepository {
        override suspend fun load(group: BackupChoiceGroup) = emptyList<BackupChoice>()
        override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean) = emptyList<BackupChoice>()
        override suspend fun save() {}
    }
    private class Operations : BackupOperationsRepository {
        val calls = mutableListOf<String>(); var writable = false; var failNames = false; var truncated = false
        var gate: CompletableDeferred<Unit>? = null; var lateError = false
        override suspend fun writableTree(path: String): Boolean { calls += "check:$path"; return writable }
        override suspend fun backup(path: String?, uploadWebDav: Boolean) { calls += "backup:$path:$uploadWebDav"; gate?.let { withContext(NonCancellable) { it.await(); if (lateError) error("late failure") } } }
        override suspend fun restoreFiles(): BackupRestoreFiles { calls += "names"; if (failNames) error("cloud unavailable"); return BackupRestoreFiles(listOf("backup-new", "backup-old"), truncated) }
        override suspend fun restoreWebDav(name: String) { calls += "remote:$name" }
        override suspend fun restoreLocal(uri: String) { calls += "local:$uri" }
        override suspend fun importOld(uri: String) { calls += "old:$uri" }
    }
    private class Lan : BackupLanRepository {
        val calls = mutableListOf<String>(); var closed = 0
        override suspend fun prepare(): BackupLanOffer { calls += "prepare"; return BackupLanOffer("offer", "/private/qr.png", Long.MAX_VALUE) }
        override suspend fun decode(qrText: String): BackupLanReceiveInfo { calls += "decode:$qrText"; return BackupLanReceiveInfo(1024, "device") }
        override fun receive(qrText: String): Flow<BackupLanReceivePhase> = flow { calls += "receive:$qrText"; BackupLanReceivePhase.entries.forEach { emit(it) } }
        override suspend fun close(id: String) { calls += "close:$id"; closed++ }
        override suspend fun closeAll() { calls += "close-all" }
    }
    private inner class Fixture(scope: CoroutineScope, val settings: Settings = Settings(), val drafts: Drafts = Drafts(), val operations: Operations = Operations(), val lan: Lan = Lan(),
        val saved: SavedStateHandle = SavedStateHandle()) {
        val model = BackupSettingsViewModel(settings, Choices(), drafts, SavedStateHandle())
        private val owner = ViewModelStore().apply { put("vm", model) }
        val controller = BackupOperationsController(scope, model, settings, operations, lan, saved)
        suspend fun close() { controller.release(); owner.clear() }
    }
    @Test fun defaultManualBackupKeepsLocalOnlyFlagPublishesDurableReceiptAndConsumesSuccessOnce() = runTest(dispatcher) {
        val f = Fixture(this)
        try { runCurrent(); f.controller.backup(); assertEquals(BackupPopup.Destination, f.controller.state.value.popup)
            f.controller.manualDestination(false); runCurrent(); assertEquals(listOf("backup:null:false"), f.operations.calls)
            assertEquals(BackupTaskPhase.Complete, f.drafts.value.task!!.phase); val id = f.controller.state.value.success!!
            assertTrue(f.controller.consumeSuccess(id)); assertFalse(f.controller.consumeSuccess(id)); assertNull(f.controller.state.value.success)
        } finally { f.close() }
    }
    @Test fun writableContentTreeIsUsedDirectlyWithoutPickerOrPreferenceRewrite() = runTest(dispatcher) {
        val settings = Settings().apply { values.value = values.value.copy(backupPath = "content://tree") }
        val ops = Operations().apply { writable = true }; val f = Fixture(this, settings, operations = ops)
        try { runCurrent(); f.controller.manualDestination(true); runCurrent()
            assertEquals(listOf("check:content://tree", "backup:content://tree:true"), ops.calls); assertTrue(settings.paths.isEmpty()); assertNull(f.controller.state.value.event)
        } finally { f.close() }
    }
    @Test fun unwritableTreeQueuesDirectoryPickerAndResultUsesCapturedDestinationFlagExactlyOnce() = runTest(dispatcher) {
        val settings = Settings().apply { values.value = values.value.copy(backupPath = "content://old") }; val f = Fixture(this, settings)
        try { runCurrent(); f.controller.manualDestination(false); runCurrent(); val event = f.controller.state.value.event!!
            assertEquals(BackupHostAction.BackupDirectory, event.action); assertTrue(f.controller.consumeEvent(event.id)); assertFalse(f.controller.consumeEvent(event.id))
            f.controller.result(BackupHostAction.BackupDirectory, "content://selected"); f.controller.result(BackupHostAction.BackupDirectory, "content://duplicate"); runCurrent()
            assertEquals(listOf("content://selected"), settings.paths); assertEquals(listOf("check:content://old", "backup:content://selected:false"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun filesystemPathRequiresPermissionBeforeBackupAndDenialHasNoBackupSideEffect() = runTest(dispatcher) {
        val settings = Settings().apply { values.value = values.value.copy(backupPath = "/selected") }; val f = Fixture(this, settings)
        try { runCurrent(); f.controller.manualDestination(false); runCurrent(); assertEquals(BackupHostAction.StoragePermission, f.controller.state.value.event!!.action)
            f.controller.consumeEvent(f.controller.state.value.event!!.id); f.controller.permissionResult(false); runCurrent(); assertTrue(f.operations.calls.isEmpty())
            f.controller.manualDestination(true); runCurrent(); f.controller.consumeEvent(f.controller.state.value.event!!.id); f.controller.permissionResult(true); f.controller.permissionResult(true); runCurrent()
            assertEquals(listOf("backup:/selected:true"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun restoreNamesAreStableCloudWarningConsumesOnceAndSelectionForwardsExactName() = runTest(dispatcher) {
        val f = Fixture(this, operations = Operations().apply { truncated = true })
        try { runCurrent(); f.controller.restore(); runCurrent(); assertEquals(BackupPopup.RestoreFiles, f.controller.state.value.popup)
            assertEquals(listOf("backup-new", "backup-old"), f.controller.state.value.names); assertTrue(f.controller.consumeTruncatedNotice()); assertFalse(f.controller.consumeTruncatedNotice())
            f.controller.selectRestore("not-listed"); runCurrent(); assertEquals(listOf("names"), f.operations.calls)
            f.controller.selectRestore("backup-old"); runCurrent(); assertEquals(listOf("names", "remote:backup-old"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun cloudFailureOffersLocalFallbackAndPickerCancellationOrDuplicateCannotRestore() = runTest(dispatcher) {
        val f = Fixture(this, operations = Operations().apply { failNames = true })
        try { runCurrent(); f.controller.restore(); runCurrent(); assertEquals(BackupPopup.RestoreFallback, f.controller.state.value.popup)
            f.controller.localRestore(); val event = f.controller.state.value.event!!; f.controller.consumeEvent(event.id)
            f.controller.result(BackupHostAction.RestoreFile, null); f.controller.result(BackupHostAction.RestoreFile, "late"); runCurrent(); assertEquals(listOf("names"), f.operations.calls)
            f.controller.localRestore(); f.controller.consumeEvent(f.controller.state.value.event!!.id); f.controller.result(BackupHostAction.RestoreFile, "content://chosen.zip"); runCurrent()
            assertEquals(listOf("names", "local:content://chosen.zip"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun scannedLanDescriptorNeedsConfirmationBeforeAnyDownloadOrRestore() = runTest(dispatcher) {
        val f = Fixture(this)
        try { runCurrent(); f.controller.scanLan(); f.controller.consumeEvent(f.controller.state.value.event!!.id); f.controller.result(BackupHostAction.ScanLan, "private-qr"); runCurrent()
            assertEquals(listOf("decode:private-qr"), f.lan.calls); assertEquals(BackupPopup.ReceiveConfirm, f.controller.state.value.popup); assertEquals(BackupLanReceiveInfo(1024, "device"), f.controller.state.value.receive)
            f.controller.receiveConfirmed(); runCurrent(); assertEquals(listOf("decode:private-qr", "receive:private-qr"), f.lan.calls); assertEquals(BackupTaskPhase.Complete, f.drafts.value.task!!.phase)
        } finally { f.close() }
    }
    @Test fun sendOfferIsClosedOnStopAndDoesNotReplayServerAfterPauseOrRestoration() = runTest(dispatcher) {
        val f = Fixture(this)
        try { runCurrent(); f.controller.send(); runCurrent(); assertEquals("offer", f.controller.state.value.offer!!.id)
            f.controller.onStop(); runCurrent(); assertNull(f.controller.state.value.offer); assertEquals(listOf("prepare", "close:offer"), f.lan.calls)
            f.controller.onStop(); runCurrent(); assertEquals(1, f.lan.closed)
        } finally { f.close() }
    }
    @Test fun completeReceiptFailureBlocksNewTasksAndRetryOnlyWritesReceiptWithoutRepeatingBackup() = runTest(dispatcher) {
        val drafts = Drafts().apply { failComplete = true }; val f = Fixture(this, drafts = drafts)
        try { runCurrent(); f.controller.manualDestination(false); runCurrent(); assertTrue(f.controller.state.value.pendingReceipt); assertTrue(f.model.state.value.taskBusy)
            f.controller.restore(); f.model.path("unsafe"); runCurrent(); assertEquals(listOf("backup:null:false"), f.operations.calls); assertTrue(f.settings.paths.isEmpty())
            drafts.failComplete = false; f.controller.retry(); runCurrent(); assertFalse(f.controller.state.value.pendingReceipt); assertFalse(f.model.state.value.taskBusy)
            assertEquals(listOf("backup:null:false"), f.operations.calls); assertNotNull(f.controller.state.value.success)
        } finally { f.close() }
    }
    @Test fun runningTaskRestoreIsInterruptedAndCannotReplayUntilUserExplicitlyReconfirms() = runTest(dispatcher) {
        val drafts = Drafts().apply { value = BackupSettingsDraft(task = BackupTaskDraft("old", BackupTaskKind.RestoreLocal, "private-file", phase = BackupTaskPhase.Running)) }; val f = Fixture(this, drafts = drafts)
        try { runCurrent(); assertTrue(f.controller.state.value.interrupted); assertTrue(f.operations.calls.isEmpty())
            f.controller.retry(); assertEquals(BackupPopup.RetryConfirm, f.controller.state.value.popup); assertTrue(f.operations.calls.isEmpty())
            f.controller.retryConfirmed(); runCurrent(); assertEquals(listOf("local:private-file"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun consumedCompleteSuccessDoesNotReappearAcrossSavedStateRestoration() = runTest(dispatcher) {
        val task = BackupTaskDraft("receipt", BackupTaskKind.Backup, phase = BackupTaskPhase.Complete)
        val drafts = Drafts().apply { value = BackupSettingsDraft(task = task) }; val saved = SavedStateHandle(mapOf("backupSuccessConsumed" to "receipt")); val f = Fixture(this, drafts = drafts, saved = saved)
        try { runCurrent(); assertNull(f.controller.state.value.success); assertTrue(f.operations.calls.isEmpty()) }
        finally { f.close() }
    }
    @Test fun earlyPickerResultSurvivesPrivateInitializationFailureAndUsesCapturedTicketAfterRetry() = runTest(dispatcher) {
        val drafts = Drafts().apply { failOpen = true }; val saved = SavedStateHandle(mapOf("backupResultAction" to BackupHostAction.RestoreFile.name)); val f = Fixture(this, drafts = drafts, saved = saved)
        try { f.controller.result(BackupHostAction.RestoreFile, "private-early-uri"); runCurrent(); assertTrue(f.model.state.value.failed); assertTrue(f.operations.calls.isEmpty())
            drafts.failOpen = false; f.model.retry(); runCurrent(); assertEquals(listOf("local:private-early-uri"), f.operations.calls)
        } finally { f.close() }
    }
    @Test fun cancelledOrStoppedNonCooperativeTaskCannotDeliverSuccessOrLateFailure() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val ops = Operations().apply { this.gate = gate; lateError = true }; val f = Fixture(this, operations = ops)
        try { runCurrent(); f.controller.manualDestination(false); runCurrent(); val before = f.controller.state.value; f.controller.stop(); gate.complete(Unit); runCurrent()
            assertEquals(before, f.controller.state.value); assertNull(f.controller.state.value.success)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun restoredEarlyResultConsumesPendingLaunchBeforeInitializationAndNeverRelaunchesPicker() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("backupHostId" to "old-launch", "backupHostAction" to BackupHostAction.RestoreFile.name,
            "backupResultAction" to BackupHostAction.RestoreFile.name))
        val f = Fixture(this, saved = saved)
        try { f.controller.result(BackupHostAction.RestoreFile, "private-result"); assertNull(f.controller.state.value.event)
            runCurrent(); assertEquals(listOf("local:private-result"), f.operations.calls)
            assertFalse(f.controller.consumeEvent("old-launch")); assertFalse(saved.contains("backupHostId")); assertFalse(saved.contains("backupResultAction"))
        } finally { f.close() }
    }
    @Test fun cancelWaitsForNonCooperativeCleanupAndBlocksAnotherOperationUntilJournalIsCleared() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val ops = Operations().apply { this.gate = gate }; val f = Fixture(this, operations = ops)
        try { runCurrent(); f.controller.manualDestination(false); runCurrent(); f.controller.cancel(); f.controller.cancel(); runCurrent()
            assertTrue(f.controller.state.value.busy); assertTrue(f.model.state.value.taskBusy)
            f.controller.restore(); runCurrent(); assertEquals(listOf("backup:null:false"), ops.calls)
            gate.complete(Unit); runCurrent(); assertFalse(f.controller.state.value.busy); assertFalse(f.model.state.value.taskBusy)
            assertNull(f.drafts.value.task); assertNull(f.controller.state.value.success)
            f.controller.restore(); runCurrent(); assertEquals(listOf("backup:null:false", "names"), ops.calls)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun taskJournalKeepsLargePayloadOffSavedStateAndPreservesUnrelatedDraftFields() = runTest(dispatcher) {
        val large = "private-uri".repeat(50000)
        val drafts = Drafts().apply { value = BackupSettingsDraft(text = "private-password", intervalText = "19", autoWebDav = false,
            choices = mapOf("private-key" to true)) }
        val saved = SavedStateHandle(mapOf("backupResultAction" to BackupHostAction.RestoreFile.name)); val f = Fixture(this, drafts = drafts, saved = saved)
        try { runCurrent(); f.controller.result(BackupHostAction.RestoreFile, large); runCurrent()
            assertEquals(large, drafts.value.task!!.payload); assertEquals("private-password", drafts.value.text)
            assertEquals("19", drafts.value.intervalText); assertFalse(drafts.value.autoWebDav); assertEquals(mapOf("private-key" to true), drafts.value.choices)
            saved.keys().forEach { key -> assertFalse(saved.get<Any>(key).toString().contains(large)); assertFalse(saved.get<Any>(key).toString().contains("private-password")) }
        } finally { f.close() }
    }
    @Test fun retainedViewModelReturnsTheSameOwnedCoordinatorAcrossHostRecreation() = runTest(dispatcher) {
        val drafts = Drafts(); val settings = Settings(); val ops = Operations(); val lan = Lan()
        val model = BackupSettingsViewModel(settings, Choices(), drafts, SavedStateHandle()); val owner = ViewModelStore().apply { put("vm", model) }
        try { val first = model.operations(ops, lan); runCurrent(); assertSame(first, model.operations(Operations(), Lan()))
            first.manualDestination(false); runCurrent(); assertEquals(listOf("backup:null:false"), ops.calls)
            model.operations(ops, lan).restore(); runCurrent(); assertEquals(listOf("backup:null:false", "names"), ops.calls)
        } finally { model.release(); owner.clear() }
    }

    @Test fun oldPickerNonceCannotConsumeNewRequestForTheSameAction() = runTest(dispatcher) {
        val f = Fixture(this)
        try { runCurrent(); f.controller.localRestore(); val old = f.controller.state.value.event!!.id; f.controller.consumeEvent(old)
            f.controller.result(BackupHostAction.RestoreFile, null, old); f.controller.localRestore(); val next = f.controller.state.value.event!!.id
            assertNotEquals(old, next); f.controller.consumeEvent(next)
            f.controller.result(BackupHostAction.RestoreFile, "late-old-result", old); runCurrent(); assertTrue(f.operations.calls.isEmpty())
            assertEquals(next, f.controller.resultId(BackupHostAction.RestoreFile))
            f.controller.result(BackupHostAction.RestoreFile, "new-result", next); runCurrent(); assertEquals(listOf("local:new-result"), f.operations.calls)
            assertNull(f.controller.resultId(BackupHostAction.RestoreFile))
        } finally { f.close() }
    }
    @Test fun earlyMatchingNonceIsRetainedUntilInitializationAndWrongNonceCannotReplaceIt() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("backupHostId" to "owned", "backupHostAction" to BackupHostAction.RestoreFile.name,
            "backupResultAction" to BackupHostAction.RestoreFile.name, "backupResultId" to "owned"))
        val drafts = Drafts().apply { failOpen = true }; val f = Fixture(this, drafts = drafts, saved = saved)
        try { f.controller.result(BackupHostAction.RestoreFile, "wrong", "old"); assertEquals("owned", f.controller.resultId(BackupHostAction.RestoreFile))
            f.controller.result(BackupHostAction.RestoreFile, "matching", "owned"); runCurrent(); assertTrue(f.operations.calls.isEmpty())
            assertNull(f.controller.state.value.event); assertNull(f.controller.resultId(BackupHostAction.RestoreFile))
            drafts.failOpen = false; f.model.retry(); runCurrent(); assertEquals(listOf("local:matching"), f.operations.calls)
        } finally { f.close() }
    }

}
