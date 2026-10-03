package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.HandleFileCheckpoint
import io.legado.app.data.repository.HandleFileChoicesRepository
import io.legado.app.data.repository.HandleFileChoicesSessionRepository
import io.legado.app.data.repository.HandleFileInput
import io.legado.app.data.repository.HandleFileIssue
import io.legado.app.data.repository.HandleFileIssueException
import io.legado.app.data.repository.HandleFilePending
import io.legado.app.data.repository.HandleFileSeed
import io.legado.app.data.repository.handleFileChoiceValues
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Immutable UI projection; all unrestricted strings are owned by the private session. */
data class HandleFileChoicesState(
    val loaded: Boolean = false,
    val input: HandleFileInput? = null,
    val phase: String = "Choices",
    val draft: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val pending: HandleFilePending? = null,
    val result: String? = null,
    val busy: Boolean = false,
    val issue: HandleFileIssue? = null,
    val error: String? = null,
    val finished: Boolean = false,
)

class HandleFileChoicesViewModel(
    private val savedState: SavedStateHandle,
    private val repository: HandleFileChoicesRepository,
    private val sessionRepository: HandleFileChoicesSessionRepository,
) : ViewModel() {
    val sessionId: String =
        savedState.get<String>("handleFile.session")
            ?: UUID.randomUUID().toString().also { savedState["handleFile.session"] = it }
    private val mutableState = MutableStateFlow(HandleFileChoicesState())
    val state = mutableState.asStateFlow()
    private var checkpoint = HandleFileCheckpoint()
    private var initialSeed: HandleFileSeed? = null
    private var activeJob: Job? = null
    private var draftWriteJob: Job? = null
    // A receipt write failure retains the accepted outcome: retry must not upload or write again.
    private var acceptedResult: HandleFileCheckpoint? = null
    private var earlyResult: Pair<String, String?>? = null
    private var currentGeneration = 0L
    private var stopped = false

    fun load(value: HandleFileSeed? = null) {
        if (stopped || state.value.finished || state.value.loaded || state.value.busy) return
        if (value != null) initialSeed = value
        val loadGeneration = ++currentGeneration
        mutableState.value = state.value.copy(busy = true, issue = null, error = null)
        activeJob = viewModelScope.launch {
            try {
                val input =
                    initialSeed?.let { sessionRepository.stage(sessionId, it) }
                        ?: sessionRepository.input(sessionId)
                        ?: throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
                currentCoroutineContext().ensureActive()
                if (loadGeneration != currentGeneration || stopped) return@launch
                initialSeed = null
                checkpoint = sessionRepository.read(sessionId) ?: HandleFileCheckpoint()
                currentCoroutineContext().ensureActive()
                if (loadGeneration != currentGeneration || stopped) return@launch
                checkpoint =
                    checkpoint.copy(
                        revision =
                            maxOf(
                                checkpoint.revision,
                                savedState.get<Long>("handleFile.revision") ?: 0,
                            )
                    )
                mutableState.value =
                    HandleFileChoicesState(
                        loaded = true,
                        input = input,
                        phase = checkpoint.phase,
                        draft = checkpoint.draft,
                        start = checkpoint.start,
                        end = checkpoint.end,
                        pending = checkpoint.pending,
                        result = checkpoint.result,
                        finished = checkpoint.finished,
                    )
                val early = earlyResult
                earlyResult = null
                if (early != null) {
                    returned(early.first, early.second)
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (loadGeneration == currentGeneration && !stopped) failure(error)
            }
        }
    }

    fun choose(action: Int, customTitle: String? = null) {
        if (!ready() || state.value.phase != "Choices") return
        operation {
            val pending = HandleFilePending(action, UUID.randomUUID().toString())
            if (
                action !in handleFileChoiceValues(state.value.input!!.mode) && customTitle != null
            ) {
                // Custom path parsing is deferred to the platform host; its exact title is private.
                persist(checkpoint.copy(phase = "Native", draft = customTitle, pending = pending))
            } else {
                persist(
                    checkpoint.copy(
                        phase = if (action == 111) "Uploading" else "Native",
                        pending = pending,
                    )
                )
                if (action == 111) upload()
            }
        }
    }

    /**
     * The launch receipt is durable before a platform picker, permission request or manual editor.
     */
    suspend fun nativeDelivered(nonce: String): Boolean {
        val pending = state.value.pending ?: return false
        if (
            !ready() || pending.nonce != nonce || pending.delivered || state.value.phase != "Native"
        )
            return false
        mutableState.value = state.value.copy(busy = true)
        try {
            draftWriteJob?.join()
            persist(checkpoint.copy(pending = pending.copy(delivered = true)))
            return true
        } finally {
            if (!stopped) mutableState.value = state.value.copy(busy = false)
        }
    }

    fun manualReady(nonce: String) {
        val pending = state.value.pending ?: return
        if (
            !ready() ||
                state.value.phase != "Native" ||
                pending.nonce != nonce ||
                pending.action !in listOf(112, 113)
        )
            return
        operation { persist(checkpoint.copy(phase = "Manual")) }
    }

    fun text(value: String, start: Int, end: Int) {
        if (!ready() || state.value.phase != "Manual") return
        val selectionStart = start.coerceIn(0, value.length)
        val selectionEnd = end.coerceIn(0, value.length)
        mutableState.value =
            state.value.copy(
                draft = value,
                start = selectionStart,
                end = selectionEnd,
                issue = null,
                error = null,
            )
        draftWriteJob?.cancel()
        val previous = draftWriteJob
        draftWriteJob = viewModelScope.launch {
            previous?.join()
            try {
                persist(
                    checkpoint.copy(draft = value, start = selectionStart, end = selectionEnd),
                    publish = false,
                )
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failure(error, keepBusy = activeJob?.isActive == true)
            }
        }
    }

    fun confirmManual() {
        if (!ready() || state.value.phase != "Manual") return
        operation {
            try {
                val uri = repository.manual(state.value.draft, state.value.pending?.action == 113)
                currentCoroutineContext().ensureActive()
                accept(uri)
            } catch (error: HandleFileIssueException) {
                // The original manual dialog dismisses after either valid or invalid confirmation.
                finish()
                throw error
            }
        }
    }

    fun returned(nonce: String, uri: String?) {
        if (!state.value.loaded) {
            if (!stopped) earlyResult = nonce to uri
            return
        }
        val pending = state.value.pending ?: return
        if (!ready() || pending.nonce != nonce || state.value.phase != "Native") return
        operation {
            if (uri == null) {
                finish()
            } else {
                accept(uri)
            }
        }
    }

    /** System-picker failures use the original app-picker fallback with a distinct owner nonce. */
    fun fallback(nonce: String) {
        val pending = state.value.pending ?: return
        if (
            !ready() ||
                state.value.phase != "Native" ||
                pending.nonce != nonce ||
                pending.action !in listOf(0, 1)
        )
            return
        operation {
            val fallbackAction = if (pending.action == 0) 10 else 11
            val fallback = HandleFilePending(fallbackAction, UUID.randomUUID().toString())
            persist(checkpoint.copy(pending = fallback))
        }
    }

    fun retry() {
        if (stopped || state.value.busy || state.value.finished) return
        if (!state.value.loaded) {
            load()
            return
        }
        when {
            acceptedResult != null ->
                operation {
                    val receipt = checkNotNull(acceptedResult)
                    sessionRepository.write(sessionId, receipt)
                    currentCoroutineContext().ensureActive()
                    publish(receipt)
                    acceptedResult = null
                }
            state.value.phase == "Uploading" -> operation { upload() }
            state.value.phase == "Saving" && checkpoint.result != null ->
                operation {
                    accept(checkNotNull(checkpoint.result))
                }
            else -> mutableState.value = state.value.copy(issue = null, error = null)
        }
    }

    fun close() {
        if (ready()) operation { finish() }
    }

    /** Host acknowledges before setResult/finish, preventing a recreated host from replaying it. */
    suspend fun resultDelivered(): Boolean {
        if (!ready() || state.value.phase != "Result" || state.value.result == null) return false
        draftWriteJob?.join()
        finish()
        return true
    }

    private suspend fun accept(uri: String) {
        val input = state.value.input!!
        if (input.mode == 3 && state.value.pending?.action != 111) {
            persist(checkpoint.copy(phase = "Saving", result = uri))
            val name =
                input.fileName ?: throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
            val base = checkpoint
            repository.saveRecorded(uri, name, sessionRepository.bytes(sessionId)) { result ->
                val receipt =
                    base.copy(
                        revision = base.revision + 1,
                        phase = "Result",
                        result = result,
                    )
                acceptedResult = receipt
                sessionRepository.write(sessionId, receipt)
            }
            currentCoroutineContext().ensureActive()
            publish(checkNotNull(acceptedResult))
            acceptedResult = null
        } else {
            persist(checkpoint.copy(phase = "Result", result = uri))
        }
    }

    private suspend fun upload() {
        val input = state.value.input!!
        val name = input.fileName ?: throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
        val type =
            input.contentType ?: throw HandleFileIssueException(HandleFileIssue.PayloadMissing)
        val base = checkpoint
        val bytes = sessionRepository.bytes(sessionId)
        val recordSuccess: suspend (String) -> Unit = { result ->
            val receipt =
                base.copy(
                    revision = base.revision + 1,
                    phase = "Result",
                    result = result,
                )
            acceptedResult = receipt
            sessionRepository.write(sessionId, receipt)
        }
        val sourceFileName = input.sourceFileName
        if (sourceFileName == null) {
            repository.uploadRecorded(name, bytes, type, recordSuccess)
        } else {
            repository.uploadFileRecorded(name, sourceFileName, bytes, type, recordSuccess)
        }
        currentCoroutineContext().ensureActive()
        publish(checkNotNull(acceptedResult))
        acceptedResult = null
    }

    private suspend fun finish() {
        persist(checkpoint.copy(finished = true))
    }

    private fun ready(): Boolean =
        !stopped && state.value.loaded && !state.value.busy && !state.value.finished

    private suspend fun persist(next: HandleFileCheckpoint, publish: Boolean = true) {
        val value = next.copy(revision = checkpoint.revision + 1)
        sessionRepository.write(sessionId, value)
        currentCoroutineContext().ensureActive()
        publish(value, publish)
    }

    private fun publish(value: HandleFileCheckpoint, visible: Boolean = true) {
        checkpoint = value
        savedState["handleFile.revision"] = value.revision
        if (visible && !stopped) {
            mutableState.value =
                state.value.copy(
                    phase = value.phase,
                    draft = value.draft,
                    start = value.start,
                    end = value.end,
                    pending = value.pending,
                    result = value.result,
                    finished = value.finished,
                )
        }
    }

    private fun operation(block: suspend () -> Unit) {
        mutableState.value = state.value.copy(busy = true, issue = null, error = null)
        activeJob = viewModelScope.launch {
            try {
                draftWriteJob?.join()
                block()
                currentCoroutineContext().ensureActive()
                if (!stopped) mutableState.value = state.value.copy(busy = false)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!stopped) failure(error)
            }
        }
    }

    private fun failure(error: Throwable, keepBusy: Boolean = false) {
        mutableState.value =
            state.value.copy(
                busy = keepBusy,
                issue = (error as? HandleFileIssueException)?.issue,
                error =
                    if (error is HandleFileIssueException) {
                        null
                    } else {
                        error.localizedMessage ?: error.javaClass.simpleName
                    },
            )
    }

    fun stop() {
        stopped = true
        ++currentGeneration
        activeJob?.cancel()
        draftWriteJob?.cancel()
    }

    override fun onCleared() {
        stop()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sessionRepository.release(sessionId)
        }
    }
}
