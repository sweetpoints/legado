package io.legado.app.ui.book.bookmark

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BookmarkEditorState(val chapter: String = "", val bookText: String = "", val content: String = "",
    val textStart: Int = 0, val textEnd: Int = 0, val contentStart: Int = 0, val contentEnd: Int = 0,
    val canDelete: Boolean = false, val loaded: Boolean = false, val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val finished: Boolean = false) {
    val canEdit get() = loaded && !loading && !busy && !finished
}
class BookmarkEditorViewModel(private val repository: BookmarkEditorRepository, private val saved: SavedStateHandle,
    requestId: String) : ViewModel() {
    private val id = saved.get<String>("bookmark.request") ?: requestId.also { saved["bookmark.request"] = it }
    private val mutable = MutableStateFlow(BookmarkEditorState(finished = saved.get<Boolean>("bookmark.finished") == true,
        loading = saved.get<Boolean>("bookmark.finished") != true))
    val state = mutable.asStateFlow()
    private var draft: BookmarkEditorDraft? = null
    private var work: Job? = null; private var autosave: Job? = null
    init { load() }
    fun load() {
        if (state.value.finished || state.value.loaded || work?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val loaded = repository.load(id)
                currentCoroutineContext().ensureActive()
                if (state.value.finished) return@launch
                draft = loaded
                mutable.value = state.value.copy(chapter = loaded.seed.chapterName, bookText = loaded.bookText, content = loaded.content,
                    canDelete = loaded.seed.editPos >= 0, loaded = true, loading = false, finished = loaded.finished,
                    textStart = (saved.get<Int>("bookmark.textStart") ?: 0).coerceIn(0, loaded.bookText.length),
                    textEnd = (saved.get<Int>("bookmark.textEnd") ?: 0).coerceIn(0, loaded.bookText.length),
                    contentStart = (saved.get<Int>("bookmark.contentStart") ?: 0).coerceIn(0, loaded.content.length),
                    contentEnd = (saved.get<Int>("bookmark.contentEnd") ?: 0).coerceIn(0, loaded.content.length))
                if (loaded.finished) finish()
            } catch (error: Throwable) { failure(error) }
        }
    }
    fun bookText(value: String, start: Int, end: Int) = edit(true, value, start, end)
    fun content(value: String, start: Int, end: Int) = edit(false, value, start, end)
    private fun edit(text: Boolean, value: String, start: Int, end: Int) {
        if (!state.value.canEdit) return
        val first = start.coerceIn(0, value.length); val last = end.coerceIn(0, value.length)
        if (text) {
            saved["bookmark.textStart"] = first; saved["bookmark.textEnd"] = last
            mutable.value = state.value.copy(bookText = value, textStart = first, textEnd = last)
        } else {
            saved["bookmark.contentStart"] = first; saved["bookmark.contentEnd"] = last
            mutable.value = state.value.copy(content = value, contentStart = first, contentEnd = last)
        }
        val previous = draft ?: return
        if (previous.bookText == state.value.bookText && previous.content == state.value.content) return
        val snapshot = previous.copy(bookText = state.value.bookText, content = state.value.content, revision = previous.revision + 1)
        draft = snapshot; autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(150)
            try { repository.write(id, snapshot) }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (draft?.revision == snapshot.revision && !state.value.busy) failure(error)
            }
        }
    }
    fun confirm() = commit(false)
    fun delete() = commit(true)
    private fun commit(delete: Boolean) {
        if (!state.value.canEdit || delete && !state.value.canDelete) return
        autosave?.cancel(); val snapshot = draft!!.copy(revision = draft!!.revision + 1)
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                repository.commit(id, snapshot, delete)
                currentCoroutineContext().ensureActive()
                draft = snapshot.copy(finished = true); finish()
            }
            catch (error: Throwable) { failure(error) }
        }
    }
    suspend fun flushDraft() {
        autosave?.cancel()
        if (state.value.busy || state.value.finished) return
        draft?.let { repository.write(id, it) }
    }
    fun cancel() {
        if (state.value.busy || state.value.finished) return
        work?.cancel(); autosave?.cancel(); finish()
    }
    private fun finish() {
        saved["bookmark.finished"] = true
        mutable.value = state.value.copy(finished = true, loading = false, busy = false)
    }
    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        if (!state.value.finished) mutable.value = state.value.copy(loading = false, busy = false, error = error.localizedMessage ?: error.toString())
    }
    fun stop() { work?.cancel(); autosave?.cancel() }
    override fun onCleared() { stop() }
}
