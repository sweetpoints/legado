package io.legado.app.ui.association.compose

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationImportOperations
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationNativeReceipt
import io.legado.app.data.association.AssociationNativeResult
import io.legado.app.data.association.AssociationNativeResultRepository
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationOperation
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionController
import io.legado.app.data.association.AssociationSessionRepository
import io.legado.app.data.association.associationOnlineRoute
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AssociationImportState(
    val loaded: Boolean = false,
    val ticket: String? = null,
    val session: AssociationSession? = null,
    val busy: Boolean = false,
    val restoreError: String? = null,
    val nativeResultPending: Boolean = false,
)

/** Each host owns one private UUID; providers, JSON and complete book metadata stay off Bundle. */
open class AssociationImportViewModel(
    private val savedState: SavedStateHandle,
    private val sessions: AssociationSessionRepository,
    private val files: AssociationFileRepository,
    private val online: AssociationOnlineRepository,
    private val actions: AssociationImportOperations? = null,
    private val nativeResults: AssociationNativeResultRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AssociationImportState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var closed = false
    private var sessionController: AssociationSessionController? = null
    private val nativeResultCommands = Mutex()

    private fun controller(ticket: String): AssociationSessionController {
        return sessionController?.takeIf { it.ticket == ticket }
            ?: AssociationSessionController(ticket, sessions).also { sessionController = it }
    }

    init {
        savedState.get<String>(TICKET_KEY)?.let { ticket ->
            operation = viewModelScope.launch {
                try {
                    val restored = sessions.read(ticket)
                    val pendingResults = nativeResults?.read(ticket).orEmpty()
                    currentCoroutineContext().ensureActive()
                    if (closed) return@launch
                    val needsInspection =
                        restored.phase == AssociationPhase.Ready ||
                            (restored.phase == AssociationPhase.Loading &&
                                restored.operation?.kind == INSPECT_OPERATION)
                    mutableState.value =
                        AssociationImportState(
                            loaded = true,
                            ticket = ticket,
                            session = restored,
                            busy = needsInspection,
                            nativeResultPending = pendingResults.isNotEmpty(),
                        )
                    // Reading/staging is repeatable. An accepted import operation is resumed only
                    // by its dedicated journal, never by rerunning the first incoming Intent.
                    if (needsInspection) {
                        inspect(ticket, restored)
                    }
                    reconcileNativeResults()
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (!closed)
                        mutableState.value =
                            AssociationImportState(
                                loaded = true,
                                ticket = ticket,
                                restoreError = failure.message ?: "无法恢复导入",
                            )
                }
            }
        } ?: run { mutableState.value = AssociationImportState(loaded = true) }
    }

    /** The Host captured its large launch on IO before clearing Intent defaults. */
    fun attachPrepared(ticket: String) {
        if (closed || operation?.isActive == true || state.value.ticket != null) return
        savedState[TICKET_KEY] = ticket
        mutableState.value = AssociationImportState(ticket = ticket, busy = true)
        operation = viewModelScope.launch {
            try {
                val initial = sessions.read(ticket)
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                publish(ticket, initial, busy = true)
                inspect(ticket, initial)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!closed)
                    mutableState.value =
                        state.value.copy(
                            loaded = true,
                            busy = false,
                            restoreError = failure.message ?: "Unable to restore import",
                        )
            }
        }
    }

    fun start(input: AssociationInput) {
        if (closed || operation?.isActive == true || state.value.ticket != null) return
        mutableState.value = state.value.copy(busy = true)
        operation = viewModelScope.launch {
            var allocatedTicket: String? = null
            try {
                val ticket = sessions.create(input)
                allocatedTicket = ticket
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                savedState[TICKET_KEY] = ticket
                mutableState.value = AssociationImportState(ticket = ticket, busy = true)
                allocatedTicket = null
                val initial = sessions.read(ticket)
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                mutableState.value =
                    AssociationImportState(
                        loaded = true,
                        ticket = ticket,
                        session = initial,
                        busy = true,
                    )
                inspect(ticket, initial)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!closed)
                    mutableState.value =
                        state.value.copy(busy = false, restoreError = failure.message ?: "无法创建导入")
            } finally {
                allocatedTicket?.let { ticket ->
                    withContext(NonCancellable) { sessions.release(ticket) }
                }
            }
        }
    }

    fun retryInspection() {
        val current = state.value
        val session = current.session ?: return
        val ticket = current.ticket ?: return
        if (closed || current.busy || operation?.isActive == true) return
        if (session.operation != null && session.operation.kind != INSPECT_OPERATION) return
        mutableState.value = current.copy(busy = true)
        operation = viewModelScope.launch { inspect(ticket, session) }
    }

    val ownedTicket: String?
        get() = savedState.get(TICKET_KEY)

    suspend fun awaitCommands() {
        val currentJob = currentCoroutineContext()[Job]
        operation?.takeIf { it != currentJob }?.join()
    }

    /**
     * Record first, even while restore is suspended. The callback carries only its private owner.
     */
    suspend fun recordNativeResult(ticket: String, result: AssociationNativeResult) {
        if (closed || savedState.get<String>(TICKET_KEY) != ticket) return
        val repository = nativeResults ?: return
        repository.record(ticket, result)
        currentCoroutineContext().ensureActive()
        reconcileNativeResults()
    }

    suspend fun reconcileNativeResults() {
        val repository = nativeResults ?: return
        awaitCommands()
        nativeResultCommands.withLock {
            if (closed) return@withLock
            val ticket = state.value.ticket ?: return@withLock
            val results = repository.read(ticket)
            if (results.isEmpty()) return@withLock
            mutableState.value = state.value.copy(nativeResultPending = true)
            try {
                for (result in results) {
                    if (closed || state.value.ticket != ticket) return@withLock
                    val session = controller(ticket).read()
                    if (session.generation != result.receipt.generation) continue
                    publish(ticket, session, busy = false)
                    when (result.receipt.kind) {
                        AssociationNativeKind.StoragePermission -> {
                            if (result.receipt in session.claimedEffects) {
                                permissionResult(result.receipt, result.permissionGranted == true)
                            }
                            repository.acknowledge(ticket, result.receipt)
                        }
                        AssociationNativeKind.SelectDirectory -> {
                            if (result.receipt in session.claimedEffects) {
                                directoryResult(result.receipt, result.directory)
                            } else if (
                                result.directory != null &&
                                    session.importAfterDirectory &&
                                    session.phase != AssociationPhase.Finished
                            ) {
                                // Cancellation may follow the folder acknowledgement but precede
                                // the accepted import command. The retained result bridges that
                                // gap; the local journal handles an already accepted command.
                                check(
                                    session.operation == null ||
                                        session.operation.kind == "local-import"
                                )
                                startConfirmedOperation(
                                    "local-import",
                                    result.directory,
                                    currentCoroutineContext()[Job],
                                )
                            }
                            awaitCommands()
                            val completed = controller(ticket).read()
                            publish(ticket, completed, busy = false)
                            if (
                                result.directory == null ||
                                    !completed.importAfterDirectory ||
                                    completed.phase == AssociationPhase.Finished
                            ) {
                                repository.acknowledge(ticket, result.receipt)
                            }
                        }
                        else -> error("Unexpected platform result")
                    }
                }
            } finally {
                if (!closed && state.value.ticket == ticket) {
                    mutableState.value = state.value.copy(nativeResultPending = false)
                }
            }
        }
    }

    suspend fun closeOwnedSession() {
        closed = true
        operation?.cancel()
        state.value.ticket?.let { ticket ->
            withContext(NonCancellable) { sessions.release(ticket) }
        }
    }

    /** Public async callbacks require a live, loaded nonterminal owner, not UUID equality alone. */
    fun acceptsCallback(ticket: String?, generation: Long?): Boolean {
        val current = state.value
        return !closed &&
            ticket != null &&
            generation != null &&
            current.loaded &&
            current.ticket == ticket &&
            current.session?.generation == generation &&
            current.session?.phase != AssociationPhase.Finished
    }

    fun reportProjectionFailure(ticket: String?, generation: Long?, failure: Throwable) {
        if (acceptsCallback(ticket, generation)) {
            mutableState.value =
                state.value.copy(restoreError = failure.message ?: "Unable to restore preview")
        }
    }

    fun updateSelectionWithProof(ids: Set<String>): Deferred<Boolean> {
        val current = state.value
        val ticket = current.ticket
        val session = current.session
        if (
            ticket == null ||
                session == null ||
                closed ||
                current.busy ||
                current.nativeResultPending ||
                operation?.isActive == true
        ) {
            return CompletableDeferred(false)
        }
        mutableState.value = current.copy(busy = true)
        val proof = viewModelScope.async {
            try {
                val accepted =
                    controller(ticket).update(session.generation) {
                        it.copy(
                            selectedIds =
                                it.previews.map { preview -> preview.id }.filter { it in ids }
                        )
                    } ?: return@async false
                publish(ticket, accepted, busy = false)
                acceptsCallback(ticket, session.generation) && accepted.selectedIds.toSet() == ids
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!closed)
                    mutableState.value =
                        state.value.copy(
                            busy = false,
                            restoreError = failure.message ?: "Unable to save selection",
                        )
                false
            }
        }
        operation = proof
        return proof
    }

    fun updateSelection(ids: Set<String>) = command { session ->
        session.copy(selectedIds = session.previews.map { it.id }.filter { it in ids })
    }

    fun requestDirectory(importAfter: Boolean = true) = command { session ->
        check(session.selectedIds.isNotEmpty()) { "No books selected" }
        session.copy(phase = AssociationPhase.Directory, importAfterDirectory = importAfter)
    }

    fun cancelDirectory() = command { session ->
        session.copy(phase = AssociationPhase.Preview, choosingDirectory = false)
    }

    fun finishRequest() = command(::finish)

    fun chooseSystemDirectory() = command { session ->
        session.copy(
            choosingDirectory = true,
            effects = session.effects + receipt(session, AssociationNativeKind.SelectDirectory),
        )
    }

    private fun command(transform: (AssociationSession) -> AssociationSession) {
        val current = state.value
        val ticket = current.ticket ?: return
        val session = current.session ?: return
        if (closed || current.busy || operation?.isActive == true) return
        mutableState.value = current.copy(busy = true)
        operation = viewModelScope.launch {
            try {
                controller(ticket).update(session.generation, transform)?.let {
                    publish(ticket, it, busy = false)
                }
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!closed)
                    mutableState.value =
                        state.value.copy(
                            busy = false,
                            restoreError = failure.message ?: "Unable to update import",
                        )
            }
        }
    }

    suspend fun claimNative(receipt: AssociationNativeReceipt): AssociationNativeReceipt? {
        val current = state.value
        val ticket = current.ticket ?: return null
        if (
            closed ||
                current.busy ||
                current.nativeResultPending ||
                current.session?.generation != receipt.generation
        )
            return null
        return controller(ticket).claim(receipt.token, receipt.generation)
    }

    suspend fun returnNative(receipt: AssociationNativeReceipt) {
        val ticket = state.value.ticket ?: return
        controller(ticket).returnUndelivered(receipt)
        val current = controller(ticket).read()
        publish(ticket, current, busy = state.value.busy)
    }

    suspend fun acknowledgeNative(receipt: AssociationNativeReceipt) {
        val ticket = state.value.ticket ?: return
        controller(ticket).acknowledge(receipt.token, receipt.generation)
        currentCoroutineContext().ensureActive()
        publish(ticket, controller(ticket).read(), busy = state.value.busy)
    }

    fun confirmOperation(kind: String, payload: String? = null) =
        startConfirmedOperation(kind, payload)

    private fun startConfirmedOperation(
        kind: String,
        payload: String?,
        restoringJob: Job? = null,
    ) {
        val current = state.value
        val ticket = current.ticket ?: return
        val session = current.session ?: return
        val executor = actions ?: return
        // Reconciliation is itself the initial restore Job. It may hand ownership to a confirmed
        // import; another active command still cannot authorize a second accepted mutation.
        if (
            closed ||
                current.busy ||
                session.phase == AssociationPhase.Finished ||
                (operation?.isActive == true && operation != restoringJob)
        )
            return
        mutableState.value = current.copy(busy = true)
        operation = viewModelScope.launch {
            try {
                val accepted =
                    controller(ticket).update(session.generation) {
                        val pending = it.operation
                        check(pending == null || pending.kind == kind) {
                            "Another import is pending"
                        }
                        check(pending == null || kind in REPLAY_SAFE_OPERATIONS) {
                            "The previous result is uncertain; close and confirm a new import"
                        }
                        it.copy(
                            error = null,
                            operation =
                                pending
                                    ?: AssociationOperation(
                                        UUID.randomUUID().toString(),
                                        it.generation,
                                        kind,
                                        payload,
                                        accepted = true,
                                    ),
                        )
                    } ?: return@launch
                publish(ticket, accepted, busy = true)
                val result = executor.execute(ticket, accepted, checkNotNull(accepted.operation))
                // Accepted engine results must reach private state even if the native owner stops
                // at the IO return. Only publishing UI remains cancellable after durable delivery.
                withContext(NonCancellable) {
                    val completed =
                        controller(ticket).update(accepted.generation) {
                            it.copy(
                                phase = AssociationPhase.Finished,
                                completionMessage = result.message,
                                operation = null,
                                effects =
                                    listOf(
                                        if (result.bookJson != null)
                                            receipt(
                                                it,
                                                AssociationNativeKind.OpenBook,
                                                payload = result.bookJson,
                                            )
                                        else
                                            receipt(
                                                it,
                                                AssociationNativeKind.Finish,
                                                payload = result.message,
                                            )
                                    ),
                            )
                        }
                    completed?.let { publish(ticket, it, busy = false) }
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!closed) {
                    controller(ticket)
                        .update(session.generation) {
                            it.copy(
                                phase = AssociationPhase.Failed,
                                error = failure.message ?: "Import failed",
                            )
                        }
                        ?.let { publish(ticket, it, busy = false) }
                }
            }
        }
    }

    suspend fun permissionResult(receipt: AssociationNativeReceipt, granted: Boolean) {
        val ticket = state.value.ticket ?: return
        if (closed || receipt.kind != AssociationNativeKind.StoragePermission) return
        var accepted = false
        val persisted =
            controller(ticket).update(receipt.generation) {
                if (receipt !in it.claimedEffects) return@update it
                accepted = true
                val acknowledged = it.copy(claimedEffects = it.claimedEffects - receipt)
                if (granted) acknowledged.copy(storagePermissionGranted = true)
                else
                    finish(acknowledged)
                        .copy(
                            effects =
                                listOf(
                                    receipt(
                                        acknowledged,
                                        AssociationNativeKind.Finish,
                                        type = "permissionDenied",
                                    )
                                )
                        )
            } ?: return
        currentCoroutineContext().ensureActive()
        if (!accepted) return
        publish(ticket, persisted, busy = granted)
        if (granted && persisted.storagePermissionGranted) inspect(ticket, persisted)
    }

    suspend fun directoryResult(receipt: AssociationNativeReceipt, directory: String?) {
        val ticket = state.value.ticket ?: return
        if (closed || receipt.kind != AssociationNativeKind.SelectDirectory) return
        var accepted = false
        val persisted =
            controller(ticket).update(receipt.generation) {
                if (receipt !in it.claimedEffects) return@update it
                accepted = true
                it.copy(
                    phase = AssociationPhase.Preview,
                    choosingDirectory = false,
                    claimedEffects = it.claimedEffects - receipt,
                )
            } ?: return
        currentCoroutineContext().ensureActive()
        if (!accepted) return
        publish(ticket, persisted, busy = false)
        if (directory != null && persisted.importAfterDirectory)
            startConfirmedOperation("local-import", directory, currentCoroutineContext()[Job])
    }

    private suspend fun inspect(ticket: String, original: AssociationSession) {
        val controller = controller(ticket)
        try {
            val needsStorage =
                original.input.host == AssociationHostKind.File &&
                    original.input.kind == AssociationInputKind.View &&
                    original.input.uris.firstOrNull()?.let {
                        it.substringBefore(':') != "content"
                    } == true
            if (needsStorage && !original.storagePermissionGranted) {
                val waiting =
                    controller.update(original.generation) {
                        val hasRequest =
                            (it.effects + it.claimedEffects).any { request ->
                                request.kind == AssociationNativeKind.StoragePermission
                            }
                        it.copy(
                            phase = AssociationPhase.Ready,
                            operation = null,
                            effects =
                                if (hasRequest) it.effects
                                else
                                    it.effects +
                                        receipt(it, AssociationNativeKind.StoragePermission),
                        )
                    }
                waiting?.let { publish(ticket, it, busy = false) }
                return
            }
            val inspecting =
                controller.update(original.generation) {
                    it.copy(
                        phase = AssociationPhase.Loading,
                        error = null,
                        operation =
                            AssociationOperation(
                                UUID.randomUUID().toString(),
                                it.generation,
                                INSPECT_OPERATION,
                            ),
                    )
                } ?: return
            publish(ticket, inspecting, busy = true)
            val completed =
                if (inspecting.input.host == AssociationHostKind.Online) {
                    inspectOnline(ticket, inspecting)
                } else {
                    applyFileInspection(inspecting, files.inspect(ticket, inspecting.input))
                }
            currentCoroutineContext().ensureActive()
            if (closed) return
            val persisted =
                controller.update(inspecting.generation) {
                    completed.copy(revision = it.revision, operation = null)
                }
            persisted?.let { publish(ticket, it, busy = false) }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (closed) return
            val failed =
                controller.update(original.generation) {
                    it.copy(
                        phase = AssociationPhase.Failed,
                        operation = null,
                        error = failure.message ?: "格式不对",
                    )
                }
            failed?.let { publish(ticket, it, busy = false) }
        }
    }

    private suspend fun inspectOnline(
        ticket: String,
        session: AssociationSession,
    ): AssociationSession {
        val incomingUri = session.input.uris.singleOrNull() ?: return finish(session)
        val incoming = Uri.parse(incomingUri)
        val url = incoming.getQueryParameter("src")
        if (url.isNullOrEmpty()) return finish(session)
        val route = associationOnlineRoute(incoming.path, incoming.host)
        if (route.importType != null) return importDialog(session, route.importType, url)
        val payload =
            if (route.readConfig) online.readConfig(ticket, url) else online.determine(ticket, url)
        return applyOnlinePayload(session, payload)
    }

    private fun applyOnlinePayload(
        session: AssociationSession,
        payload: AssociationOnlinePayload,
    ): AssociationSession {
        payload.readConfigFile?.let { filename ->
            return session.copy(phase = AssociationPhase.ReadConfig, readConfigFile = filename)
        }
        return importDialog(session, checkNotNull(payload.importType), checkNotNull(payload.source))
    }

    private fun applyFileInspection(
        session: AssociationSession,
        result: AssociationFileInspection,
    ): AssociationSession {
        if (result.finished) return finish(session)
        result.onlineUri?.let { uri ->
            return session.copy(
                phase = AssociationPhase.Finished,
                effects =
                    listOf(receipt(session, AssociationNativeKind.OnlineImport, payload = uri)),
            )
        }
        result.importType?.let { type ->
            return importDialog(session, type, checkNotNull(result.source))
        }
        result.unsupportedUri?.let { uri ->
            return session.copy(
                phase = AssociationPhase.Unsupported,
                unsupportedUri = uri,
                unsupportedName = result.unsupportedName,
            )
        }
        val staging = checkNotNull(result.staging)
        if (staging.mixedTypes) return session.copy(phase = AssociationPhase.MixedTypes)
        staging.importType?.let { type ->
            return importDialog(session, type, checkNotNull(staging.importSource))
        }
        check(staging.previews.isNotEmpty()) { "没有可导入的书籍" }
        return session.copy(
            phase =
                if (result.openSingleBook) AssociationPhase.Directory else AssociationPhase.Preview,
            previews = staging.previews,
            selectedIds = staging.previews.map { it.id },
            openSingleBook = result.openSingleBook,
        )
    }

    private fun importDialog(
        session: AssociationSession,
        type: String,
        source: String,
    ): AssociationSession =
        session.copy(
            phase = AssociationPhase.Preview,
            importType = type,
            importSource = source,
            effects = listOf(receipt(session, AssociationNativeKind.ImportDialog, type, source)),
        )

    private fun finish(session: AssociationSession): AssociationSession =
        session.copy(
            phase = AssociationPhase.Finished,
            effects = listOf(receipt(session, AssociationNativeKind.Finish)),
        )

    private fun receipt(
        session: AssociationSession,
        kind: AssociationNativeKind,
        type: String? = null,
        payload: String? = null,
    ) =
        AssociationNativeReceipt(
            UUID.randomUUID().toString(),
            session.generation,
            kind,
            type,
            payload,
        )

    private fun publish(ticket: String, session: AssociationSession, busy: Boolean) {
        if (closed || state.value.ticket != ticket) return
        mutableState.value =
            AssociationImportState(
                loaded = true,
                ticket = ticket,
                session = session,
                busy = busy,
                nativeResultPending = state.value.nativeResultPending,
            )
    }

    override fun onCleared() {
        closed = true
        operation?.cancel()
        super.onCleared()
    }

    companion object {
        const val TICKET_KEY = "association.ticket"
        private const val INSPECT_OPERATION = "inspect"
        private val REPLAY_SAFE_OPERATIONS = setOf("local-import", "bookshelf", "read-config")
    }
}
