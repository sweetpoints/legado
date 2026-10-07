package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceEngineSourcePolicy
import io.legado.app.model.sourceEngine.SourceMigrationPreview
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class BookSourceMigrationReport(
    val revision: Long,
    val preview: SourceMigrationPreview,
)

internal data class BookSourceComposeState(
    val document: BookSourceEditDocument? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val confirmDiscard: Boolean = false,
    val groups: List<String>? = null,
    val variable: String? = null,
    val variableComment: String? = null,
    val assists: List<BookSourceKeyboardAssist> = emptyList(),
    val keyboardRows: Int? = null,
    val migrationAvailable: Boolean = BuildConfig.FLUTTER_SOURCE_ENGINE,
    val migrationRunning: Boolean = false,
    val migrationReport: BookSourceMigrationReport? = null,
    val migrationError: String? = null,
)

internal class BookSourceComposeViewModel(
    private val repository: BookSourceEditorRepository,
    savedState: SavedStateHandle,
    private val sourceUrl: String?,
    private val migrationAvailable: Boolean = BuildConfig.FLUTTER_SOURCE_ENGINE,
    private val migrateSource: suspend (BookSource) -> SourceMigrationPreview =
        DartSourceEngine::migrate,
) : ViewModel() {
    private val sessionId =
        savedState.get<String>("bookSourceDraftId")
            ?: UUID.randomUUID().toString().also { savedState["bookSourceDraftId"] = it }
    private val operationMutex = Mutex()
    private val writeMutex = Mutex()
    private val mutableState =
        MutableStateFlow(BookSourceComposeState(migrationAvailable = migrationAvailable))
    val state = mutableState.asStateFlow()
    private var migrationRequest = 0L
    private var draft: BookSourceEditDocument? = null
    private var pendingSave = false
    private var pendingNativeRollback: BookSourceEditDocument? = null
    private var pendingNativeReceipt: BookSourceEditDocument? = null
    private var pendingNativeResult: BookSourceEditDocument? = null

    init {
        retryInternal(manual = false)
        viewModelScope.launch {
            try {
                repository.assists().collect { assists ->
                    mutableState.value = mutableState.value.copy(assists = assists)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = error.localizedMessage)
            }
        }
    }

    private fun publish(error: String? = mutableState.value.error) {
        mutableState.value =
            mutableState.value.copy(
                document = draft,
                busy = false,
                error = error,
                variable = draft?.variableDraft,
                variableComment = draft?.variableComment,
            )
    }

    private fun edit(updated: BookSourceEditDocument) {
        val next = updated.copy(revision = (draft?.revision ?: -1) + 1)
        draft = next
        publish(error = null)
        viewModelScope.launch {
            try {
                flush()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value =
                    mutableState.value.copy(error = error.localizedMessage ?: "无法保存草稿")
            }
        }
    }

    private suspend fun flush() {
        writeMutex.withLock {
            draft?.let { requireAccepted(repository.writeDraft(sessionId, it)) }
        }
    }

    private fun requireAccepted(accepted: Boolean) {
        if (accepted) return
        // The private writer rejected this cached generation. Never use it to authorize a
        // launch, replay a result, or roll back the newer durable owner's payload.
        invalidateCachedDraft()
        throw BookSourceDraftConflict()
    }

    private fun invalidateCachedDraft() {
        dismissMigration()
        draft = null
        pendingNativeRollback = null
        pendingNativeReceipt = null
        pendingNativeResult = null
        mutableState.value = mutableState.value.copy(document = null)
    }

    private suspend fun checkpoint(updated: BookSourceEditDocument) {
        val next = updated.copy(revision = (draft?.revision ?: -1) + 1)
        writeMutex.withLock { requireAccepted(repository.writeDraft(sessionId, next)) }
        draft = next
    }

    private suspend fun resultCheckpoint(updated: BookSourceEditDocument) {
        // Preserve an immutable native result if private IO fails. Its retry consumes this
        // receipt and cannot revive an earlier accepted handoff or borrow a newer owner.
        pendingNativeResult = updated
        checkpoint(updated)
        pendingNativeResult = null
        pendingNativeReceipt = null
        pendingNativeRollback = null
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun operation(clearError: Boolean = true, block: suspend () -> Unit) =
        viewModelScope.launch {
            operationMutex.withLock {
                mutableState.value =
                    mutableState.value.copy(
                        busy = true,
                        error = if (clearError) null else mutableState.value.error,
                    )
                try {
                    flush()
                    block()
                    publish()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is BookSourceDraftConflict) invalidateCachedDraft()
                    publish(error.localizedMessage ?: "Error")
                }
            }
        }

    fun previewMigration() {
        val current = draft ?: return
        if (
            !migrationAvailable ||
                state.value.busy ||
                current.finished ||
                current.nativeRequest != null
        )
            return
        val request = ++migrationRequest
        val snapshot =
            try {
                current.source()
            } catch (error: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        migrationRunning = false,
                        migrationReport = null,
                        migrationError = error.localizedMessage ?: "无法读取当前草稿",
                    )
                return
            }
        if (SourceEngineSourcePolicy.hasVersionedDefinition(snapshot.bookSourceComment)) {
            mutableState.value =
                mutableState.value.copy(
                    migrationRunning = false,
                    migrationReport = null,
                    migrationError = "已使用新版配置，无需再次从旧字段迁移",
                )
            return
        }
        val revision = current.revision
        mutableState.value =
            mutableState.value.copy(
                migrationRunning = true,
                migrationReport = null,
                migrationError = null,
            )
        viewModelScope.launch {
            try {
                val preview = migrateSource(snapshot)
                if (request != migrationRequest || draft == null || draft?.finished == true)
                    return@launch
                mutableState.value =
                    mutableState.value.copy(
                        migrationRunning = false,
                        migrationReport = BookSourceMigrationReport(revision, preview),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == migrationRequest) {
                    mutableState.value =
                        mutableState.value.copy(
                            migrationRunning = false,
                            migrationError = error.localizedMessage ?: "迁移预览失败",
                        )
                }
            }
        }
    }

    fun dismissMigration() {
        migrationRequest++
        mutableState.value =
            mutableState.value.copy(
                migrationRunning = false,
                migrationReport = null,
                migrationError = null,
            )
    }

    fun applyMigration() {
        val current = draft ?: return
        val report = state.value.migrationReport ?: return
        val preview = report.preview
        if (
            !migrationAvailable ||
                state.value.busy ||
                current.finished ||
                current.nativeRequest != null ||
                report.revision != current.revision ||
                preview.requiresManualWork ||
                preview.issues.isNotEmpty() ||
                preview.status != "unverified" ||
                preview.candidateJson.isNullOrBlank()
        )
            return
        try {
            val updated = current.withMigrationCandidate(preview.candidateJson)
            edit(updated)
            dismissMigration()
        } catch (error: Exception) {
            mutableState.value =
                mutableState.value.copy(migrationError = error.localizedMessage ?: "无法应用迁移候选")
        }
    }

    fun retry() = retryInternal(manual = true)

    private fun retryInternal(manual: Boolean) {
        operation {
            if (draft == null || pendingSave) {
                draft = repository.readDraft(sessionId) ?: repository.load(sourceUrl)
                pendingSave = false
                flush()
            }
            pendingNativeRollback?.let {
                checkpoint(it)
                pendingNativeRollback = null
            }
            pendingNativeResult?.let { resultCheckpoint(it) }
            pendingNativeReceipt?.let {
                checkpoint(it)
                pendingNativeReceipt = null
            }
            var current = draft ?: return@operation
            val nativeRequest = current.nativeRequest
            if (
                nativeRequest?.delivered == true &&
                    !nativeRequest.handedOff &&
                    !nativeRequest.returning &&
                    current.importPayload == null
            ) {
                if (!manual) {
                    publish("原生请求交付中断，请确认子页面状态后点击重试；此前可能已打开子页面")
                    return@operation
                }
                checkpoint(current.copy(nativeRequest = nativeRequest.copy(delivered = false)))
                current = draft!!
            }
            if (current.finished) {
                repository.release(*current.ownedTransfers.toTypedArray())
            } else if (current.importPayload != null) {
                completeImport()
            } else if (current.nativeRequest?.returning == true) {
                completeEditorReturn()
            } else if (current.delivery != null) {
                deliverSaved(current)
            } else if (current.redirectJs && current.nativeRequest == null) {
                request(BookSourceNativeAction.JS, sourceUrl = current.originalKey)
            }
        }
    }

    fun updateField(tab: Int, key: String, value: String, start: Int, end: Int) {
        val current = draft ?: return
        if (state.value.busy || current.finished) return
        val field = current.form.field(tab, key) ?: return
        val histories =
            if (field.value != value) {
                val oldHistory =
                    current.histories.find { it.tab == tab && it.key == key }
                        ?: BookSourceFieldHistory(tab, key)
                current.histories.filterNot { it.tab == tab && it.key == key } +
                    oldHistory.copy(undo = oldHistory.undo + field, redo = emptyList())
            } else current.histories
        edit(
            current.copy(
                form = current.form.updateField(tab, key, value, start, end),
                histories = histories,
            )
        )
    }

    fun focus(tab: Int, key: String) {
        val current = draft ?: return
        if (!state.value.busy && (current.selectedTab != tab || current.focusedKey != key)) {
            edit(current.copy(selectedTab = tab, focusedKey = key))
        }
    }

    fun tab(tab: Int) {
        val current = draft ?: return
        if (!state.value.busy && tab in current.form.tabs.indices)
            edit(current.copy(selectedTab = tab, focusedKey = null))
    }

    fun options(options: BookSourceEditOptions) {
        val current = draft ?: return
        if (!state.value.busy) edit(current.copy(form = current.form.copy(options = options)))
    }

    fun optionsExpanded(expanded: Boolean) {
        val current = draft ?: return
        if (!state.value.busy) edit(current.copy(optionsExpanded = expanded))
    }

    fun autoComplete() {
        val current = draft ?: return
        if (!state.value.busy) edit(current.copy(autoComplete = !current.autoComplete))
    }

    fun insert(text: String) {
        val current = draft ?: return
        val key = current.focusedKey ?: return
        val field =
            current.form.insert(current.selectedTab, key, text).field(current.selectedTab, key)
                ?: return
        updateField(current.selectedTab, key, field.value, field.selectionStart, field.selectionEnd)
    }

    fun undo(redo: Boolean = false) {
        val current = draft ?: return
        val key = current.focusedKey ?: return
        val tab = current.selectedTab
        val history = current.histories.find { it.tab == tab && it.key == key } ?: return
        val previous = (if (redo) history.redo else history.undo).lastOrNull() ?: return
        val field = current.form.field(tab, key) ?: return
        val updatedHistory =
            if (redo) history.copy(undo = history.undo + field, redo = history.redo.dropLast(1))
            else history.copy(undo = history.undo.dropLast(1), redo = history.redo + field)
        edit(
            current.copy(
                form =
                    current.form.updateField(
                        tab,
                        key,
                        previous.value,
                        previous.selectionStart,
                        previous.selectionEnd,
                    ),
                histories = current.histories.map { if (it == history) updatedHistory else it },
            )
        )
    }

    fun importText(text: String) {
        operation {
            val current = draft ?: return@operation
            if (current.finished) return@operation
            checkpoint(current.copy(importPayload = text))
            completeImport()
        }
    }

    private suspend fun completeImport() {
        val current = draft ?: return
        val payload = current.importPayload ?: return
        val form = repository.importForm(payload)
        checkpoint(
            current.copy(
                form = form,
                histories = emptyList(),
                nativeRequest = null,
                importPayload = null,
            )
        )
    }

    fun showInitialHelp(needed: Boolean) {
        operation {
            val current = draft ?: return@operation
            if (current.helpShown || current.redirectJs || current.nativeRequest != null)
                return@operation
            checkpoint(current.copy(helpShown = true))
            if (needed) request(BookSourceNativeAction.HELP, "ruleHelp")
        }
    }

    fun save(action: BookSourceSaveAction) {
        operation {
            val current = draft ?: return@operation
            if (current.finished || current.nativeRequest != null) return@operation
            // A disk failure after Room acceptance leaves a fixed journal. Retry reads its receipt
            // before allowing another save, so rules are never materialized twice for one intent.
            pendingSave = true
            draft = repository.save(sessionId, current, action)
            pendingSave = false
            deliverSaved(draft!!)
        }
    }

    private suspend fun deliverSaved(current: BookSourceEditDocument) {
        val delivery = current.delivery ?: return
        if (delivery.action == BookSourceSaveAction.FINISH) {
            close(current)
        } else if (delivery.action == BookSourceSaveAction.VARIABLE) {
            val variable = repository.variable(delivery.sourceUrl)
            val comment = repository.variableComment(delivery.sourceUrl)
            checkpoint(
                current.copy(delivery = null, variableDraft = variable, variableComment = comment)
            )
        } else {
            val action =
                when (delivery.action) {
                    BookSourceSaveAction.DEBUG -> BookSourceNativeAction.DEBUG
                    BookSourceSaveAction.LOGIN -> BookSourceNativeAction.LOGIN
                    BookSourceSaveAction.SEARCH -> BookSourceNativeAction.SEARCH
                }
            checkpoint(
                current.copy(
                    delivery = null,
                    nativeRequest =
                        BookSourceNativeRequest(
                            delivery.id,
                            action,
                            sourceUrl = delivery.sourceUrl,
                            text =
                                if (action == BookSourceNativeAction.SEARCH)
                                    repository.searchScope(delivery.sourceUrl)
                                else null,
                        ),
                )
            )
        }
    }

    fun requestAction(action: BookSourceNativeAction, text: String? = null) {
        operation { request(action, text) }
    }

    private suspend fun request(
        action: BookSourceNativeAction,
        text: String? = null,
        sourceUrl: String? = null,
    ) {
        val current = draft ?: return
        if (current.finished || current.nativeRequest != null) return
        val key = current.focusedKey
        val field = key?.let { current.form.field(current.selectedTab, it) }
        if (action == BookSourceNativeAction.EDITOR && field == null) error("请先定位文本框光标")
        val path =
            if (action == BookSourceNativeAction.EDITOR) repository.transfer(field!!.value)
            else null
        val payload =
            if (
                action in
                    listOf(
                        BookSourceNativeAction.COPY,
                        BookSourceNativeAction.SHARE,
                        BookSourceNativeAction.QR_SHARE,
                    )
            )
                repository.export(current)
            else text
        try {
            checkpoint(
                current.copy(
                    nativeRequest =
                        BookSourceNativeRequest(
                            UUID.randomUUID().toString(),
                            action,
                            text = payload,
                            path = path,
                            sourceUrl = sourceUrl,
                            tab = current.selectedTab,
                            key = key,
                            cursor = field?.selectionStart ?: 0,
                            selectionEnd = field?.selectionEnd ?: 0,
                        ),
                    ownedTransfers = current.ownedTransfers + listOfNotNull(path),
                )
            )
        } catch (error: Throwable) {
            repository.release(path)
            throw error
        }
    }

    suspend fun deliverNative(
        id: String,
        canLaunch: () -> Boolean,
        launch: (BookSourceNativeRequest) -> Unit,
    ): Boolean =
        withContext(NonCancellable) {
            operationMutex.withLock {
                val current = draft ?: return@withLock false
                val request = current.nativeRequest ?: return@withLock false
                if (request.id != id || request.delivered || current.finished) return@withLock false
                mutableState.value = mutableState.value.copy(busy = true)
                pendingNativeRollback = current
                try {
                    // The private claim survives lifecycle cancellation. Pausing restores the same
                    // owner and file; a failed rollback is retried before a new handoff is allowed.
                    checkpoint(current.copy(nativeRequest = request.copy(delivered = true)))
                    if (!canLaunch()) {
                        checkpoint(current)
                        pendingNativeRollback = null
                        publish()
                        return@withLock false
                    }
                    launch(request)
                } catch (error: Exception) {
                    if (error !is BookSourceDraftConflict) {
                        runCatching { checkpoint(current) }
                            .onSuccess { pendingNativeRollback = null }
                    }
                    publish(error.localizedMessage)
                    return@withLock false
                }
                // A returned launcher has accepted the handoff. A receipt failure must never roll
                // back its owner or repeat the launch in this live VM. Recovery without that
                // private
                // receipt requires explicit confirmation because the child may already be open.
                pendingNativeRollback = null
                pendingNativeReceipt =
                    draft!!.copy(nativeRequest = request.copy(delivered = true, handedOff = true))
                try {
                    checkpoint(pendingNativeReceipt!!)
                    pendingNativeReceipt = null
                    publish()
                } catch (error: Exception) {
                    publish(error.localizedMessage)
                }
                true
            }
        }

    fun qrReturned(id: String, text: String?) {
        operation(clearError = false) {
            val current = draft ?: return@operation
            val request = current.nativeRequest ?: return@operation
            if (
                request.id != id ||
                    request.action != BookSourceNativeAction.QR ||
                    !request.delivered
            )
                return@operation
            if (text == null) resultCheckpoint(current.copy(nativeRequest = null))
            else {
                resultCheckpoint(
                    current.copy(
                        importPayload = text,
                        nativeRequest = request.copy(handedOff = true),
                    )
                )
                completeImport()
            }
        }
    }

    fun nativeReturned(id: String, action: BookSourceNativeAction) {
        operation(clearError = false) {
            val current = draft ?: return@operation
            if (
                current.nativeRequest?.id == id &&
                    current.nativeRequest.action == action &&
                    current.nativeRequest.delivered == true
            ) {
                resultCheckpoint(current.copy(nativeRequest = null))
            }
        }
    }

    fun nativeInserted(id: String, action: BookSourceNativeAction, text: String?) {
        operation(clearError = false) {
            val current = draft ?: return@operation
            val request = current.nativeRequest ?: return@operation
            if (request.id != id || request.action != action || !request.delivered) return@operation
            val key = request.key
            val field = key?.let { current.form.field(request.tab, it) }
            if (text == null || field == null) {
                resultCheckpoint(current.copy(nativeRequest = null))
                return@operation
            }
            val form =
                current.form
                    .updateField(
                        request.tab,
                        key,
                        field.value,
                        request.cursor,
                        request.selectionEnd,
                    )
                    .insert(request.tab, key, text)
            val history =
                current.histories.find { it.tab == request.tab && it.key == key }
                    ?: BookSourceFieldHistory(request.tab, key)
            resultCheckpoint(
                current.copy(
                    form = form,
                    histories =
                        if (form.field(request.tab, key)?.value == field.value) current.histories
                        else
                            current.histories.filterNot { it.tab == request.tab && it.key == key } +
                                history.copy(undo = history.undo + field, redo = emptyList()),
                    nativeRequest = null,
                    selectedTab = request.tab,
                    focusedKey = key,
                )
            )
        }
    }

    fun editorReturned(id: String, ok: Boolean, text: String?, path: String?, cursor: Int) {
        operation(clearError = false) {
            val current = draft ?: return@operation
            val request = current.nativeRequest ?: return@operation
            if (
                request.id != id ||
                    request.action != BookSourceNativeAction.EDITOR ||
                    !request.delivered
            )
                return@operation
            if (!ok) {
                resultCheckpoint(current.copy(nativeRequest = null))
                repository.release(request.path)
                return@operation
            }
            resultCheckpoint(
                current.copy(
                    nativeRequest =
                        request.copy(
                            handedOff = true,
                            returning = true,
                            returnedText = text,
                            returnedPath = path,
                            returnedCursor = cursor,
                        ),
                    ownedTransfers = (current.ownedTransfers + listOfNotNull(path)).distinct(),
                )
            )
            completeEditorReturn()
        }
    }

    private suspend fun completeEditorReturn() {
        val current = draft ?: return
        val request = current.nativeRequest ?: return
        val key = request.key ?: return
        val field = current.form.field(request.tab, key) ?: return
        val text =
            request.returnedPath?.let { repository.editorText(it) }
                ?: request.returnedText
                ?: field.value
        val cursor =
            if (request.returnedCursor < 0) field.selectionStart
            else request.returnedCursor.coerceIn(0, text.length)
        val history =
            current.histories.find { it.tab == request.tab && it.key == key }
                ?: BookSourceFieldHistory(request.tab, key)
        checkpoint(
            current.copy(
                form = current.form.updateField(request.tab, key, text, cursor, cursor),
                histories =
                    if (text == field.value) current.histories
                    else
                        current.histories.filterNot { it.tab == request.tab && it.key == key } +
                            history.copy(undo = history.undo + field, redo = emptyList()),
                nativeRequest = null,
                selectedTab = request.tab,
                focusedKey = key,
            )
        )
        repository.release(request.path, request.returnedPath)
    }

    fun clearCookie() {
        operation {
            draft?.form?.field(0, "bookSourceUrl")?.value?.let { repository.clearCookie(it) }
        }
    }

    fun keyboardRows(rows: Int) {
        mutableState.value = mutableState.value.copy(keyboardRows = rows.coerceIn(1, 5))
    }

    fun groups() {
        operation { mutableState.value = mutableState.value.copy(groups = repository.groups()) }
    }

    fun dismissGroups() {
        mutableState.value = mutableState.value.copy(groups = null)
    }

    fun variable(value: String?) {
        operation {
            draft?.savedUrl?.let { repository.setVariable(it, value) }
            draft?.let { checkpoint(it.copy(variableDraft = null, variableComment = null)) }
        }
    }

    fun variableEdit(value: String) {
        val current = draft ?: return
        if (!state.value.busy) edit(current.copy(variableDraft = value))
    }

    fun dismissVariable() {
        val current = draft ?: return
        if (!state.value.busy) edit(current.copy(variableDraft = null, variableComment = null))
    }

    fun cancel() {
        val current = draft ?: return
        if (current.dirty()) mutableState.value = mutableState.value.copy(confirmDiscard = true)
        else discard()
    }

    fun keepEditing() {
        mutableState.value = mutableState.value.copy(confirmDiscard = false)
    }

    fun discard() {
        operation { draft?.let { close(it) } }
    }

    private suspend fun close(current: BookSourceEditDocument) {
        dismissMigration()
        val empty = BookSourceEditDocument.from(BookSource(), originalKey = null)
        resultCheckpoint(
            current.copy(
                originalJson = empty.originalJson,
                form = empty.form,
                baseline = empty.baseline,
                histories = emptyList(),
                nativeRequest = null,
                delivery = null,
                variableDraft = null,
                variableComment = null,
                importPayload = null,
                finished = true,
            )
        )
        repository.release(*current.ownedTransfers.toTypedArray())
        mutableState.value = mutableState.value.copy(confirmDiscard = false)
    }

    fun jsReturned(id: String, ok: Boolean, origin: String?) {
        operation(clearError = false) {
            val current = draft ?: return@operation
            if (
                current.nativeRequest?.id != id ||
                    current.nativeRequest.action != BookSourceNativeAction.JS ||
                    current.nativeRequest.delivered != true
            )
                return@operation
            close(current.copy(savedUrl = if (ok) origin else current.savedUrl))
        }
    }
}
