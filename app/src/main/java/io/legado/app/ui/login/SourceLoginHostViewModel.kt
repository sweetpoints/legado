package io.legado.app.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class SourceLoginHostState(val loading: Boolean = false, val loaded: Boolean = false,
    val form: Boolean = false, val title: String = "", val missing: Boolean = false, val error: String? = null)

/** Owns the small entry UUID. Script entities remain behind the legacy form compatibility bridge. */
class SourceLoginHostViewModel(private val repository: SourceLoginRepository,
    private val sessions: SourceLoginSessionRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val id = saved.get<String>("sourceLogin.entry") ?: UUID.randomUUID().toString().also { saved["sourceLogin.entry"] = it }
    private val mutable = MutableStateFlow(SourceLoginHostState())
    val state = mutable.asStateFlow()
    private var request: SourceLoginRequest? = null
    private var snapshot: SourceLoginSnapshot? = null
    private var epoch = 0L
    private var job: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun bind(input: SourceLoginRequest? = null) {
        if (state.value.loading && (input == null || input == request) || state.value.loaded && (input == null || input == request)) return
        val generation = ++epoch; job?.cancel(); snapshot = null; if (input != null) request = input
        mutable.value = SourceLoginHostState(loading = true)
        job = viewModelScope.launch {
            try {
                val value = request ?: sessions.read(id)
                currentCoroutineContext().ensureActive(); if (generation != epoch) return@launch
                if (value == null) { mutable.value = SourceLoginHostState(missing = true); return@launch }
                request = value
                sessions.write(id, value); currentCoroutineContext().ensureActive(); if (generation != epoch) return@launch
                val loaded = repository.load(value); currentCoroutineContext().ensureActive(); if (generation != epoch) return@launch
                snapshot = loaded
                val source = loaded.source
                mutable.value = SourceLoginHostState(loaded = source != null, form = source?.hasLoginForm() == true,
                    title = source?.getTag().orEmpty(), missing = source == null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == epoch) mutable.value = SourceLoginHostState(error = error.localizedMessage ?: error.javaClass.simpleName) }
        }
    }
    fun retry() = bind(request)
    fun inputs(): Pair<SourceLoginRequest, SourceLoginSnapshot>? = if (!state.value.loaded) null
        else request?.let { request -> snapshot?.let { request to it } }
    fun stop() { epoch++; job?.cancel(); viewModelScope.cancel() }
    override fun onCleared() { stop(); cleanup.launch { runCatching { sessions.release(id) } }; super.onCleared() }
}
