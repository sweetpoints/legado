package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
)

internal class BookSourceComposeViewModel(
    private val repository: BookSourceEditorRepository,
    savedState: SavedStateHandle,
    private val sourceUrl: String?,
) : ViewModel() {
    private val sessionId =
        savedState.get<String>("bookSourceDraftId")
            ?: UUID.randomUUID().toString().also { savedState["bookSourceDraftId"] = it }
    private val operationMutex = Mutex()
    private val writeMutex = Mutex()
    private val mutableState = MutableStateFlow(BookSourceComposeState())
    val state = mutableState.asStateFlow()
    private var draft: BookSourceEditDocument? = null
    private var pendingSave = false
    private var pendingNativeRollback: BookSourceEditDocument? = null

    init {
        retry()
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
            draft?.let { repository.writeDraft(sessionId, it) }
        }
    }

    private suspend fun checkpoint(updated: BookSourceEditDocument) {
        val next = updated.copy(revision = (draft?.revision ?: -1) + 1)
        writeMutex.withLock { repository.writeDraft(sessionId, next) }
        draft = next
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
                publish(error.localizedMessage ?: "Error")
            }
        }
    }

    fun retry() {
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
            val current = draft ?: return@operation
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
                    else -> error("Unexpected save action")
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
                // The claim survives lifecycle cancellation. A paused host restores the same ticket
                // and prepared file; it cannot strand an editor claim without launching its child.
                mutableState.value = mutableState.value.copy(busy = true)
                pendingNativeRollback = current
                try {
                    checkpoint(current.copy(nativeRequest = request.copy(delivered = true)))
                    if (!canLaunch()) {
                        checkpoint(current)
                        pendingNativeRollback = null
                        publish()
                        return@withLock false
                    }
                    launch(request)
                    pendingNativeRollback = null
                    publish()
                    true
                } catch (error: Exception) {
                    runCatching { checkpoint(current) }.onSuccess { pendingNativeRollback = null }
                    publish(error.localizedMessage)
                    false
                }
            }
        }

    fun nativeReturned(action: BookSourceNativeAction) {
        operation {
            val current = draft ?: return@operation
            if (
                current.nativeRequest?.action == action && current.nativeRequest?.delivered == true
            ) {
                checkpoint(current.copy(nativeRequest = null))
            }
        }
    }

    fun nativeInserted(action: BookSourceNativeAction, text: String?) {
        operation {
            val current = draft ?: return@operation
            val request = current.nativeRequest ?: return@operation
            if (request.action != action || !request.delivered) return@operation
            val key = request.key
            val field = key?.let { current.form.field(request.tab, it) }
            if (text == null || field == null || key == null) {
                checkpoint(current.copy(nativeRequest = null))
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
            checkpoint(
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

    fun editorReturned(ok: Boolean, text: String?, path: String?, cursor: Int) {
        operation {
            val current = draft ?: return@operation
            val request = current.nativeRequest ?: return@operation
            if (request.action != BookSourceNativeAction.EDITOR || !request.delivered)
                return@operation
            if (!ok) {
                checkpoint(current.copy(nativeRequest = null))
                repository.release(request.path)
                return@operation
            }
            checkpoint(
                current.copy(
                    nativeRequest =
                        request.copy(
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
        val empty = BookSourceEditDocument.from(BookSource(), originalKey = null)
        checkpoint(
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

    fun jsReturned(ok: Boolean, origin: String?) {
        operation {
            val current = draft ?: return@operation
            if (current.nativeRequest?.action != BookSourceNativeAction.JS) return@operation
            close(current.copy(savedUrl = if (ok) origin else current.savedUrl))
        }
    }
}
