package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookGroupEditorRepository
import io.legado.app.data.repository.BookGroupEditorSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY = "book.group.editor."
data class BookGroupEditorUiState(val draft: BookGroupEditorSnapshot = BookGroupEditorSnapshot(0),
    val loading: Boolean = false, val loadFailed: Boolean = false, val saving: Boolean = false,
    val importingCover: Boolean = false, val error: String? = null, val finished: Boolean = false,
    val confirmDelete: Boolean = false, val coverMenu: Boolean = false, val selectingCover: Boolean = false,
    val editing: Boolean = false) {
    val canDelete get() = editing && draft.canDelete
}
class BookGroupEditorViewModel(private val repository: BookGroupEditorRepository, private val saved: SavedStateHandle,
    initial: BookGroupEditorSnapshot? = null) : ViewModel() {
    private val initialId: Long? = if (saved.contains(KEY + "existing")) saved[KEY + "existing"] else initial?.id
    private val start = initial ?: BookGroupEditorSnapshot(0)
    private var readJob: Job? = null
    private var coverRevision = 0
    private var coverJob: Job? = null
    private val mutableState = MutableStateFlow(BookGroupEditorUiState(
        draft = start.copy(id = saved[KEY + "id"] ?: start.id, name = saved[KEY + "name"] ?: start.name,
            cover = if (saved.contains(KEY + "cover")) saved[KEY + "cover"] else start.cover,
            bookSort = (saved.get<Int>(KEY + "sort") ?: start.bookSort).takeIf { it in -1..5 } ?: -1,
            enableRefresh = saved[KEY + "refresh"] ?: start.enableRefresh, onlyUpdateRead = saved[KEY + "read"] ?: start.onlyUpdateRead,
            order = saved[KEY + "order"] ?: start.order, show = saved[KEY + "show"] ?: start.show),
        loading = initialId != null && saved.get<Boolean>(KEY + "loaded") != true,
        finished = saved[KEY + "finished"] ?: false, confirmDelete = saved[KEY + "confirmDelete"] ?: false,
        coverMenu = saved[KEY + "coverMenu"] ?: false, selectingCover = saved[KEY + "selectingCover"] ?: false,
        editing = initialId != null))
    val state = mutableState.asStateFlow()
    init { saved[KEY + "existing"] = initialId; if (state.value.loading && !state.value.finished) load() }
    fun load() {
        val id = initialId ?: return
        if (state.value.saving || state.value.finished || readJob?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, loadFailed = false, error = null)
        readJob = viewModelScope.launch {
            try {
                val current = repository.load(id) ?: error("分组不存在")
                coroutineContext.ensureActive()
                var draft = current.copy(bookSort = current.bookSort.takeIf { it in -1..5 } ?: -1)
                if (saved.contains(KEY + "name")) draft = draft.copy(name = state.value.draft.name)
                if (saved.contains(KEY + "cover")) draft = draft.copy(cover = state.value.draft.cover)
                if (saved.contains(KEY + "sort")) draft = draft.copy(bookSort = state.value.draft.bookSort)
                if (saved.contains(KEY + "refresh")) draft = draft.copy(enableRefresh = state.value.draft.enableRefresh)
                if (saved.contains(KEY + "read")) draft = draft.copy(onlyUpdateRead = state.value.draft.onlyUpdateRead)
                saved[KEY + "name"] = draft.name; saved[KEY + "cover"] = draft.cover; saved[KEY + "sort"] = draft.bookSort
                saved[KEY + "refresh"] = draft.enableRefresh; saved[KEY + "read"] = draft.onlyUpdateRead
                saved[KEY + "loaded"] = true
                saved[KEY + "order"] = draft.order; saved[KEY + "show"] = draft.show
                mutableState.value = state.value.copy(draft = draft, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage ?: "ERROR") }
        }
    }
    private fun edit(block: (BookGroupEditorSnapshot) -> BookGroupEditorSnapshot) { if (!state.value.saving && !state.value.finished) mutableState.value = state.value.copy(draft = block(state.value.draft), error = null) }
    fun name(value: String) { if (state.value.saving || state.value.finished) return; saved[KEY + "name"] = value; edit { it.copy(name = value) } }
    fun sort(value: Int) { if (value !in -1..5 || state.value.saving || state.value.finished) return; saved[KEY + "sort"] = value; edit { it.copy(bookSort = value) } }
    fun refresh(value: Boolean) { if (state.value.saving || state.value.finished) return; saved[KEY + "refresh"] = value; edit { it.copy(enableRefresh = value) } }
    fun onlyRead(value: Boolean) { if (state.value.saving || state.value.finished) return; saved[KEY + "read"] = value; edit { it.copy(onlyUpdateRead = value) } }
    private fun setCover(value: String?) { saved[KEY + "cover"] = value; edit { it.copy(cover = value?.takeIf(String::isNotBlank)) } }
    fun coverMenu(show: Boolean) { saved[KEY + "coverMenu"] = show; mutableState.value = state.value.copy(coverMenu = show) }
    fun requestCover(): Boolean {
        if (state.value.saving || state.value.finished || state.value.selectingCover) return false
        return if (state.value.draft.cover.isNullOrEmpty()) selectCover() else { coverMenu(true); false }
    }
    fun selectCover(): Boolean {
        if (state.value.saving || state.value.finished || state.value.selectingCover) return false
        coverMenu(false); saved[KEY + "selectingCover"] = true
        mutableState.value = state.value.copy(selectingCover = true); return true
    }
    fun coverResult(uri: String?) {
        saved[KEY + "selectingCover"] = false; mutableState.value = state.value.copy(selectingCover = false)
        if (uri == null || state.value.saving || state.value.finished) return
        val revision = ++coverRevision; coverJob?.cancel()
        mutableState.value = state.value.copy(importingCover = true, error = null)
        coverJob = viewModelScope.launch {
            try {
                val cover = repository.importCover(uri); coroutineContext.ensureActive()
                if (revision == coverRevision && !state.value.finished) { setCover(cover); mutableState.value = state.value.copy(importingCover = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (revision == coverRevision) mutableState.value = state.value.copy(importingCover = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun removeCover() { if (state.value.saving || state.value.finished) return; coverRevision++; coverJob?.cancel(); coverMenu(false); setCover(null); mutableState.value = state.value.copy(importingCover = false) }
    fun requestDelete(show: Boolean) { if (!state.value.canDelete || state.value.saving || state.value.finished) return; saved[KEY + "confirmDelete"] = show; mutableState.value = state.value.copy(confirmDelete = show) }
    fun confirmDelete() { if (!state.value.confirmDelete || !state.value.canDelete) return; operation { repository.delete(state.value.draft.id) } }
    fun save() {
        if (state.value.loading || state.value.loadFailed || state.value.importingCover || state.value.selectingCover) return
        val draft = state.value.draft
        if (draft.name.isEmpty()) { mutableState.value = state.value.copy(error = "分组名称不能为空"); return }
        operation { val persisted = repository.save(draft, initialId != null); saved[KEY + "id"] = persisted.id; mutableState.value = state.value.copy(draft = persisted) }
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.saving || state.value.finished || state.value.loading || state.value.loadFailed) return
        mutableState.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try { block(); coroutineContext.ensureActive(); finish() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(saving = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun close() { if (!state.value.saving) { coverRevision++; coverJob?.cancel(); finish() } }
    private fun finish() { saved[KEY + "finished"] = true; saved[KEY + "confirmDelete"] = false; mutableState.value = state.value.copy(finished = true, saving = false, confirmDelete = false, importingCover = false) }
}
