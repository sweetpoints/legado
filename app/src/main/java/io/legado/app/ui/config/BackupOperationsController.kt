package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.BackupSettingsRepository
import io.legado.app.data.repository.*
import io.legado.app.model.backup.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class BackupHostAction {
    Path,
    BackupDirectory,
    RestoreFile,
    ImportOld,
    ScanLan,
    StoragePermission,
    Help,
    Log,
}

internal enum class BackupPopup {
    Path,
    Destination,
    Lan,
    SendConfirm,
    ReceiveConfirm,
    RestoreFiles,
    RestoreFallback,
    RetryConfirm,
}

internal enum class BackupWait {
    CheckingPath,
    Backup,
    LoadingNames,
    Restore,
    ImportOld,
    LanSend,
    LanReceive,
    BeforeLanRestore,
}

internal data class BackupHostEvent(val id: String, val action: BackupHostAction)

internal data class BackupRuntimeState(
    val busy: Boolean = false,
    val waiting: BackupWait? = null,
    val popup: BackupPopup? = null,
    val event: BackupHostEvent? = null,
    val names: List<String> = emptyList(),
    val receive: BackupLanReceiveInfo? = null,
    val offer: BackupLanOffer? = null,
    val truncatedCloudListing: Boolean = false,
    val error: String? = null,
    val interrupted: Boolean = false,
    val pendingReceipt: Boolean = false,
    val success: String? = null,
)

/**
 * Main-owned task coordination. Only tiny event ids/enum names/flags are saved; inputs use the
 * editor's private journal.
 */
