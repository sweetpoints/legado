package io.legado.app.ui.association.compose

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationNativeReceipt
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationOperation
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionController
import io.legado.app.data.association.AssociationSessionRepository
import io.legado.app.data.association.associationOnlineRoute
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssociationImportState(
    val loaded: Boolean = false,
    val ticket: String? = null,
    val session: AssociationSession? = null,
    val busy: Boolean = false,
    val restoreError: String? = null,
)

/** Each host owns one private UUID; providers, JSON and complete book metadata stay off Bundle. */
class AssociationImportViewModel(
    private val savedState: SavedStateHandle,
    private val sessions: AssociationSessionRepository,
    private val files: AssociationFileRepository,
    private val online: AssociationOnlineRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AssociationImportState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var closed = false

    init {
        savedState.get<String>(TICKET_KEY)?.let { ticket ->
            operation = viewModelScope.launch {
                try {
                    val restored = sessions.read(ticket)
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
                        )
                    // Reading/staging is repeatable. An accepted import operation is resumed only
                    // by its dedicated journal, never by rerunning the first incoming Intent.
                    if (needsInspection) {
                        inspect(ticket, restored)
                    }
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

    private suspend fun inspect(ticket: String, original: AssociationSession) {
        val controller = AssociationSessionController(ticket, sessions)
        try {
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
        check(!staging.mixedTypes) { "不能同时导入不同类型的文件" }
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
            AssociationImportState(loaded = true, ticket = ticket, session = session, busy = busy)
    }

    override fun onCleared() {
        closed = true
        operation?.cancel()
        super.onCleared()
    }

    companion object {
        const val TICKET_KEY = "association.ticket"
        private const val INSPECT_OPERATION = "inspect"
    }
}
