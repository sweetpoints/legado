package io.legado.app.ui.main.bookshelf.style1

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookshelfHomeRepository
import io.legado.app.ui.main.bookshelf.components.BookshelfHeaderModel
import io.legado.app.ui.main.bookshelf.components.toBookshelfCardModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BookshelfHomeGroup(val id: Long, val name: String, val sort: Int,
    val refresh: Boolean, val onlyRead: Boolean)
data class BookshelfHomeState(val groups: List<BookshelfHomeGroup> = emptyList(), val selectedId: Long? = null,
    val header: BookshelfHeaderModel = BookshelfHeaderModel(), val loading: Boolean = true, val error: String? = null) {
    val selectedIndex: Int get() = groups.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val selectedGroup: BookshelfHomeGroup? get() = groups.getOrNull(selectedIndex)
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookshelfHomeViewModel(private val repository: BookshelfHomeRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(BookshelfHomeState(selectedId = saved["bookshelf.selectedId"]))
    val state = mutableState.asStateFlow()
    private var collection: Job? = null
    fun start() {
        if (collection != null) return
        collection = viewModelScope.launch {
            repository.preferences().flatMapLatest { preferences ->
                combine(repository.groups(), repository.header(preferences)) { groups, header ->
                    Triple(groups.map { BookshelfHomeGroup(it.groupId, it.groupName,
                        if (it.bookSort < 0) preferences.sort else it.bookSort, it.enableRefresh, it.onlyUpdateRead) }, preferences,
                        BookshelfHeaderModel(if (preferences.stats) header.bookCount to header.readingCount else null,
                            header.recent?.takeIf { preferences.recent }?.toBookshelfCardModel(false, 1, false)))
                }
            }.catch { mutableState.value = state.value.copy(loading = false, error = it.localizedMessage ?: it.toString()) }
                .collect { (groups, preferences, header) ->
                    if (groups.isEmpty()) repository.enableAll()
                    val id = if (groups.isEmpty()) state.value.selectedId else state.value.selectedId?.takeIf { selected -> groups.any { it.id == selected } }
                        ?: groups.getOrNull(preferences.selectedPosition.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))?.id
                    if (id != null) saved["bookshelf.selectedId"] = id
                    mutableState.value = BookshelfHomeState(groups, id, header, loading = false)
                }
        }
    }
    fun select(id: Long) {
        val index = state.value.groups.indexOfFirst { it.id == id }
        if (index < 0) return
        saved["bookshelf.selectedId"] = id
        mutableState.value = state.value.copy(selectedId = id)
        repository.select(index)
    }
    fun stop() { collection?.cancel(); collection = null }
    fun retry() { stop(); start() }
    fun refresh() { if (collection != null) retry() }
    override fun onCleared() { stop(); super.onCleared() }
}
