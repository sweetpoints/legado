package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class TocHighlightOpen(val id: Long, val edit: Boolean, val nonce: String = UUID.randomUUID().toString(), val context: String? = null)
data class TocHighlightsState(val parameters: TocHighlightsParameters? = null, val loaded: Boolean = false,
    val rows: List<TocHighlightRow> = emptyList(), val scrollRequest: Long = 0, val scrollTarget: Int = 0,
    val open: TocHighlightOpen? = null, val error: String? = null)
class TocHighlightsViewModel(private val repository: TocHighlightsRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(TocHighlightsState(
        open = saved.get<String>("tocHighlights.open")?.let { GSON.fromJsonObject<TocHighlightOpen>(it).getOrNull() }))
    val state: StateFlow<TocHighlightsState> = mutable
    private val session = saved.get<String>("tocHighlights.session") ?: UUID.randomUUID().toString().also { saved["tocHighlights.session"] = it }
    private var generation = 0L
    private var revision = saved.get<Long>("tocHighlights.revision") ?: 0L
    private var restoring: Job? = null
    private var collecting: Job? = null
    private var nextScroll = saved.get<Long>("tocHighlights.nextScroll") ?: 0L
    init {
        restoring = viewModelScope.launch {
            try {
                val value = repository.checkpoint(session); currentCoroutineContext().ensureActive()
                if (generation == 0L && value != null) {
                    revision = maxOf(revision, value.revision); saved["tocHighlights.revision"] = revision
                    start(value.parameters, false)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == 0L) failed(error.localizedMessage ?: "Error") }
        }
    }
    fun bind(parameters: TocHighlightsParameters) = start(parameters, true)
    private fun start(parameters: TocHighlightsParameters, persist: Boolean) {
        val old = state.value.parameters
        val contextChanged = old != null && (old.bookUrl != parameters.bookUrl || old.supported != parameters.supported) ||
            state.value.open?.context?.let { it != context(parameters) } == true
        collecting?.cancel(); val currentGeneration = ++generation
        if (contextChanged) { saved.remove<String>("tocHighlights.open"); mutable.value = state.value.copy(open = null) }
        mutable.value = state.value.copy(parameters = parameters, loaded = false, rows = emptyList(), scrollRequest = 0, error = null)
        collecting = viewModelScope.launch {
            try {
                if (persist) {
                    val disk = repository.checkpoint(session); currentCoroutineContext().ensureActive()
                    if (generation != currentGeneration) return@launch
                    revision = maxOf(revision, disk?.revision ?: 0L) + 1
                    saved["tocHighlights.revision"] = revision
                    repository.checkpoint(session, TocHighlightsCheckpoint(parameters, revision))
                }
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration) return@launch
                repository.observe(parameters).collect { rows ->
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || state.value.parameters != parameters) return@collect
                val token = ++nextScroll; saved["tocHighlights.nextScroll"] = token
                mutable.value = state.value.copy(loaded = true, rows = rows, scrollRequest = token,
                    scrollTarget = rows.indexOfLast { it.chapterIndex?.let { index -> index < parameters.chapter } == true }.coerceAtLeast(0), error = null)
            } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") }
        }
    }
    fun retry() { collecting?.cancel(); collecting = null; state.value.parameters?.let(::bind) }
    fun scrolled(token: Long) { if (state.value.scrollRequest == token) mutable.value = state.value.copy(scrollRequest = 0) }
    fun open(id: Long, edit: Boolean) {
        if (state.value.open != null || !state.value.loaded || state.value.rows.none { it.id == id }) return
        val value = TocHighlightOpen(id, edit, context = state.value.parameters?.let(::context)); saved["tocHighlights.open"] = GSON.toJson(value); mutable.value = state.value.copy(open = value)
    }
    suspend fun resolve(value: TocHighlightOpen): TocHighlightTarget? {
        if (state.value.open?.nonce != value.nonce) return null
        val parameters = state.value.parameters ?: return null
        val row = repository.resolve(parameters, value.id); currentCoroutineContext().ensureActive()
        return row.takeIf { state.value.open?.nonce == value.nonce && state.value.parameters?.let { it.bookUrl == parameters.bookUrl && it.supported == parameters.supported } == true }
    }
    fun delivered(nonce: String): TocHighlightOpen? {
        val value = state.value.open?.takeIf { it.nonce == nonce } ?: return null
        saved.remove<String>("tocHighlights.open"); mutable.value = state.value.copy(open = null); return value
    }
    fun failed(message: String) { mutable.value = state.value.copy(error = message) }
    fun clearError() { mutable.value = state.value.copy(error = null) }
    fun unbind() { generation++; restoring?.cancel(); collecting?.cancel(); collecting = null }
    private fun context(parameters: TocHighlightsParameters): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${parameters.bookUrl.length}:${parameters.bookUrl}:${parameters.supported}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    override fun onCleared() {
        stop()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { repository.release(session) } }
        super.onCleared()
    }
    fun stop() { viewModelScope.cancel() }
}
