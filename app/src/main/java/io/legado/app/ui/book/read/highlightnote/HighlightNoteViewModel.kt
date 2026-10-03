package io.legado.app.ui.book.read.highlightnote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.HighlightNoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HighlightNoteAction {
    Save,
    Delete,
}

data class HighlightNoteUiState(
    val chapterName: String = "",
    val bookText: String = "",
    val note: String = "",
    val busy: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

class HighlightNoteViewModel(
    private val repository: HighlightNoteRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val original = savedState.get<BookHighlight>("highlight")?.copy()
    private var action =
        savedState.get<String>("note.pending")?.let { name ->
            HighlightNoteAction.entries.find { it.name == name }
        }
    private val mutableState =
        MutableStateFlow(
            HighlightNoteUiState(
                chapterName = original?.chapterName.orEmpty(),
                bookText = savedState["note.bookText"] ?: original?.bookText.orEmpty(),
                note = savedState["note.text"] ?: original?.note.orEmpty(),
                finished = original == null || savedState.get<Boolean>("note.finished") == true,
            )
        )
    val state = mutableState.asStateFlow()

    init {
        if (!state.value.finished) action?.let(::perform)
    }

    fun setBookText(text: String) {
        if (state.value.busy || state.value.finished) return
        savedState["note.bookText"] = text
        mutableState.update { it.copy(bookText = text) }
    }

    fun setNote(text: String) {
        if (state.value.busy || state.value.finished) return
        savedState["note.text"] = text
        mutableState.update { it.copy(note = text) }
    }

    fun submit(action: HighlightNoteAction) {
        if (state.value.busy || state.value.finished) return
        this.action = action
        savedState["note.pending"] = action.name
        perform(action)
    }

    fun retry() {
        action?.let(::submit)
    }

    fun cancel() {
        if (state.value.busy || state.value.finished) return
        savedState.remove<String>("note.pending")
        savedState["note.finished"] = true
        mutableState.update { it.copy(finished = true) }
    }

    private fun perform(action: HighlightNoteAction) {
        val original = original ?: return
        // Editing never mutates the argument or live reader annotation before confirmation.
        val result = original.copy(bookText = state.value.bookText, note = state.value.note)
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (action == HighlightNoteAction.Save) repository.save(result)
                else repository.delete(original.copy())
                savedState.remove<String>("note.pending")
                savedState["note.finished"] = true
                mutableState.update { it.copy(busy = false, finished = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = error.localizedMessage ?: error.toString())
                }
            }
        }
    }
}
