package io.legado.app.ui.main.rss

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class MainRssAction {
    Open,
    Edit,
    Login,
    Subscriptions,
    History,
    Favorites,
    Settings,
}

enum class MainRssIssue {
    SourceMissing
}

data class MainRssEffect(val action: MainRssAction, val nonce: String)

data class MainRssState(
    val loaded: Boolean = false,
    val active: Boolean = false,
    val busy: Boolean = false,
    val rows: List<MainRssRow> = emptyList(),
    val groups: List<String> = emptyList(),
    val query: String = "",
    val queryStart: Int = 0,
    val queryEnd: Int = 0,
    val deletingId: String? = null,
    val deletingName: String? = null,
    val pending: MainRssEffect? = null,
    val issue: MainRssIssue? = null,
    val error: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)

/**
 * A retained owner keeps one private query/navigation checkpoint across the pager's visible
 * lifecycle.
 */
class MainRssViewModel(
    private val repository: MainRssRepository,
    private val sessions: MainRssSessionRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    internal val sessionId =
        saved.get<String>("mainRss.session")
            ?: UUID.randomUUID().toString().also { saved["mainRss.session"] = it }
    private val id
        get() = sessionId

    private var revision = saved.get<Long>("mainRss.revision") ?: 0L
    private val mutable =
        MutableStateFlow(
            MainRssState(
                scrollIndex = saved.get<Int>("mainRss.scrollIndex") ?: 0,
                scrollOffset = saved.get<Int>("mainRss.scrollOffset") ?: 0,
            )
        )
    val state: StateFlow<MainRssState> = mutable
    private var checkpoint = MainRssCheckpoint()
    private var generation = 0L
    private var loading: Job? = null
    private var rowsJob: Job? = null
    private var groupsJob: Job? = null
    private var retainSessionOnClear = false
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bind() {
        if (state.value.loaded || loading?.isActive == true) return
        val epoch = generation
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(id)
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                revision = maxOf(revision, disk?.revision ?: 0)
                checkpoint = disk ?: checkpoint
                checkpoint =
                    checkpoint.copy(
                        pending =
                            checkpoint.pending?.takeUnless {
                                it.nonce == saved.get<String>("mainRss.delivered")
                            }
                    )
                project()
                observe()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    fun visible(value: Boolean) {
        if (state.value.active == value) return
        mutable.value = state.value.copy(active = value)
        observe()
    }

    private fun project() {
        mutable.value =
            state.value.copy(
                loaded = true,
                busy = false,
                query = checkpoint.query,
                queryStart = checkpoint.queryStart,
                queryEnd = checkpoint.queryEnd,
                deletingId = checkpoint.deletingId,
                deletingName = checkpoint.deletingName,
                pending =
                    checkpoint.pending?.let {
                        runCatching { MainRssEffect(MainRssAction.valueOf(it.action), it.nonce) }
                            .getOrNull()
                    },
                issue = null,
                error = null,
            )
    }

    private fun observe() {
        rowsJob?.cancel()
        groupsJob?.cancel()
        if (!state.value.loaded || !state.value.active) return
        val epoch = generation
        rowsJob = viewModelScope.launch {
            try {
                repository.rows(checkpoint.query).collect { rows ->
                    currentCoroutineContext().ensureActive()
                    if (epoch == generation && state.value.active)
                        mutable.value = state.value.copy(rows = rows)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation && state.value.active) failed(error)
            }
        }
        groupsJob = viewModelScope.launch {
            try {
                repository.groups().collect { groups ->
                    currentCoroutineContext().ensureActive()
                    if (epoch == generation && state.value.active)
                        mutable.value = state.value.copy(groups = groups)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation && state.value.active) failed(error)
            }
        }
    }

    private fun editable() =
        state.value.loaded && state.value.active && !state.value.busy && state.value.pending == null

    private fun next() {
        revision++
        saved["mainRss.revision"] = revision
        checkpoint = checkpoint.copy(revision = revision)
    }

    private fun persist() {
        next()
        val value = checkpoint
        val epoch = generation
        viewModelScope.launch {
            try {
                sessions.write(id, value)
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation && revision == value.revision) failed(error)
            }
        }
    }

    fun query(value: String, start: Int = value.length, end: Int = start) {
        if (!editable()) return
        val changed = value != checkpoint.query
        checkpoint =
            checkpoint.copy(
                query = value,
                queryStart = start.coerceIn(0, value.length),
                queryEnd = end.coerceIn(0, value.length),
            )
        project()
        persist()
        if (changed) observe()
    }

    fun requestDelete(id: String) {
        if (!editable()) return
        val row = state.value.rows.firstOrNull { it.id == id } ?: return
        checkpoint = checkpoint.copy(deletingId = id, deletingName = row.name)
        project()
        persist()
    }

    fun cancelDelete() {
        if (!editable()) return
        checkpoint = checkpoint.copy(deletingId = null, deletingName = null)
        project()
        persist()
    }

    fun confirmDelete() {
        val target = checkpoint.deletingId ?: return
        mutate {
            repository.delete(target)
            currentCoroutineContext().ensureActive()
            checkpoint = checkpoint.copy(deletingId = null, deletingName = null)
            project()
            persist()
        }
    }

    fun top(id: String) = mutate { repository.top(id) }

    fun disable(id: String) = mutate { repository.disable(id) }

    private fun mutate(block: suspend () -> Unit) {
        if (!editable()) return
        val epoch = generation
        mutable.value = state.value.copy(busy = true, issue = null, error = null)
        viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
                if (epoch == generation) mutable.value = state.value.copy(busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    fun action(action: MainRssAction, sourceId: String? = null) {
        if (!editable()) return
        if (
            action in listOf(MainRssAction.Open, MainRssAction.Edit, MainRssAction.Login) &&
                sourceId == null
        )
            return
        val epoch = generation
        mutable.value = state.value.copy(busy = true, issue = null, error = null)
        viewModelScope.launch {
            try {
                val navigation =
                    if (action == MainRssAction.Open) repository.prepare(checkNotNull(sourceId))
                    else null
                val source =
                    if (action in listOf(MainRssAction.Edit, MainRssAction.Login))
                        repository.source(checkNotNull(sourceId))
                    else null
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                if (
                    action == MainRssAction.Open && navigation == null ||
                        action in listOf(MainRssAction.Edit, MainRssAction.Login) && source == null
                ) {
                    mutable.value =
                        state.value.copy(busy = false, issue = MainRssIssue.SourceMissing)
                    return@launch
                }
                checkpoint =
                    checkpoint.copy(
                        pending =
                            MainRssPrepared(
                                action.name,
                                UUID.randomUUID().toString(),
                                sourceId,
                                source?.sourceUrl ?: navigation?.sourceUrl,
                                navigation,
                            )
                    )
                next()
                sessions.write(id, checkpoint)
                currentCoroutineContext().ensureActive()
                if (epoch == generation) project()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    suspend fun resolve(nonce: String): MainRssPrepared? {
        val pending =
            checkpoint.pending?.takeIf { it.nonce == nonce && state.value.pending?.nonce == nonce }
                ?: return null
        if (pending.sourceId != null) {
            val source = repository.source(pending.sourceId)
            currentCoroutineContext().ensureActive()
            if (source == null) return null
            return pending.copy(sourceUrl = source.sourceUrl)
        }
        return pending
    }

    fun delivered(nonce: String): Boolean {
        if (state.value.pending?.nonce != nonce || !state.value.active) return false
        saved["mainRss.delivered"] = nonce
        checkpoint = checkpoint.copy(pending = null)
        project()
        persist()
        return true
    }

    fun missing() {
        mutable.value = state.value.copy(issue = MainRssIssue.SourceMissing)
    }

    fun failed(message: String) {
        mutable.value = state.value.copy(busy = false, error = message)
    }

    private fun failed(error: Exception) =
        failed(error.localizedMessage ?: error.javaClass.simpleName)

    fun retry() {
        if (state.value.busy) return
        if (!state.value.loaded) {
            bind()
            return
        }
        val epoch = generation
        mutable.value = state.value.copy(busy = true, issue = null, error = null)
        viewModelScope.launch {
            try {
                sessions.write(id, checkpoint)
                currentCoroutineContext().ensureActive()
                if (epoch == generation) {
                    project()
                    observe()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    fun scroll(index: Int, offset: Int) {
        saved["mainRss.scrollIndex"] = index.coerceAtLeast(0)
        saved["mainRss.scrollOffset"] = offset.coerceAtLeast(0)
        mutable.value =
            state.value.copy(
                scrollIndex = index.coerceAtLeast(0),
                scrollOffset = offset.coerceAtLeast(0),
            )
    }

    suspend fun flush() {
        sessions.write(id, checkpoint)
    }

    suspend fun prepareForHostMigration() {
        bind()
        awaitHostRestore()
        visible(false)
        flush()
        retainSessionOnClear = true
    }

    suspend fun awaitHostRestore() {
        val restored = state.first { it.loaded || it.error != null }
        check(restored.loaded) { restored.error ?: "RSS会话恢复尚未完成" }
    }

    fun stop() {
        generation++
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        if (!retainSessionOnClear) cleanup.launch { runCatching { sessions.release(id) } }
        super.onCleared()
    }
}