internal class BackupOperationsController(
    private val scope: CoroutineScope,
    private val model: BackupSettingsViewModel,
    private val settings: BackupSettingsRepository,
    private val operations: BackupOperationsRepository,
    private val lan: BackupLanRepository,
    private val saved: SavedStateHandle,
) {
    private val mutable =
        MutableStateFlow(
            BackupRuntimeState(
                popup =
                    saved.get<String>("backupPopup")?.let { name ->
                        BackupPopup.entries.find { it.name == name }
                    },
                event =
                    saved.get<String>("backupHostId")?.let { id ->
                        saved.get<String>("backupHostAction")?.let { name ->
                            BackupHostAction.entries
                                .find { it.name == name }
                                ?.let { BackupHostEvent(id, it) }
                        }
                    },
            )
        )
    val state = mutable.asStateFlow()
    private var stopped = false
    private var initialized = false
    private var operation: Job? = null
    private var expiry: Job? = null
    private var pendingReceipt: BackupTaskDraft? = null
    private var receiptNames: List<String>? = null
    private var cancellation: Job? = null
    private var afterCheck: (() -> Unit)? = null
    private var early: Pair<BackupHostAction, String?>? = null
    private var activeKind: BackupTaskKind? = null
    private val initializer = scope.launch {
        model.state.first { it.draft != null && !it.loading && !it.failed }
        currentCoroutineContext().ensureActive()
        initialized = true
        model.state.value.draft?.task?.let { task ->
            if (task.phase == BackupTaskPhase.Complete) publishCompletion(task)
            else mutable.value = state.value.copy(interrupted = true, error = "上次操作已中断，请重新确认后继续")
        }
        if (saved.get<Boolean>("backupHelpRequested") != true) {
            saved["backupHelpRequested"] = true
            try {
                if (settings.needsHelp()) {
                    currentCoroutineContext().ensureActive()
                    request(BackupHostAction.Help)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
            }
        }
        model.state.collect {
            if (!stopped && available() && early != null)
                dispatchResult(early!!.also { early = null })
        }
    }

    private fun available() =
        !stopped &&
            initialized &&
            !state.value.busy &&
            !state.value.pendingReceipt &&
            state.value.event == null &&
            saved.get<String>("backupResultAction") == null &&
            model.taskAvailable()

    private fun popup(value: BackupPopup?) {
        if (value == null) saved.remove<String>("backupPopup")
        else saved["backupPopup"] = value.name
        mutable.value = state.value.copy(popup = value)
    }

    fun dismissPopup() {
        popup(null)
    }

    fun pathMenu() {
        if (available()) popup(BackupPopup.Path)
    }

    fun backup() {
        if (available()) popup(BackupPopup.Destination)
    }

    fun lanMenu() {
        if (available()) popup(BackupPopup.Lan)
    }

    fun sendConfirm() {
        if (available()) popup(BackupPopup.SendConfirm)
    }

    fun help() {
        if (available()) request(BackupHostAction.Help)
    }

    fun log() {
        if (available()) request(BackupHostAction.Log)
    }

    fun importOld() {
        if (available()) request(BackupHostAction.ImportOld)
    }

    fun selectPath() {
        if (available()) {
            popup(null)
            request(BackupHostAction.Path)
        }
    }

    fun defaultPath() {
        if (available()) {
            popup(null)
            model.path(null)
        }
    }

    fun localRestore() {
        if (available()) {
            popup(null)
            request(BackupHostAction.RestoreFile)
        }
    }

    fun scanLan() {
        if (available()) {
            popup(null)
            request(BackupHostAction.ScanLan)
        }
    }

    private fun request(action: BackupHostAction) {
        if (stopped || state.value.event != null) return
        val event = BackupHostEvent(UUID.randomUUID().toString(), action)
        saved["backupHostId"] = event.id
        saved["backupHostAction"] = action.name
        if (
            action in
                listOf(
                    BackupHostAction.Path,
                    BackupHostAction.BackupDirectory,
                    BackupHostAction.RestoreFile,
                    BackupHostAction.ImportOld,
                    BackupHostAction.ScanLan,
                    BackupHostAction.StoragePermission,
                )
        ) {
            saved["backupResultAction"] = action.name
            saved["backupResultId"] = event.id
        }
        mutable.value = state.value.copy(event = event)
    }

    fun resultId(action: BackupHostAction): String? =
        saved.get<String>("backupResultId").takeIf {
            saved.get<String>("backupResultAction") == action.name
        }

    fun consumeEvent(id: String): Boolean {
        if (
            !initialized ||
                stopped ||
                state.value.event?.id != id ||
                model.state.value.loading ||
                model.state.value.failed
        )
            return false
        saved.remove<String>("backupHostId")
        saved.remove<String>("backupHostAction")
        mutable.value = state.value.copy(event = null)
        return true
    }

    /**
     * Metadata-free restored contracts are routed by the retained primitive ticket, not their reset
     * requestCode/value.
     */
    fun result(action: BackupHostAction, payload: String?, nonce: String? = null) {
        if (
            stopped ||
                saved.get<String>("backupResultAction") != action.name ||
                nonce != null && nonce != saved.get<String>("backupResultId")
        )
            return
        saved.remove<String>("backupResultAction")
        saved.remove<String>("backupResultId")
        if (state.value.event?.action == action) {
            saved.remove<String>("backupHostId")
            saved.remove<String>("backupHostAction")
            mutable.value = state.value.copy(event = null)
        }
        val value = action to payload
        if (!available()) early = value else dispatchResult(value)
    }

    private fun dispatchResult(value: Pair<BackupHostAction, String?>) {
        val (action, payload) = value
        if (payload == null) return
        when (action) {
            BackupHostAction.Path -> model.path(payload)
            BackupHostAction.BackupDirectory ->
                start(
                    BackupTaskKind.Backup,
                    payload,
                    saved.get<Boolean>("manualBackupUploadWebDav") ?: true,
                    saveBackupPath = true,
                )
            BackupHostAction.RestoreFile -> start(BackupTaskKind.RestoreLocal, payload)
            BackupHostAction.ImportOld -> start(BackupTaskKind.ImportOld, payload)
            BackupHostAction.ScanLan -> receiveDescriptor(payload)
            else -> Unit
        }
    }

    fun manualDestination(uploadWebDav: Boolean) {
        if (!available()) return
        saved["manualBackupUploadWebDav"] = uploadWebDav
        popup(null)
        val path = model.state.value.settings?.backupPath
        when {
            path.isNullOrEmpty() -> start(BackupTaskKind.Backup, null, uploadWebDav)
            path.startsWith("content:", true) -> checkPath(path, uploadWebDav)
            else -> {
                val task = task(BackupTaskKind.Backup, path, uploadWebDav)
                launchCheck(BackupWait.CheckingPath) {
                    model.stageTask(task)
                    currentCoroutineContext().ensureActive()
                    request(BackupHostAction.StoragePermission)
                }
            }
        }
    }

    private fun checkPath(path: String, upload: Boolean) =
        launchCheck(BackupWait.CheckingPath) {
            val writable = operations.writableTree(path)
            currentCoroutineContext().ensureActive()
            if (writable) {
                // The existing writable tree needs no preference rewrite or platform picker.
                afterCheck = { start(BackupTaskKind.Backup, path, upload) }
            } else {
                model.stageTask(task(BackupTaskKind.Backup, path, upload))
                currentCoroutineContext().ensureActive()
                request(BackupHostAction.BackupDirectory)
            }
        }

    fun permissionResult(granted: Boolean, nonce: String? = null) {
        if (
            stopped ||
                saved.get<String>("backupResultAction") !=
                    BackupHostAction.StoragePermission.name ||
                nonce != null && nonce != saved.get<String>("backupResultId")
        )
            return
        saved.remove<String>("backupResultAction")
        saved.remove<String>("backupResultId")
        if (state.value.event?.action == BackupHostAction.StoragePermission) {
            saved.remove<String>("backupHostId")
            saved.remove<String>("backupHostAction")
            mutable.value = state.value.copy(event = null)
        }
        if (!granted) return
        val task = model.state.value.draft?.task ?: return
        if (available()) start(task.kind, task.payload, task.uploadWebDav)
        else early = BackupHostAction.BackupDirectory to task.payload
    }

    fun restore() {
        if (available()) start(BackupTaskKind.RestoreNames)
    }

    fun selectRestore(name: String) {
        if (available() && state.value.names.contains(name)) {
            popup(null)
            start(BackupTaskKind.RestoreWebDav, name)
        }
    }

    fun send() {
        if (available()) {
            popup(null)
            start(BackupTaskKind.LanSend)
        }
    }

    private fun task(kind: BackupTaskKind, payload: String? = null, upload: Boolean = true) =
        BackupTaskDraft(UUID.randomUUID().toString(), kind, payload, upload)

    private fun start(
        kind: BackupTaskKind,
        payload: String? = null,
        upload: Boolean = true,
        saveBackupPath: Boolean = false,
    ) {
        if (!available()) return
        val value = task(kind, payload, upload)
        startTask(value, saveBackupPath)
    }

    private fun startTask(value: BackupTaskDraft, saveBackupPath: Boolean = false) {
        if (stopped || state.value.busy || state.value.pendingReceipt) return
        activeKind = value.kind
        model.taskBusy(true)
        mutable.value =
            state.value.copy(
                busy = true,
                error = null,
                interrupted = false,
                waiting = waitFor(value.kind),
            )
        operation = scope.launch {
            try {
                model.stageTask(value)
                currentCoroutineContext().ensureActive()
                if (saveBackupPath) {
                    settings.path(value.payload)
                    currentCoroutineContext().ensureActive()
                }
                val running = value.copy(phase = BackupTaskPhase.Running)
                model.stageTask(running)
                currentCoroutineContext().ensureActive()
                var names: List<String>? = null
                when (value.kind) {
                    BackupTaskKind.Backup -> operations.backup(value.payload, value.uploadWebDav)
                    BackupTaskKind.RestoreNames ->
                        operations.restoreFiles().let { listing ->
                            names = listing.names
                            currentCoroutineContext().ensureActive()
                            mutable.value =
                                state.value.copy(
                                    truncatedCloudListing = listing.truncatedCloudListing
                                )
                        }
                    BackupTaskKind.RestoreWebDav -> operations.restoreWebDav(value.payload!!)
                    BackupTaskKind.RestoreLocal -> operations.restoreLocal(value.payload!!)
                    BackupTaskKind.ImportOld -> operations.importOld(value.payload!!)
                    BackupTaskKind.LanSend -> {
                        val offer = lan.prepare()
                        currentCoroutineContext().ensureActive()
                        mutable.value = state.value.copy(offer = offer)
                        scheduleExpiry(offer)
                    }
                    BackupTaskKind.LanReceive ->
                        lan.receive(value.payload!!).collect { phase ->
                            currentCoroutineContext().ensureActive()
                            mutable.value =
                                state.value.copy(
                                    waiting =
                                        when (phase) {
                                            BackupLanReceivePhase.Receiving -> BackupWait.LanReceive
                                            BackupLanReceivePhase.BackingUp ->
                                                BackupWait.BeforeLanRestore
                                            BackupLanReceivePhase.Restoring -> BackupWait.Restore
                                            BackupLanReceivePhase.Complete -> BackupWait.Restore
                                        }
                                )
                        }
                }
                currentCoroutineContext().ensureActive()
                val complete = running.copy(phase = BackupTaskPhase.Complete)
                pendingReceipt = complete
                receiptNames = names
                model.stageTask(complete, names)
                currentCoroutineContext().ensureActive()
                pendingReceipt = null
                receiptNames = null
                if (!stopped) publishCompletion(complete)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) {
                    mutable.value =
                        state.value.copy(
                            error = error.localizedMessage.orEmpty(),
                            pendingReceipt = pendingReceipt != null,
                            interrupted =
                                pendingReceipt == null && value.kind != BackupTaskKind.RestoreNames,
                        )
                    if (value.kind == BackupTaskKind.RestoreNames && pendingReceipt == null)
                        popup(BackupPopup.RestoreFallback)
                }
            } finally {
                if (!stopped && currentCoroutineContext().isActive) {
                    mutable.value = state.value.copy(busy = false, waiting = null)
                    if (pendingReceipt == null) model.taskBusy(false)
                }
            }
        }
    }

    private fun waitFor(kind: BackupTaskKind) =
        when (kind) {
            BackupTaskKind.Backup -> BackupWait.Backup
            BackupTaskKind.RestoreNames -> BackupWait.LoadingNames
            BackupTaskKind.RestoreWebDav,
            BackupTaskKind.RestoreLocal -> BackupWait.Restore
            BackupTaskKind.ImportOld -> BackupWait.ImportOld
            BackupTaskKind.LanSend -> BackupWait.LanSend
            BackupTaskKind.LanReceive -> BackupWait.LanReceive
        }

    private fun publishCompletion(task: BackupTaskDraft) {
        if (stopped) return
        if (task.kind == BackupTaskKind.RestoreNames) {
            mutable.value =
                state.value.copy(names = model.state.value.draft?.restoreNames.orEmpty())
            popup(BackupPopup.RestoreFiles)
        } else if (
            task.kind == BackupTaskKind.Backup &&
                saved.get<String>("backupSuccessConsumed") != task.id
        )
            mutable.value = state.value.copy(success = task.id)
        mutable.value = state.value.copy(interrupted = false, pendingReceipt = false, error = null)
    }

    fun consumeSuccess(id: String): Boolean {
        if (stopped || state.value.success != id) return false
        saved["backupSuccessConsumed"] = id
        mutable.value = state.value.copy(success = null)
        return true
    }

    fun consumeTruncatedNotice(): Boolean {
        if (!state.value.truncatedCloudListing || stopped) return false
        mutable.value = state.value.copy(truncatedCloudListing = false)
        return true
    }

    private fun receiveDescriptor(qr: String) =
        launchCheck(BackupWait.LanReceive) {
            model.stageTask(task(BackupTaskKind.LanReceive, qr))
            currentCoroutineContext().ensureActive()
            val info = lan.decode(qr)
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(receive = info)
            popup(BackupPopup.ReceiveConfirm)
        }

    fun receiveConfirmed() {
        if (!available()) return
        val pending =
            model.state.value.draft?.task?.takeIf { it.kind == BackupTaskKind.LanReceive } ?: return
        popup(null)
        startTask(pending)
    }

    fun retry() {
        if (stopped || state.value.busy) return
        val receipt = pendingReceipt
        if (receipt != null) {
            model.taskBusy(true)
            mutable.value = state.value.copy(busy = true, error = null)
            operation = scope.launch {
                try {
                    model.stageTask(receipt, receiptNames)
                    currentCoroutineContext().ensureActive()
                    pendingReceipt = null
                    receiptNames = null
                    if (!stopped) {
                        publishCompletion(receipt)
                        model.taskBusy(false)
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!stopped)
                        mutable.value =
                            state.value.copy(
                                error = error.localizedMessage.orEmpty(),
                                pendingReceipt = true,
                            )
                } finally {
                    if (!stopped && currentCoroutineContext().isActive)
                        mutable.value = state.value.copy(busy = false)
                }
            }
        } else if (available()) popup(BackupPopup.RetryConfirm)
    }

    fun retryConfirmed() {
        if (!available()) return
        val pending = model.state.value.draft?.task ?: return
        popup(null)
        startTask(pending.copy(phase = BackupTaskPhase.Requested))
    }

    private fun launchCheck(wait: BackupWait, action: suspend () -> Unit) {
        if (!available()) return
        activeKind = null
        afterCheck = null
        model.taskBusy(true)
        mutable.value = state.value.copy(busy = true, waiting = wait, error = null)
        operation = scope.launch {
            try {
                action()
                currentCoroutineContext().ensureActive()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
            } finally {
                if (!stopped && currentCoroutineContext().isActive) {
                    mutable.value = state.value.copy(busy = false, waiting = null)
                    model.taskBusy(false)
                    afterCheck?.let {
                        afterCheck = null
                        it()
                    }
                    early
                        ?.takeIf { available() }
                        ?.let {
                            early = null
                            dispatchResult(it)
                        }
                }
            }
        }
    }

    fun cancel() {
        if (stopped || state.value.pendingReceipt) return
        val job = operation ?: return
        if (cancellation?.isActive == true) return
        job.cancel()
        cancellation = scope.launch {
            job.join()
            currentCoroutineContext().ensureActive()
            if (!stopped) {
                try {
                    model.stageTask(null)
                    currentCoroutineContext().ensureActive()
                    if (!stopped) {
                        mutable.value =
                            state.value.copy(
                                busy = false,
                                waiting = null,
                                error = null,
                                interrupted = false,
                            )
                        model.taskBusy(false)
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!stopped) {
                        mutable.value =
                            state.value.copy(
                                busy = false,
                                waiting = null,
                                error = error.localizedMessage.orEmpty(),
                            )
                        model.taskBusy(false)
                    }
                }
            }
        }
    }

    private fun scheduleExpiry(offer: BackupLanOffer) {
        expiry?.cancel()
        expiry = scope.launch {
            delay((offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            closeOffer()
        }
    }

    fun closeOffer() {
        val offer = state.value.offer ?: return
        expiry?.cancel()
        expiry = null
        mutable.value = state.value.copy(offer = null)
        scope.launch {
            try {
                lan.close(offer.id)
                currentCoroutineContext().ensureActive()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
            }
        }
    }

    fun onStop() {
        closeOffer()
        if (
            state.value.busy &&
                activeKind in listOf(BackupTaskKind.LanSend, BackupTaskKind.LanReceive)
        )
            cancel()
    }

    fun stop() {
        if (!stopped) {
            stopped = true
            initializer.cancel()
            operation?.cancel()
            cancellation?.cancel()
            expiry?.cancel()
        }
    }

    suspend fun release() {
        stop()
        lan.closeAll()
    }
}
