package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.repository.*
import io.legado.app.utils.stackTraceStr
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

enum class RssArticlesRetry {
    Refresh,
    NextPage,
}

data class RssArticlesOpen(
    val key: String,
    val owner: String,
    val nonce: String = UUID.randomUUID().toString(),
)

data class RssArticlesPageState(
    val loaded: Boolean = false,
    val rows: List<RssArticleRow> = emptyList(),
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val retry: RssArticlesRetry? = null,
    val error: String? = null,
    val open: RssArticlesOpen? = null,
    val firstVisible: Int = 0,
    val firstOffset: Int = 0,
    val scrollRequest: Long = 0,
)

class RssArticlesPageViewModel(
    private val repository: RssArticlesPageRepository,
    private val sessions: RssArticlesSessionRepository,
    private val saved: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val session =
        saved.get<String>("rssArticles.session")
            ?: UUID.randomUUID().toString().also { saved["rssArticles.session"] = it }
    private val mutable =
        MutableStateFlow(
            RssArticlesPageState(
                firstVisible = saved.get<Int>("rssArticles.first") ?: 0,
                firstOffset = saved.get<Int>("rssArticles.offset") ?: 0,
                open =
                    saved.get<String>("rssArticles.openKey")?.let { key ->
                        RssArticlesOpen(
                            key,
                            saved.get<String>("rssArticles.openOwner").orEmpty(),
                            saved.get<String>("rssArticles.openNonce")
                                ?: UUID.randomUUID().toString(),
                        )
                    },
            )
        )
    val state: StateFlow<RssArticlesPageState> = mutable
    private var parameters: RssArticlesParameters? = null
    private var pagination = RssPaginationState()
    private var revision = saved.get<Long>("rssArticles.revision") ?: 0L
    private var nextScroll = saved.get<Long>("rssArticles.nextScroll") ?: 0L
    private var generation = 0L
    private var ready = false
    private var initialized = false
    private var visible = false
    private var order = 0L
    private var restoring: Job? = null
    private var collecting: Job? = null
    private var loading: Job? = null

    fun bind(value: RssArticlesParameters) {
        if (parameters == value && (ready || restoring?.isActive == true)) return
        val oldOwner = parameters?.let(::owner) ?: saved.get<String>("rssArticles.owner")
        val newOwner = owner(value)
        if (oldOwner != null && oldOwner != newOwner) {
            clearOpen()
            saved["rssArticles.first"] = 0
            saved["rssArticles.offset"] = 0
            mutable.value = state.value.copy(firstVisible = 0, firstOffset = 0)
        }
        saved["rssArticles.owner"] = newOwner
        parameters = value
        restoring?.cancel()
        collecting?.cancel()
        loading?.cancel()
        val current = ++generation
        ready = false
        initialized = false
        pagination = RssPaginationState()
        mutable.value =
            state.value.copy(
                loaded = false,
                refreshing = false,
                loadingMore = false,
                rows = emptyList(),
                error = null,
                retry = null,
                hasMore = false,
            )
        restoring = viewModelScope.launch {
            try {
                val disk = sessions.read(session)
                currentCoroutineContext().ensureActive()
                if (generation != current) return@launch
                revision = maxOf(revision, disk?.revision ?: 0)
                val restored = disk?.takeIf { it.parameters == value }
                if (restored != null) {
                    initialized = restored.initialized
                    order = restored.order
                    pagination.restore(
                        restored.page,
                        restored.nextUrl,
                        restored.retry?.let {
                            runCatching { RssRetryTarget.valueOf(it) }.getOrNull()
                        },
                    )
                } else order = clock()
                publishPagination(restored?.hasMore ?: pagination.hasNextPage)
                sessions.write(session, checkpoint())
                currentCoroutineContext().ensureActive()
                if (generation != current) return@launch
                ready = true
                publishPagination(restored?.hasMore ?: pagination.hasNextPage)
                observe(current, value)
                if (!initialized && (visible || value.preload)) refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error)
            }
        }
    }

    private fun observe(current: Long, value: RssArticlesParameters) {
        collecting?.cancel()
        collecting = viewModelScope.launch {
            try {
                repository.observe(value).collect { rows ->
                    currentCoroutineContext().ensureActive()
                    if (current != generation) return@collect
                    val first = !state.value.loaded
                    val token = if (first) ++nextScroll else state.value.scrollRequest
                    if (first) saved["rssArticles.nextScroll"] = nextScroll
                    mutable.value =
                        state.value.copy(loaded = true, rows = rows.toList(), scrollRequest = token)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) {
                    AppLog.put("订阅文章界面获取数据失败\n${error.localizedMessage}", error)
                    failed(error)
                }
            }
        }
    }

    fun active(value: Boolean) {
        visible = value
        if (value && ready && !initialized && !pagination.isLoading) refresh()
    }

    fun refresh() {
        val params = parameters ?: return
        if (!ready) return
        val engine = pagination
        val request =
            when (val result = engine.startRefresh()) {
                RssRefreshAction.InProgress -> return
                is RssRefreshAction.Request -> result
            }
        val current = generation
        order = clock()
        mutable.value =
            state.value.copy(refreshing = true, loadingMore = false, error = null, retry = null)
        loading = viewModelScope.launch {
            try {
                val batch = repository.fetch(params, params.sortUrl, request.page)
                currentCoroutineContext().ensureActive()
                if (current != generation || !engine.isActive(request)) return@launch
                val commit = repository.refresh(params, batch, order)
                currentCoroutineContext().ensureActive()
                if (current != generation || !engine.completeRefresh(request, batch.nextUrl))
                    return@launch
                order = commit.order
                initialized = true
                mutable.value =
                    state.value.copy(
                        refreshing = false,
                        loadingMore = false,
                        hasMore = commit.added && engine.hasNextPage,
                        retry = null,
                        error = null,
                    )
                persist(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation && engine.failRefresh(request)) {
                    initialized = true
                    mutable.value =
                        state.value.copy(
                            refreshing = false,
                            retry = RssArticlesRetry.Refresh,
                            error = error.stackTraceStr,
                        )
                    AppLog.put("rss获取内容失败", error)
                    persist(current)
                }
            }
        }
    }

    fun more() {
        val params = parameters ?: return
        if (!ready) return
        val engine = pagination
        val request =
            when (val result = engine.startNextPage()) {
                RssNextPageAction.InProgress -> return
                is RssNextPageAction.NoMore -> {
                    mutable.value = state.value.copy(hasMore = false, loadingMore = false)
                    return
                }
                is RssNextPageAction.Request -> result
            }
        val current = generation
        mutable.value = state.value.copy(loadingMore = true, error = null, retry = null)
        loading = viewModelScope.launch {
            try {
                val batch = repository.fetch(params, request.url, request.page)
                currentCoroutineContext().ensureActive()
                if (current != generation || !engine.isActive(request)) return@launch
                val commit = repository.append(params, batch, order)
                currentCoroutineContext().ensureActive()
                if (current != generation || !engine.completeNextPage(request, batch.nextUrl))
                    return@launch
                order = commit.order
                mutable.value =
                    state.value.copy(
                        loadingMore = false,
                        hasMore = commit.added && engine.hasNextPage,
                        retry = null,
                        error = null,
                    )
                persist(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation && engine.failNextPage(request)) {
                    mutable.value =
                        state.value.copy(
                            loadingMore = false,
                            retry = RssArticlesRetry.NextPage,
                            error = error.stackTraceStr,
                        )
                    AppLog.put("rss获取内容失败", error)
                    persist(current)
                }
            }
        }
    }

    private fun checkpoint(): RssArticlesSession {
        val params = checkNotNull(parameters)
        revision++
        saved["rssArticles.revision"] = revision
        return RssArticlesSession(
            params,
            initialized,
            pagination.page,
            pagination.nextPageUrl,
            pagination.retryTarget?.name,
            order,
            revision,
            state.value.hasMore,
        )
    }

    private fun persist(current: Long) {
        val value = checkpoint()
        viewModelScope.launch {
            try {
                sessions.write(session, value)
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation && value.revision == revision) failed(error)
            }
        }
    }

    private fun publishPagination(hasMore: Boolean) {
        mutable.value =
            state.value.copy(
                hasMore = hasMore,
                retry = pagination.retryTarget?.let { RssArticlesRetry.valueOf(it.name) },
            )
    }

    fun position(first: Int, offset: Int) {
        if (!state.value.loaded) return
        val index = first.coerceAtLeast(0)
        val pixels = offset.coerceAtLeast(0)
        saved["rssArticles.first"] = index
        saved["rssArticles.offset"] = pixels
        mutable.value = state.value.copy(firstVisible = index, firstOffset = pixels)
    }

    fun scrolled(token: Long) {
        if (state.value.scrollRequest == token) mutable.value = state.value.copy(scrollRequest = 0)
    }

    fun open(key: String) {
        val params = parameters ?: return
        if (
            !state.value.loaded ||
                state.value.open != null ||
                state.value.rows.none { it.key == key }
        )
            return
        val value = RssArticlesOpen(key, owner(params))
        saved["rssArticles.openKey"] = key
        saved["rssArticles.openOwner"] = value.owner
        saved["rssArticles.openNonce"] = value.nonce
        mutable.value = state.value.copy(open = value)
    }

    suspend fun resolve(value: RssArticlesOpen): RssArticle? {
        val params = parameters ?: return null
        if (state.value.open?.nonce != value.nonce || value.owner != owner(params)) return null
        val current = generation
        val result = repository.resolve(params, value.key)
        currentCoroutineContext().ensureActive()
        return result.takeIf {
            current == generation &&
                state.value.open?.nonce == value.nonce &&
                parameters?.let(::owner) == value.owner
        }
    }

    suspend fun resolvePrepared(
        value: RssArticlesOpen,
        resolver: RssArticlesReadRepository,
    ): RssArticlesRead? {
        val params = parameters ?: return null
        if (state.value.open?.nonce != value.nonce || value.owner != owner(params)) return null
        val current = generation
        val result = resolver.prepare(params, value.key)
        currentCoroutineContext().ensureActive()
        return result.takeIf {
            current == generation &&
                state.value.open?.nonce == value.nonce &&
                parameters?.let(::owner) == value.owner
        }
    }

    fun delivered(nonce: String): RssArticlesOpen? {
        val value = state.value.open?.takeIf { it.nonce == nonce } ?: return null
        clearOpen()
        return value
    }

    private fun clearOpen() {
        saved.remove<String>("rssArticles.openKey")
        saved.remove<String>("rssArticles.openOwner")
        saved.remove<String>("rssArticles.openNonce")
        mutable.value = state.value.copy(open = null)
    }

    fun retry() {
        if (!ready) {
            parameters?.let { bind(it) }
            return
        }
        when (state.value.retry) {
            RssArticlesRetry.Refresh -> refresh()
            RssArticlesRetry.NextPage -> more()
            null -> {
                persist(generation)
                parameters?.let { observe(generation, it) }
                mutable.value = state.value.copy(error = null)
            }
        }
    }

    private fun failed(error: Exception) {
        mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
    }

    fun failed(message: String) {
        mutable.value = state.value.copy(error = message)
    }

    private fun owner(params: RssArticlesParameters): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                "${params.sourceUrl.length}:${params.sourceUrl}${params.sortName.length}:${params.sortName}${params.sortUrl.length}:${params.sortUrl}${params.query?.length ?: -1}:${params.query.orEmpty()}"
                    .toByteArray()
            )
            .joinToString("") { "%02x".format(it) }

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
