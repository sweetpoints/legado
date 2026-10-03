package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BookMemoConfirmation {
    Discard,
    Clear,
}

data class BookMemoState(
    val memo: BookMemoSnapshot? = null,
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val draft: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val saving: Boolean = false,
    val error: String? = null,
    val confirmation: BookMemoConfirmation? = null,
    val finished: Boolean = false,
)

class BookMemoViewModel(
    private val repository: BookMemoRepository,
    private val saved: SavedStateHandle,
    val bookUrl: String,
) : ViewModel() {
    val draftId =
        saved.get<String>("memo.draftId")
            ?: UUID.randomUUID().toString().also { saved["memo.draftId"] = it }
    private var revision = saved.get<Long>("memo.revision") ?: 0L
    private val mutable =
        MutableStateFlow(
            BookMemoState(
                finished = saved["memo.finished"] ?: false,
                confirmation =
                    saved.get<String>("memo.confirmation")?.let {
                        runCatching { BookMemoConfirmation.valueOf(it) }.getOrNull()
                    },
            )
        )
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var restore: Job? = null
    private var observed = false
    private var restored = false
    private val checkpoints = Channel<BookMemoDraft>(Channel.CONFLATED)
    private val writer = viewModelScope.launch {
        for (draft in checkpoints) try {
            repository.writeDraft(draftId, draft)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
        }
    }

    init {
        if (!state.value.finished) load()
    }

    fun load() {
        if (state.value.finished || restore?.isActive == true) return
        mutable.value = state.value.copy(error = null)
        observer?.cancel()
        observer = viewModelScope.launch {
            try {
                repository.observe(bookUrl).collect {
                    observed = true
                    mutable.value = state.value.copy(memo = it, loaded = restored)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
            }
        }
        restore = viewModelScope.launch {
            try {
                val draft = repository.readDraft(draftId)
                if (draft != null) {
                    check(draft.bookUrl == bookUrl) { "备忘录草稿不匹配" }
                    revision = maxOf(revision, draft.revision)
                    saved["memo.revision"] = revision
                    val start =
                        (saved.get<Int>("memo.selectionStart") ?: draft.content.length).coerceIn(
                            0,
                            draft.content.length,
                        )
                    val end =
                        (saved.get<Int>("memo.selectionEnd") ?: start).coerceIn(
                            0,
                            draft.content.length,
                        )
                    mutable.value =
                        state.value.copy(
                            editing = draft.editing,
                            draft = draft.content,
                            selectionStart = start,
                            selectionEnd = end,
                        )
                }
                restored = true
                mutable.value = state.value.copy(loaded = observed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    private fun checkpoint() {
        revision++
        saved["memo.revision"] = revision
        checkpoints.trySend(
            BookMemoDraft(
                bookUrl,
                if (state.value.editing) state.value.draft else "",
                state.value.editing,
                revision,
            )
        )
    }

    fun edit() {
        if (
            !state.value.loaded || state.value.editing || state.value.saving || state.value.finished
        )
            return
        val content = state.value.memo?.content.orEmpty()
        mutable.value =
            state.value.copy(
                editing = true,
                draft = content,
                selectionStart = content.length,
                selectionEnd = content.length,
                error = null,
            )
        checkpoint()
    }

    fun text(content: String, start: Int = content.length, end: Int = start) {
        if (!state.value.editing || state.value.saving || state.value.finished) return
        val selectionStart = start.coerceIn(0, content.length)
        val selectionEnd = end.coerceIn(0, content.length)
        saved["memo.selectionStart"] = selectionStart
        saved["memo.selectionEnd"] = selectionEnd
        mutable.value =
            state.value.copy(
                draft = content,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                error = null,
            )
        checkpoint()
    }

    fun cancelEdit() {
        if (state.value.saving || state.value.finished) return
        mutable.value = state.value.copy(editing = false, confirmation = null)
        saved.remove<String>("memo.confirmation")
        checkpoint()
    }

    fun close() {
        if (state.value.saving || state.value.finished) return
        if (state.value.editing) confirmation(BookMemoConfirmation.Discard) else finish()
    }

    fun requestClear() {
        if (
            state.value.loaded &&
                !state.value.saving &&
                !state.value.finished &&
                !state.value.memo?.content.isNullOrEmpty()
        )
            confirmation(BookMemoConfirmation.Clear)
    }

    private fun confirmation(value: BookMemoConfirmation) {
        saved["memo.confirmation"] = value.name
        mutable.value = state.value.copy(confirmation = value)
    }

    fun cancelConfirmation() {
        saved.remove<String>("memo.confirmation")
        mutable.value = state.value.copy(confirmation = null)
    }

    fun confirm() {
        when (state.value.confirmation) {
            BookMemoConfirmation.Discard -> {
                cancelEdit()
                finish()
            }
            BookMemoConfirmation.Clear -> save("")
            null -> Unit
        }
    }

    fun save() {
        if (state.value.editing) save(state.value.draft)
    }

    private fun save(content: String) {
        if (!state.value.loaded || state.value.saving || state.value.finished) return
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val memo = repository.save(bookUrl, content)
                mutable.value =
                    state.value.copy(memo = memo, editing = false, draft = content, saving = false)
                cancelConfirmation()
                checkpoint()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(saving = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    private fun finish() {
        saved["memo.finished"] = true
        mutable.value = state.value.copy(finished = true)
    }

    suspend fun flushDraft() {
        if (!state.value.loaded || state.value.finished) return
        try {
            repository.writeDraft(
                draftId,
                BookMemoDraft(
                    bookUrl,
                    if (state.value.editing) state.value.draft else "",
                    state.value.editing,
                    revision,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage ?: "ERROR")
        }
    }

    fun stop() {
        observer?.cancel()
        restore?.cancel()
        writer.cancel()
        checkpoints.close()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
