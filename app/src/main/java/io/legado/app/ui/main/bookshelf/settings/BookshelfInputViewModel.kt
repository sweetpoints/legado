package io.legado.app.ui.main.bookshelf.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BookshelfInputState(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = text.length,
)

data class BookshelfInputResult(val text: String, val groupId: Long)

class BookshelfInputViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            BookshelfInputState(
                saved.get<String>("input.text") ?: saved.get<String>("value") ?: "",
                saved["input.start"]
                    ?: (saved.get<String>("input.text") ?: saved.get<String>("value") ?: "").length,
                saved["input.end"]
                    ?: (saved.get<String>("input.text") ?: saved.get<String>("value") ?: "").length,
            )
        )
    val state = mutableState.asStateFlow()
    val kind: Int = saved["kind"] ?: 0
    val summary: String = saved["summary"] ?: ""
    private var finished: Boolean = saved["input.finished"] ?: false

    fun edit(text: String, start: Int, end: Int) {
        if (finished) return
        mutableState.value =
            BookshelfInputState(text, start.coerceIn(0, text.length), end.coerceIn(0, text.length))
        saved["input.text"] = text
        saved["input.start"] = state.value.selectionStart
        saved["input.end"] = state.value.selectionEnd
    }

    fun confirm(): BookshelfInputResult? {
        if (finished) return null
        finish()
        // Export results always copy the actual URI, independent of edits to its displayed text.
        return BookshelfInputResult(
            if (kind == 2) saved.get<String>("value") ?: "" else state.value.text,
            saved["groupId"] ?: -1L,
        )
    }

    fun selectFile(): Long? {
        if (finished) return null
        finish()
        return saved["groupId"] ?: -1L
    }

    fun cancel() = finish()

    private fun finish() {
        finished = true
        saved["input.finished"] = true
    }
}
