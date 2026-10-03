package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.code.CodeEditActivity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class JsSourceEditState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val stage: JsSourceEditStage = JsSourceEditStage.READY,
    val editorPath: String? = null,
    val sourceUrl: String? = null,
    val finished: Boolean = false,
    val saved: Boolean = false,
    val missingLogin: Boolean = false,
    val successToast: Boolean = false,
)

internal class JsSourceEditViewModel(
    private val repository: JsSourceEditRepository,
    savedState: SavedStateHandle,
    private val initialSourceUrl: String?,
) : ViewModel() {
    private val sessionId =
        savedState.get<String>("jsSourceDraftId")
            ?: UUID.randomUUID().toString().also { savedState["jsSourceDraftId"] = it }
    private var draft: JsSourceDraft? = null
    private var pendingAcceptedReceipt: JsSourceDraft? = null
    private var pendingEditorReceipt: JsSourceDraft? = null
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(JsSourceEditState())
    val state = mutableState.asStateFlow()

    init {
        load()
    }

    private fun publish(error: String? = null) {
        val currentDraft = draft ?: return
        mutableState.value =
            JsSourceEditState(
                loaded = true,
                error = error,
                stage = currentDraft.stage,
                editorPath = currentDraft.editorPath,
                sourceUrl = currentDraft.sourceUrl,
                finished = currentDraft.finished,
                saved = currentDraft.saved,
                missingLogin = currentDraft.missingLogin,
                successToast = currentDraft.successToast,
            )
    }

    private suspend fun checkpoint(updatedDraft: JsSourceDraft) {
        val nextRevision = updatedDraft.copy(revision = (draft?.revision ?: -1) + 1)
        repository.write(sessionId, nextRevision)
        draft = nextRevision
    }

    private fun runOperation(block: suspend () -> Unit) = viewModelScope.launch {
        operationMutex.withLock {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                block()
                publish(mutableState.value.error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        error = error.localizedMessage ?: "Error",
                    )
            }
        }
    }

    fun load() {
        runOperation {
            if (draft == null) {
                draft = repository.read(sessionId)
                if (draft == null) {
                    checkpoint(
                        JsSourceDraft(
                            text = repository.initial(initialSourceUrl),
                            sourceUrl = initialSourceUrl,
                            stage = JsSourceEditStage.READY,
                        )
                    )
                }
            }
            if (pendingEditorReceipt != null) {
                checkpoint(pendingEditorReceipt!!)
                pendingEditorReceipt = null
            }
            if (pendingAcceptedReceipt != null) {
                persistAcceptedReceipt()
                finalizeSavedDraft()
            } else if (draft?.editorReturning == true) {
                completeEditorReturn()
            } else {
                resumePendingOperation()
            }
        }
    }

    private suspend fun resumePendingOperation() {
        val currentDraft = draft ?: return
        if (currentDraft.finished) {
            releaseOwnedTransfers(currentDraft)
            return
        }
        when (currentDraft.stage.restoreAction()) {
            JsSourceEditRestoreAction.OPEN_EDITOR -> prepareEditor(currentDraft)
            JsSourceEditRestoreAction.SAVE_AND_FINISH,
            JsSourceEditRestoreAction.SAVE_FOR_DEBUG,
            JsSourceEditRestoreAction.SAVE_FOR_LOGIN -> saveSource()
            else -> Unit
        }
    }

    private suspend fun prepareEditor(currentDraft: JsSourceDraft) {
        if (currentDraft.editorPath != null) return
        val editorPath = repository.transfer(currentDraft.text)
        try {
            checkpoint(
                currentDraft.copy(
                    editorPath = editorPath,
                    ownedTransfers = (currentDraft.ownedTransfers + editorPath).distinct(),
                )
            )
        } catch (error: Throwable) {
            repository.release(editorPath)
            throw error
        }
    }

    suspend fun deliverLaunch(
        destination: JsSourceEditStage,
        canLaunch: () -> Boolean,
        launch: (JsSourceEditState) -> Unit,
    ): Boolean =
        withContext(NonCancellable) {
            // Once the launch checkpoint is accepted, lifecycle cancellation must not strand it.
            // A paused host rolls back the checkpoint, retaining the same prepared transfer file.
            operationMutex.withLock {
                val currentDraft = draft ?: return@withLock false
                val expectedStage =
                    when (destination) {
                        JsSourceEditStage.EDITOR_OPEN -> JsSourceEditStage.READY
                        JsSourceEditStage.DEBUG_OPEN -> JsSourceEditStage.DEBUG_READY
                        JsSourceEditStage.LOGIN_OPEN -> JsSourceEditStage.LOGIN_READY
                        else -> return@withLock false
                    }
                if (currentDraft.stage != expectedStage || currentDraft.finished) {
                    return@withLock false
                }
                checkpoint(currentDraft.copy(stage = destination, missingLogin = false))
                publish()
                if (!canLaunch()) {
                    checkpoint(currentDraft)
                    publish()
                    return@withLock false
                }
                try {
                    launch(state.value.copy(missingLogin = currentDraft.missingLogin))
                    true
                } catch (error: Exception) {
                    checkpoint(currentDraft)
                    publish(error.localizedMessage ?: "Error")
                    false
                }
            }
        }

    fun editorReturned(ok: Boolean, text: String?, path: String?, action: String?) {
        runOperation {
            val currentDraft = draft ?: return@runOperation
            if (currentDraft.finished || currentDraft.stage != JsSourceEditStage.EDITOR_OPEN) {
                return@runOperation
            }
            if (!ok || (text == null && path == null)) {
                finishDraft(currentDraft)
                return@runOperation
            }
            val editorReceipt =
                currentDraft.copy(
                    editorReturning = true,
                    returnedText = text,
                    returnedPath = path,
                    returnedAction = action,
                    ownedTransfers = (currentDraft.ownedTransfers + listOfNotNull(path)).distinct(),
                )
            pendingEditorReceipt = editorReceipt
            checkpoint(editorReceipt)
            pendingEditorReceipt = null
            completeEditorReturn()
        }
    }

    private suspend fun completeEditorReturn() {
        val editorReceipt = draft ?: return
        if (!editorReceipt.editorReturning) return
        val editorText =
            editorReceipt.returnedPath?.let { repository.editorText(it) }
                ?: editorReceipt.returnedText
                ?: error("未获取到待编辑文本")
        checkpoint(
            editorReceipt.copy(
                text = editorText,
                editorPath = null,
                editorReturning = false,
                returnedText = null,
                returnedPath = null,
                returnedAction = null,
                stage =
                    stageForEditorResult(
                        debugRequested =
                            editorReceipt.returnedAction ==
                                CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE,
                        loginRequested =
                            editorReceipt.returnedAction ==
                                CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE,
                    ),
            )
        )
        repository.release(editorReceipt.editorPath)
        if (editorReceipt.returnedPath != editorReceipt.editorPath)
            repository.release(editorReceipt.returnedPath)
        saveSource()
    }

    private suspend fun saveSource() {
        val currentDraft = draft ?: return
        var accepted = false
        try {
            repository.save(currentDraft.text, currentDraft.sourceUrl) { acceptedSource ->
                // Retain the immutable accepted result if private storage fails. Retry must write
                // this receipt rather than re-run user JS or repeat a committed Room mutation.
                accepted = true
                pendingAcceptedReceipt = acceptedReceipt(currentDraft, acceptedSource)
                persistAcceptedReceipt()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!accepted && pendingAcceptedReceipt == null) {
                // Parsing errors return to an editable draft, so users can fix the script.
                checkpoint(currentDraft.copy(stage = JsSourceEditStage.READY))
                resumePendingOperation()
            }
            publish(error.localizedMessage ?: "Error")
            return
        }
        finalizeSavedDraft()
    }

    private fun acceptedReceipt(currentDraft: JsSourceDraft, source: BookSource): JsSourceDraft {
        val requestedStage = currentDraft.stage.afterSuccessfulSave()
        val missingLogin = requestedStage == JsSourceEditStage.LOGIN_READY && !source.hasLogin()
        val nextStage = if (missingLogin) JsSourceEditStage.READY else requestedStage
        val finishAfterSave = currentDraft.stage == JsSourceEditStage.SAVING
        return currentDraft.copy(
            text = if (finishAfterSave) "" else source.mainJs ?: currentDraft.text,
            sourceUrl = source.bookSourceUrl,
            stage = nextStage,
            finished = finishAfterSave,
            saved = true,
            missingLogin = missingLogin,
            successToast = finishAfterSave,
        )
    }

    private suspend fun persistAcceptedReceipt() {
        val acceptedReceipt = pendingAcceptedReceipt ?: return
        checkpoint(acceptedReceipt)
        pendingAcceptedReceipt = null
    }

    private suspend fun finalizeSavedDraft() {
        val currentDraft = draft ?: return
        if (currentDraft.finished) {
            releaseOwnedTransfers(currentDraft)
        } else if (currentDraft.stage == JsSourceEditStage.READY) {
            prepareEditor(currentDraft)
        }
        publish()
    }

    fun returned(stage: JsSourceEditStage) {
        runOperation {
            val currentDraft = draft ?: return@runOperation
            if (currentDraft.stage != stage || currentDraft.finished) return@runOperation
            val nextStage =
                if (stage == JsSourceEditStage.DEBUG_OPEN) {
                    stage.afterDebugResult()
                } else {
                    stage.afterLoginResult()
                }
            checkpoint(currentDraft.copy(stage = nextStage))
            resumePendingOperation()
        }
    }

    fun cancel() {
        runOperation {
            if (pendingAcceptedReceipt != null) persistAcceptedReceipt()
            val currentDraft = draft ?: return@runOperation
            val closingDraft =
                currentDraft.copy(
                    ownedTransfers =
                        (currentDraft.ownedTransfers +
                                pendingEditorReceipt?.ownedTransfers.orEmpty())
                            .distinct()
                )
            finishDraft(closingDraft)
            pendingEditorReceipt = null
        }
    }

    private suspend fun finishDraft(currentDraft: JsSourceDraft) {
        checkpoint(
            currentDraft.copy(
                text = "",
                editorPath = null,
                returnedText = null,
                returnedPath = null,
                returnedAction = null,
                editorReturning = false,
                finished = true,
            )
        )
        releaseOwnedTransfers(currentDraft)
    }

    private suspend fun releaseOwnedTransfers(currentDraft: JsSourceDraft) {
        currentDraft.ownedTransfers.forEach { path -> repository.release(path) }
    }
}
