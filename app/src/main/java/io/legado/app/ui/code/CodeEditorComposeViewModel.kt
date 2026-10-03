package io.legado.app.ui.code

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CodeEditorComposeState(
    val session: CodeEditorSession? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val confirmDiscard: Boolean = false,
    val editorReady: Boolean = false,
    val engineOwner: String? = null,
)

/** Owns private text snapshots; Sora and safe WebView algorithms remain in their engine bridges. */
internal class CodeEditorComposeViewModel(
    private val repository: CodeEditorSessionRepository,
    savedState: SavedStateHandle,
    launch: CodeEditorLaunch?,
) : ViewModel() {
    private val restored = savedState.contains("codeEditorSessionId")
    private val sessionId =
        savedState.get<String>("codeEditorSessionId")
            ?: UUID.randomUUID().toString().also { savedState["codeEditorSessionId"] = it }
    private var launchSeed = launch.takeUnless { restored }
    private var pendingBootstrap: CodeEditorSession? = null
    private var session: CodeEditorSession? = null
    private var pendingReturnPayload: CodeEditorResultPayload? = null
    private var pendingAcceptedClose: CodeEditorSession? = null
    private val operationMutex = Mutex()
    private val writeMutex = Mutex()
    private val mutableState = MutableStateFlow(CodeEditorComposeState())
    val state = mutableState.asStateFlow()

    init {
        retryInternal(manual = false)
    }

    private fun publish(error: String? = mutableState.value.error) {
        mutableState.value = mutableState.value.copy(session = session, busy = false, error = error)
    }

    private fun invalidateCachedSession() {
        session = null
        pendingBootstrap = null
        pendingReturnPayload = null
        pendingAcceptedClose = null
        mutableState.value =
            mutableState.value.copy(session = null, editorReady = false, engineOwner = null)
    }

    private fun requireAccepted(accepted: Boolean) {
        if (accepted) return
        invalidateCachedSession()
        throw CodeEditorSessionConflict()
    }

    private suspend fun flush() = writeMutex.withLock {
        session?.let { requireAccepted(repository.write(sessionId, it)) }
    }

    private suspend fun checkpoint(updated: CodeEditorSession) {
        val next = updated.copy(revision = (session?.revision ?: -1) + 1)
        writeMutex.withLock { requireAccepted(repository.write(sessionId, next)) }
        session = next
    }

    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        operationMutex.withLock {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                flush()
                block()
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CodeEditorSessionConflict) invalidateCachedSession()
                publish(error.localizedMessage ?: "无法保存代码草稿")
            }
        }
    }

    fun retry() = retryInternal(manual = true)

    private fun retryInternal(manual: Boolean) {
        operation {
            pendingAcceptedClose?.let {
                checkpoint(it)
                pendingAcceptedClose = null
                return@operation
            }
            if (session == null) {
                val durable = repository.read(sessionId)
                if (durable != null) {
                    session = durable
                    launchSeed = null
                    pendingBootstrap = null
                } else {
                    check(!restored) { "代码编辑草稿已丢失，请关闭后重新打开原入口" }
                    val seed =
                        pendingBootstrap
                            ?: repository.loadLaunch(checkNotNull(launchSeed)).also {
                                pendingBootstrap = it
                                launchSeed = null
                            }
                    requireAccepted(repository.write(sessionId, seed))
                    session = seed
                    pendingBootstrap = null
                }
            }
            if (mutableState.value.engineOwner == null && session != null) {
                mutableState.value =
                    mutableState.value.copy(engineOwner = UUID.randomUUID().toString())
            }
            val current = session ?: return@operation
            if (current.finished) return@operation
            val receipt = current.returnReceipt ?: return@operation
            if (receipt.claimed) {
                if (!manual) {
                    publish("代码返回交付中断，请确认调用页面状态后点击重试；此前可能已返回结果")
                    return@operation
                }
                checkpoint(current.copy(returnReceipt = receipt.copy(claimed = false)))
            }
            prepareReturnPayload()
        }
    }

    private fun edit(updated: CodeEditorSession) {
        session = updated.copy(revision = (session?.revision ?: -1) + 1)
        publish(error = null)
        viewModelScope.launch {
            try {
                flush()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value =
                    mutableState.value.copy(
                        session = session,
                        error = error.localizedMessage ?: "无法保存代码草稿",
                    )
            }
        }
    }

    fun restartEngine(owner: String) {
        if (owner != state.value.engineOwner || state.value.busy) return
        operation {
            val current = session ?: return@operation
            if (current.finished || current.returnReceipt != null) return@operation
            mutableState.value =
                mutableState.value.copy(
                    engineOwner = UUID.randomUUID().toString(),
                    editorReady = false,
                )
        }
    }

    fun editorReady(owner: String, ready: Boolean) {
        if (owner != state.value.engineOwner) return
        mutableState.value = mutableState.value.copy(editorReady = ready)
    }

    fun updateEditor(
        owner: String,
        text: String,
        start: Int,
        end: Int,
        programmatic: Boolean = false,
    ) {
        if (owner != state.value.engineOwner) return
        val current = session ?: return
        if (state.value.busy || current.finished || current.returnReceipt != null) return
        if (!current.writable && text != current.text && !programmatic) return
        val next = current.edited(text, CodeEditorSelection(start, end))
        if (next != current) edit(next)
    }

    fun search(owner: String, search: CodeEditorSearch) {
        if (owner != state.value.engineOwner) return
        val current = session ?: return
        if (state.value.busy || current.finished || current.returnReceipt != null) return
        edit(current.copy(search = search))
    }

    fun keepEditing() {
        mutableState.value = mutableState.value.copy(confirmDiscard = false)
    }

    fun requestExit() {
        val current = session ?: return
        if (state.value.busy) return
        if (current.writable && current.dirty) {
            mutableState.value = mutableState.value.copy(confirmDiscard = true)
        } else {
            requestReturn(includeText = false, action = null)
        }
    }

    fun discard() {
        mutableState.value = mutableState.value.copy(confirmDiscard = false)
        val current = session ?: return
        if (current.returnReceipt == null) {
            requestReturn(includeText = false, action = null)
        } else {
            operation {
                val pending = session ?: return@operation
                val receipt = pending.returnReceipt
                checkpoint(pending.closed())
                // A claimed transfer may already belong to the caller. Only an unclaimed
                // abandoned output can be removed by the editor that created it.
                if (receipt?.claimed == false) repository.releaseOutput(receipt.textFile)
            }
        }
    }

    fun save(action: String? = null) {
        val current = session ?: return
        if (state.value.busy || !current.writable) return
        requestReturn(
            includeText = current.dirty || current.returnUnchangedText || action != null,
            action = action,
        )
    }

    private fun requestReturn(includeText: Boolean, action: String?) {
        operation {
            val current = session ?: return@operation
            if (current.finished || current.returnReceipt != null) return@operation
            val cursor = minOf(current.selection.start, current.selection.end)
            if (!current.writable || (!includeText && cursor == 0 && action == null)) {
                checkpoint(current.closed())
                return@operation
            }
            val receiptId = UUID.randomUUID().toString()
            val receipt =
                CodeEditorReturnReceipt(
                    id = receiptId,
                    cursorPosition = cursor,
                    includeText = includeText,
                    action = action,
                    textFile =
                        if (includeText && current.useTextFile)
                            repository.returnFile(sessionId, receiptId)
                        else null,
                )
            checkpoint(current.copy(returnReceipt = receipt))
            prepareReturnPayload()
        }
    }

    private suspend fun prepareReturnPayload() {
        val current = session ?: return
        val receipt = current.returnReceipt ?: return
        val payload =
            pendingReturnPayload
                ?: repository.prepareOutput(sessionId, current).also { pendingReturnPayload = it }
        if (!receipt.prepared)
            checkpoint(current.copy(returnReceipt = receipt.copy(prepared = true)))
        pendingReturnPayload = payload
    }

    suspend fun deliverReturn(
        id: String,
        canDeliver: () -> Boolean,
        deliver: (CodeEditorResultPayload) -> Unit,
    ): Boolean =
        withContext(NonCancellable) {
            operationMutex.withLock {
                val current = session ?: return@withLock false
                val receipt = current.returnReceipt ?: return@withLock false
                if (receipt.id != id || receipt.claimed || !receipt.prepared || current.finished)
                    return@withLock false
                val payload = pendingReturnPayload ?: return@withLock false
                mutableState.value = mutableState.value.copy(busy = true)
                try {
                    checkpoint(current.copy(returnReceipt = receipt.copy(claimed = true)))
                    if (!canDeliver()) {
                        checkpoint(current)
                        publish()
                        return@withLock false
                    }
                    deliver(payload)
                } catch (error: Exception) {
                    if (error !is CodeEditorSessionConflict) runCatching { checkpoint(current) }
                    publish(error.localizedMessage)
                    return@withLock false
                }
                // Main accepted the public result. Receipt cleanup retries must never deliver it
                // again in this live controller; a process-restored claimed receipt is manual.
                pendingAcceptedClose = session!!.closed()
                pendingReturnPayload = null
                try {
                    checkpoint(pendingAcceptedClose!!)
                    pendingAcceptedClose = null
                    publish()
                } catch (error: Exception) {
                    publish(error.localizedMessage)
                }
                true
            }
        }
}
