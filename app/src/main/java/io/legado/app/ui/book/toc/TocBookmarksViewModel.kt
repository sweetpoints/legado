package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class TocBookmarkOpen(val id: Long, val edit: Boolean, val nonce: String = UUID.randomUUID().toString(), val context: String? = null)
data class TocBookmarksState(val parameters: TocBookmarksParameters? = null, val loaded: Boolean = false,
    val rows: List<TocBookmarkRow> = emptyList(), val scrollRequest: Long = 0, val scrollTarget: Int = 0,
    val open: TocBookmarkOpen? = null, val error: String? = null)
class TocBookmarksViewModel(private val repository: TocBookmarksRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(TocBookmarksState(
        open = saved.get<String>("tocBookmarks.open")?.let { GSON.fromJsonObject<TocBookmarkOpen>(it).getOrNull() }))
    val state: StateFlow<TocBookmarksState> = mutable
    private val session = saved.get<String>("tocBookmarks.session") ?: UUID.randomUUID().toString().also { saved["tocBookmarks.session"] = it }
    private var generation = 0L
    private var revision = saved.get<Long>("tocBookmarks.revision") ?: 0L
    private var restoring: Job? = null
    private var collecting: Job? = null
    private var nextScroll = saved.get<Long>("tocBookmarks.nextScroll") ?: 0L
    init {
        restoring = viewModelScope.launch {
            try {
                val value = repository.checkpoint(session); currentCoroutineContext().ensureActive()
                if (generation == 0L && value != null) {
                    revision = maxOf(revision, value.revision); saved["tocBookmarks.revision"] = revision
                    start(value.parameters, false)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (generation == 0L) failed(error.localizedMessage ?: "Error") }
        }
    }
    fun bind(parameters: TocBookmarksParameters) = start(parameters, true)
    private fun start(parameters: TocBookmarksParameters, persist: Boolean) {
        if (state.value.parameters == parameters && collecting?.isActive == true) return
        val old = state.value.parameters
        val contextChanged = old != null && (old.name != parameters.name || old.author != parameters.author) ||
            state.value.open?.context?.let { it != context(parameters) } == true
        collecting?.cancel(); val currentGeneration = ++generation
        if (contextChanged) { saved.remove<String>("tocBookmarks.open"); mutable.value = state.value.copy(open = null) }
        mutable.value = state.value.copy(parameters = parameters, loaded = false, rows = emptyList(), scrollRequest = 0, error = null)
        collecting = viewModelScope.launch {
            try {
                if (persist) {
                    val disk = repository.checkpoint(session); currentCoroutineContext().ensureActive()
                    if (generation != currentGeneration) return@launch
                    revision = maxOf(revision, disk?.revision ?: 0L) + 1
                    saved["tocBookmarks.revision"] = revision
                    repository.checkpoint(session, TocBookmarksCheckpoint(parameters, revision))
                }
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration) return@launch
                repository.observe(parameters).collect { rows ->
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || state.value.parameters != parameters) return@collect
                val token = ++nextScroll; saved["tocBookmarks.nextScroll"] = token
                mutable.value = state.value.copy(loaded = true, rows = rows, scrollRequest = token,
                    scrollTarget = rows.indexOfLast { it.chapterIndex < parameters.chapter }.coerceAtLeast(0), error = null)
            } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); mutable.value = state.value.copy(error = error.localizedMessage ?: "Error") }
        }
    }
    fun retry() { collecting?.cancel(); collecting = null; state.value.parameters?.let(::bind) }
    fun scrolled(token: Long) { if (state.value.scrollRequest == token) mutable.value = state.value.copy(scrollRequest = 0) }
    fun open(id: Long, edit: Boolean) {
        if (state.value.open != null || !state.value.loaded || state.value.rows.none { it.id == id }) return
        val value = TocBookmarkOpen(id, edit, context = state.value.parameters?.let(::context)); saved["tocBookmarks.open"] = GSON.toJson(value); mutable.value = state.value.copy(open = value)
    }
    suspend fun resolve(value: TocBookmarkOpen): Bookmark? {
        if (state.value.open?.nonce != value.nonce) return null
        val parameters = state.value.parameters ?: return null
        val row = repository.resolve(parameters, value.id); currentCoroutineContext().ensureActive()
        return row.takeIf { state.value.open?.nonce == value.nonce && state.value.parameters?.let { it.name == parameters.name && it.author == parameters.author } == true }
    }
    fun delivered(nonce: String): TocBookmarkOpen? {
        val value = state.value.open?.takeIf { it.nonce == nonce } ?: return null
        saved.remove<String>("tocBookmarks.open"); mutable.value = state.value.copy(open = null); return value
    }
    fun failed(message: String) { mutable.value = state.value.copy(error = message) }
    fun clearError() { mutable.value = state.value.copy(error = null) }
    fun unbind() { generation++; restoring?.cancel(); collecting?.cancel(); collecting = null }
    private fun context(parameters: TocBookmarksParameters): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${parameters.name.length}:${parameters.name}:${parameters.author.length}:${parameters.author}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    override fun onCleared() {
        stop()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { repository.release(session) } }
        super.onCleared()
    }
    fun stop() { viewModelScope.cancel() }
}
