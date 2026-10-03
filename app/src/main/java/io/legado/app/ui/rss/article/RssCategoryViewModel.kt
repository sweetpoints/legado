package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RssCategoryEffectKind {
    Login,
    EditSource,
    Variable,
    ReadRecords,
}

data class RssCategoryEffect(
    val kind: RssCategoryEffectKind,
    val nonce: String = UUID.randomUUID().toString(),
)

data class RssCategoryState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val missingSource: Boolean = false,
    val request: RssCategoryRequest? = null,
    val sourceName: String = "",
    val tabs: List<RssCategoryTab> = emptyList(),
    val selected: Int = 0,
    val style: Int = 0,
    val preload: Boolean = false,
    val canSearch: Boolean = false,
    val canLogin: Boolean = false,
    val draft: String = "",
    val searchOpen: Boolean = false,
    val menuOpen: Boolean = false,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val contentRevision: Long = 0,
    val error: String? = null,
    val pending: RssCategoryEffect? = null,
)

/**
 * State contains owned projections; full requests and search drafts are persisted only in the
 * private session.
 */
class RssCategoryViewModel(
    private val repository: RssCategoryRepository,
    private val sessions: RssCategorySessionRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val session =
        saved.get<String>("rssCategory.session")
            ?: UUID.randomUUID().toString().also { saved["rssCategory.session"] = it }
    private val mutable =
        MutableStateFlow(
            RssCategoryState(
                selected = saved.get<Int>("rssCategory.selected") ?: 0,
                searchOpen = saved.get<Boolean>("rssCategory.searchOpen") == true,
                menuOpen = saved.get<Boolean>("rssCategory.menuOpen") == true,
                selectionStart = saved.get<Int>("rssCategory.selectionStart") ?: 0,
                selectionEnd = saved.get<Int>("rssCategory.selectionEnd") ?: 0,
                pending =
                    saved.get<String>("rssCategory.effectKind")?.let { name ->
                        runCatching {
                            RssCategoryEffect(
                                RssCategoryEffectKind.valueOf(name),
                                saved.get<String>("rssCategory.effectNonce")
                                    ?: UUID.randomUUID().toString(),
                            )
                        }
                            .getOrNull()
                    },
            )
        )
    val state: StateFlow<RssCategoryState> = mutable
    private var revision = saved.get<Long>("rssCategory.revision") ?: 0L
    private var generation = 0L
    private var loading: Job? = null
    private var source: RssSource? = null
    private var requested: RssCategoryRequest? = null
    private var requestedDraft: String? = null

    fun sourceSnapshot(): RssSource? = source?.copy()

    fun bind(request: RssCategoryRequest? = null, reuseSortUrl: Boolean = false) {
        if (request == null && (state.value.loaded || loading?.isActive == true)) return
        val retrying =
            !state.value.loaded && request != null && request == requested && requestedDraft != null
        if (request != null && !retrying) {
            requested = request
            requestedDraft = null
        }
        loading?.cancel()
        val current = ++generation
        mutable.value =
            state.value.copy(loaded = false, busy = false, error = null, missingSource = false)
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(session)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                revision = maxOf(revision, disk?.revision ?: 0)
                val previous = state.value.request ?: disk?.request
                val input =
                    request?.let { incoming ->
                        if (reuseSortUrl && incoming.sortUrl == null)
                            incoming.copy(sortUrl = previous?.sortUrl)
                        else incoming
                    } ?: previous
                if (input == null) {
                    mutable.value = state.value.copy(missingSource = true)
                    return@launch
                }
                requested = input
                val changedOwner = previous != null && previous.sourceUrl != input.sourceUrl
                if (changedOwner) {
                    clearEffect()
                    saved["rssCategory.searchOpen"] = false
                    saved["rssCategory.menuOpen"] = false
                    mutable.value =
                        state.value.copy(
                            searchOpen = false,
                            menuOpen = false,
                            selectionStart = 0,
                            selectionEnd = 0,
                        )
                }
                val draft =
                    if (retrying) requestedDraft!!
                    else if (request == null) disk?.draft ?: state.value.draft
                    else input.query.orEmpty()
                requestedDraft = draft
                val next = checkpoint(input, draft)
                sessions.write(session, next)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                val snapshot = repository.load(input)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                source = snapshot.source?.copy()
                project(snapshot, input, draft, if (changedOwner) 0 else state.value.selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error)
            }
        }
    }

    private fun checkpoint(request: RssCategoryRequest, draft: String): RssCategorySession {
        revision++
        saved["rssCategory.revision"] = revision
        return RssCategorySession(request, draft, revision)
    }

    private fun project(
        snapshot: RssCategorySnapshot,
        request: RssCategoryRequest,
        draft: String,
        selected: Int,
    ) {
        val source = snapshot.source
        val index = selected.coerceIn(0, (snapshot.tabs.size - 1).coerceAtLeast(0))
        saved["rssCategory.selected"] = index
        val start = state.value.selectionStart.coerceIn(0, draft.length)
        val end = state.value.selectionEnd.coerceIn(0, draft.length)
        saved["rssCategory.selectionStart"] = start
        saved["rssCategory.selectionEnd"] = end
        mutable.value =
            state.value.copy(
                loaded = true,
                busy = false,
                missingSource = source == null,
                request = request,
                sourceName = source?.sourceName.orEmpty(),
                tabs = snapshot.tabs.toList(),
                selected = index,
                style = source?.articleStyle ?: 0,
                preload = source?.preload == true,
                canSearch = !source?.searchUrl.isNullOrBlank(),
                canLogin = !source?.loginUrl.isNullOrBlank(),
                draft = draft,
                selectionStart = start,
                selectionEnd = end,
                contentRevision = state.value.contentRevision + 1,
                error = null,
            )
    }

    fun select(index: Int) {
        if (!state.value.loaded || index !in state.value.tabs.indices) return
        saved["rssCategory.selected"] = index
        mutable.value = state.value.copy(selected = index, menuOpen = false)
        saved["rssCategory.menuOpen"] = false
    }

    fun menu(open: Boolean) {
        if (!state.value.loaded || state.value.busy) return
        saved["rssCategory.menuOpen"] = open
        mutable.value = state.value.copy(menuOpen = open)
    }

    fun search(open: Boolean) {
        if (!state.value.loaded || state.value.busy || open && !state.value.canSearch) return
        saved["rssCategory.searchOpen"] = open
        mutable.value = state.value.copy(searchOpen = open, menuOpen = false)
        saved["rssCategory.menuOpen"] = false
    }

    fun draft(text: String, start: Int = text.length, end: Int = start) {
        val request = state.value.request ?: return
        if (!state.value.loaded || state.value.busy) return
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        saved["rssCategory.selectionStart"] = a
        saved["rssCategory.selectionEnd"] = b
        mutable.value = state.value.copy(draft = text, selectionStart = a, selectionEnd = b)
        persist(checkpoint(request, text), generation)
    }

    private fun persist(value: RssCategorySession, owner: Long) {
        viewModelScope.launch {
            try {
                sessions.write(session, value)
                currentCoroutineContext().ensureActive()
                if (owner == generation && revision == value.revision)
                    mutable.value = state.value.copy(error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (owner == generation && revision == value.revision) failed(error)
            }
        }
    }

    fun submitSearch() {
        val request = state.value.request ?: return
        if (!state.value.loaded || state.value.busy || !state.value.canSearch) return
        search(false)
        bind(request.copy(query = state.value.draft))
    }

    fun exitSearch(): Boolean {
        val request = state.value.request ?: return false
        if (request.query == null || state.value.busy) return false
        search(false)
        bind(request.copy(query = null))
        return true
    }

    fun refresh() = mutate { request -> repository.load(request, refresh = true) }

    fun switchStyle() = mutate { request ->
        request.sourceUrl?.let { repository.switchStyle(it) }
        repository.load(request)
    }

    fun clearArticles() = mutate { request ->
        request.sourceUrl?.let { repository.clearArticles(it) }
        repository.load(request)
    }

    fun edited() = mutate { request -> repository.load(request) }

    private fun mutate(action: suspend (RssCategoryRequest) -> RssCategorySnapshot) {
        val request = state.value.request ?: return
        if (!state.value.loaded || state.value.busy) return
        val owner = generation
        mutable.value = state.value.copy(busy = true, menuOpen = false, error = null)
        saved["rssCategory.menuOpen"] = false
        viewModelScope.launch {
            try {
                val snapshot = action(request)
                currentCoroutineContext().ensureActive()
                if (owner != generation) return@launch
                source = snapshot.source?.copy()
                project(snapshot, request, state.value.draft, state.value.selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (owner == generation) {
                    mutable.value = state.value.copy(busy = false)
                    failed(error)
                }
            }
        }
    }

    fun effect(kind: RssCategoryEffectKind) {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.pending != null ||
                state.value.request?.sourceUrl == null
        )
            return
        if (kind == RssCategoryEffectKind.Login && !state.value.canLogin) return
        val value = RssCategoryEffect(kind)
        saved["rssCategory.effectKind"] = kind.name
        saved["rssCategory.effectNonce"] = value.nonce
        mutable.value = state.value.copy(pending = value, menuOpen = false)
        saved["rssCategory.menuOpen"] = false
    }

    suspend fun variable(effect: RssCategoryEffect): RssCategoryVariable? {
        val key = state.value.request?.sourceUrl ?: return null
        if (
            state.value.pending?.nonce != effect.nonce ||
                effect.kind != RssCategoryEffectKind.Variable
        )
            return null
        val owner = generation
        val value = repository.variable(key)
        currentCoroutineContext().ensureActive()
        return value.takeIf { generation == owner && state.value.pending?.nonce == effect.nonce }
    }

    fun setVariable(key: String, value: String?) {
        if (state.value.request?.sourceUrl != key) return
        val owner = generation
        viewModelScope.launch {
            try {
                repository.variable(key, value)
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (owner == generation) failed(error)
            }
        }
    }

    fun delivered(nonce: String): RssCategoryEffect? {
        val effect = state.value.pending?.takeIf { it.nonce == nonce } ?: return null
        clearEffect()
        return effect
    }

    private fun clearEffect() {
        saved.remove<String>("rssCategory.effectKind")
        saved.remove<String>("rssCategory.effectNonce")
        mutable.value = state.value.copy(pending = null)
    }

    fun failed(message: String) {
        mutable.value = state.value.copy(error = message)
    }

    private fun failed(error: Exception) {
        mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
    }

    fun retry() {
        if (!state.value.loaded) bind(requested)
        else
            state.value.request?.let {
                val value = checkpoint(it, state.value.draft)
                mutate { request ->
                    sessions.write(session, value)
                    repository.load(request)
                }
            }
    }

    fun stop() {
        generation++
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sessions.release(session) }
        }
        super.onCleared()
    }
}
