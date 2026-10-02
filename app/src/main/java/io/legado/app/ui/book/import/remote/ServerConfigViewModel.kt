package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Server
import io.legado.app.data.repository.RemoteServerEditorRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ServerConfigField { Name, Url, Username, Password }
data class ServerConfigDraft(val name: String = "", val url: String = "", val username: String = "", val password: String = "")
data class ServerConfigUiState(val draft: ServerConfigDraft = ServerConfigDraft(), val loading: Boolean = true,
    val saving: Boolean = false, val loadFailed: Boolean = false, val error: String? = null, val finished: Boolean = false)
class ServerConfigViewModel(private val repository: RemoteServerEditorRepository, private val saved: SavedStateHandle,
    private val id: Long? = null) : ViewModel() {
    private var loadingJob: Job? = null
    private var loadGeneration = 0
    private var original: Server? = saved.get<String>("server.original")?.let { GSON.fromJsonObject<Server>(it).getOrNull() }
    private val mutable = MutableStateFlow(ServerConfigUiState(
        draft = saved.get<String>("server.draft")?.let { GSON.fromJsonObject<ServerConfigDraft>(it).getOrNull() } ?: ServerConfigDraft(),
        loading = original == null, finished = saved["server.finished"] ?: false))
    val state = mutable.asStateFlow()
    init { if (original == null && !state.value.finished) load() }
    fun load() {
        if (state.value.saving || state.value.finished || original != null) return
        loadingJob?.cancel()
        val generation = ++loadGeneration
        mutable.value = state.value.copy(loading = true, loadFailed = false, error = null)
        loadingJob = viewModelScope.launch {
            try {
                val server = repository.load(id)
                if (generation != loadGeneration || state.value.finished) return@launch
                val config = server.config?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrThrow() }.orEmpty()
                original = server.copy(); saved["server.original"] = GSON.toJson(server)
                mutable.value = state.value.copy(loading = false, draft = ServerConfigDraft(server.name,
                    config["url"].orEmpty(), config["username"].orEmpty(), config["password"].orEmpty()))
                persist()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == loadGeneration && !state.value.finished) mutable.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun edit(field: ServerConfigField, value: String) {
        if (state.value.loading || state.value.loadFailed || state.value.saving || state.value.finished) return
        val draft = state.value.draft
        mutable.value = state.value.copy(error = null, draft = when (field) {
            ServerConfigField.Name -> draft.copy(name = value); ServerConfigField.Url -> draft.copy(url = value)
            ServerConfigField.Username -> draft.copy(username = value); ServerConfigField.Password -> draft.copy(password = value)
        }); persist()
    }
    fun save() {
        val server = original ?: return
        if (state.value.saving || state.value.loading || state.value.loadFailed || state.value.finished) return
        val draft = state.value.draft
        val edited = server.copy(name = draft.name, type = Server.TYPE.WEBDAV,
            config = GSON.toJson(mapOf("url" to draft.url, "username" to draft.username, "password" to draft.password)))
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try { repository.save(edited); mutable.value = state.value.copy(saving = false, finished = true); persist() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = state.value.copy(saving = false, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun close() { if (!state.value.saving) { loadGeneration++; loadingJob?.cancel(); mutable.value = state.value.copy(finished = true); persist() } }
    private fun persist() { saved["server.draft"] = GSON.toJson(state.value.draft); saved["server.finished"] = state.value.finished }
}
