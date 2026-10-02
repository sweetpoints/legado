package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.HttpTtsImportItem
import io.legado.app.data.repository.HttpTtsImportRepository
import io.legado.app.data.repository.HttpTtsImportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ImportHttpTtsCode(val key: String, val json: String)
data class ImportHttpTtsState(val items: List<HttpTtsImportItem> = emptyList(),
    val selected: Set<String> = emptySet(), val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val finished: Boolean = false, val code: ImportHttpTtsCode? = null) {
    val isSelectAll: Boolean get() = items.all { it.key in selected }
    val selectCount: Int get() = selected.size
}

class ImportHttpTtsViewModel(private val repository: HttpTtsImportRepository,
    private val saved: SavedStateHandle, private val source: String) : ViewModel() {
    private val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(ImportHttpTtsState(finished = saved["finished"] ?: false))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init { if (state.value.finished) mutable.value = state.value.copy(loading = false) else load() }
    private fun failure(error: Exception) {
        if (!state.value.finished) mutable.value = state.value.copy(loading = false, busy = false,
            error = "ImportError:${error.localizedMessage}")
    }
    fun load() {
        if (state.value.finished || operation?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (source.isEmpty()) { cancel(); return@launch }
                val staged = repository.restore(session) ?: HttpTtsImportSession(repository.read(source)).also {
                    repository.stage(session, it.items)
                }
                if (state.value.finished) return@launch
                if (staged.committed) { finish(); return@launch }
                val keys = staged.items.mapTo(mutableSetOf()) { it.key }
                val selected = saved.get<ArrayList<String>>("selected")?.toSet()?.intersect(keys)
                    ?: staged.items.filter { it.selectedByDefault }.mapTo(mutableSetOf()) { it.key }
                saved["selected"] = ArrayList(selected)
                mutable.value = ImportHttpTtsState(staged.items, selected, loading = false,
                    code = staged.items.find { it.key == saved.get<String>("codeKey") }?.let { ImportHttpTtsCode(it.key, it.json) })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    fun toggle(key: String) {
        val value = state.value
        if (value.loading || value.busy || value.finished || value.items.none { it.key == key }) return
        select(if (key in value.selected) value.selected - key else value.selected + key)
    }
    private fun select(keys: Set<String>) { saved["selected"] = ArrayList(keys); mutable.value = state.value.copy(selected = keys) }
    fun toggleAll() {
        val value = state.value
        if (!value.loading && !value.busy && !value.finished)
            select(if (value.isSelectAll) emptySet() else value.items.mapTo(mutableSetOf()) { it.key })
    }
    fun openCode(key: String) {
        val value = state.value
        if (value.busy || value.finished) return
        value.items.find { it.key == key }?.let { saved["codeKey"] = key; mutable.value = value.copy(code = ImportHttpTtsCode(key, it.json)) }
    }
    fun consumeCode(key: String) { if (state.value.code?.key == key) { saved.remove<String>("codeKey"); mutable.value = state.value.copy(code = null) } }
    fun edit(code: String, key: String?) {
        if (key == null || state.value.finished || state.value.busy || state.value.items.none { it.key == key }) return
        saved.remove<String>("codeKey")
        mutable.value = state.value.copy(busy = true, error = null, code = null)
        operation = viewModelScope.launch {
            try {
                val item = repository.edit(key, code)
                val items = state.value.items.map { if (it.key == key) item else it }
                repository.stage(session, items)
                if (!state.value.finished) mutable.value = state.value.copy(items = items, busy = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    fun confirm() {
        val value = state.value
        if (value.loading || value.busy || value.finished || value.error != null && value.items.isEmpty()) return
        mutable.value = value.copy(busy = true, error = null, code = null)
        operation = viewModelScope.launch {
            try { repository.insert(session, value.items, value.selected); finish() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
        }
    }
    private fun finish() { saved.remove<String>("codeKey"); saved["finished"] = true; mutable.value = state.value.copy(loading = false, busy = false, finished = true, code = null) }
    fun cancel() { if (!state.value.busy) { finish(); operation?.cancel() } }
}
