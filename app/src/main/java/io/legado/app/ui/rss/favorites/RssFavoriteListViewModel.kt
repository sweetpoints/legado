package io.legado.app.ui.rss.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

enum class FavoriteDeleteKind {
    Row,
    Group,
    All,
}

data class FavoriteDeleteConfirmation(val kind: FavoriteDeleteKind, val target: String)

data class FavoriteScrollPosition(val index: Int = 0, val offset: Int = 0)

data class RssFavoriteListState(
    val groups: List<String> = emptyList(),
    val rows: List<RssFavoriteRow> = emptyList(),
    val group: String? = null,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val confirmation: FavoriteDeleteConfirmation? = null,
    val pendingRead: String? = null,
) {
    val visibleRows
        get() = rows.filter { it.group == group }
}

class RssFavoriteListViewModel(
    private val repository: RssFavoriteListRepository,
    private val saved: SavedStateHandle,
    private val fixedGroup: String? = null,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            RssFavoriteListState(
                group = fixedGroup ?: saved.get<String>("favorites.group"),
                pendingRead = saved.get<String>("favorites.read"),
                confirmation =
                    saved.get<String>("favorites.deleteKind")?.let {
                        runCatching {
                            FavoriteDeleteConfirmation(
                                FavoriteDeleteKind.valueOf(it),
                                saved.get<String>("favorites.deleteTarget") ?: "",
                            )
                        }
                            .getOrNull()
                    },
            )
        )
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var operation: Job? = null
    private var selectedIndex = saved.get<Int>("favorites.groupIndex") ?: 0

    init {
        load()
    }

    fun load() {
        if (observer?.isActive == true) return
        mutable.value = state.value.copy(error = null)
        observer = viewModelScope.launch {
            try {
                repository.observe().collect { snapshot ->
                    currentCoroutineContext().ensureActive()
                    val groups = if (fixedGroup == null) snapshot.groups else listOf(fixedGroup)
                    val current = state.value.group
                    val group =
                        if (fixedGroup != null) fixedGroup
                        else
                            current?.takeIf { it in groups }
                                ?: groups.getOrNull(
                                    selectedIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                                )
                    selectedIndex = groups.indexOf(group).coerceAtLeast(0)
                    saved["favorites.group"] = group
                    saved["favorites.groupIndex"] = selectedIndex
                    val confirmation =
                        state.value.confirmation?.takeIf {
                            when (it.kind) {
                                FavoriteDeleteKind.Row ->
                                    snapshot.rows.any { row -> row.id == it.target }
                                FavoriteDeleteKind.Group -> it.target in snapshot.groups
                                FavoriteDeleteKind.All -> true
                            }
                        }
                    storeConfirmation(confirmation)
                    mutable.value =
                        state.value.copy(
                            groups = groups,
                            rows = snapshot.rows,
                            group = group,
                            loaded = true,
                            error = null,
                            confirmation = confirmation,
                        )
                }
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    fun selectGroup(group: String) {
        if (fixedGroup != null || group !in state.value.groups) return
        selectedIndex = state.value.groups.indexOf(group)
        saved["favorites.group"] = group
        saved["favorites.groupIndex"] = selectedIndex
        mutable.value = state.value.copy(group = group)
    }

    fun scrollPosition(group: String): FavoriteScrollPosition {
        val prefix = scrollKey(group)
        return FavoriteScrollPosition(
            saved.get<Int>("$prefix.index") ?: 0,
            saved.get<Int>("$prefix.offset") ?: 0,
        )
    }

    fun scrolled(group: String, index: Int, offset: Int) {
        if (group !in state.value.groups) return
        val prefix = scrollKey(group)
        saved["$prefix.index"] = index.coerceAtLeast(0)
        saved["$prefix.offset"] = offset.coerceAtLeast(0)
    }

    private fun scrollKey(group: String) =
        "favorites.scroll.${RoomRssFavoriteListRepository.key(group, "") }"

    fun read(id: String) {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.pendingRead != null ||
                state.value.visibleRows.none { it.id == id }
        )
            return
        saved["favorites.read"] = id
        mutable.value = state.value.copy(pendingRead = id)
    }

    suspend fun resolveRead(id: String): RssStar? {
        if (state.value.pendingRead != id) return null
        val article = repository.resolve(id)
        currentCoroutineContext().ensureActive()
        return article
    }

    /** Acknowledge only after the Route rechecks its resumed native host. */
    fun readDelivered(id: String) {
        if (state.value.pendingRead != id) return
        saved["favorites.read"] = null
        mutable.value = state.value.copy(pendingRead = null)
    }

    fun requestDelete(id: String) {
        if (!state.value.loaded || state.value.busy || state.value.visibleRows.none { it.id == id })
            return
        confirmation(FavoriteDeleteConfirmation(FavoriteDeleteKind.Row, id))
    }

    fun requestDeleteGroup() {
        if (!state.value.loaded || state.value.busy) return
        state.value.group?.let {
            if (it in state.value.groups)
                confirmation(FavoriteDeleteConfirmation(FavoriteDeleteKind.Group, it))
        }
    }

    fun requestDeleteAll() {
        if (state.value.loaded && !state.value.busy)
            confirmation(FavoriteDeleteConfirmation(FavoriteDeleteKind.All, ""))
    }

    private fun storeConfirmation(value: FavoriteDeleteConfirmation?) {
        saved["favorites.deleteKind"] = value?.kind?.name
        saved["favorites.deleteTarget"] = value?.target
    }

    private fun confirmation(value: FavoriteDeleteConfirmation?) {
        storeConfirmation(value)
        mutable.value = state.value.copy(confirmation = value)
    }

    fun cancelConfirmation() {
        if (!state.value.busy) confirmation(null)
    }

    fun confirmDelete() {
        val request = state.value.confirmation ?: return
        if (!state.value.loaded || state.value.busy) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                when (request.kind) {
                    FavoriteDeleteKind.Row -> repository.delete(request.target)
                    FavoriteDeleteKind.Group -> repository.deleteGroup(request.target)
                    FavoriteDeleteKind.All -> repository.deleteAll()
                }
                currentCoroutineContext().ensureActive()
                confirmation(null)
                mutable.value = state.value.copy(busy = false)
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        mutable.value =
            state.value.copy(busy = false, error = error.localizedMessage ?: error.toString())
    }

    fun stop() {
        observer?.cancel()
        operation?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
