package io.legado.app.ui.main.bookshelf.style2

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.data.repository.*
import io.legado.app.help.book.isLocal
import io.legado.app.ui.main.bookshelf.components.*
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BookshelfFolderGroup(
    val id: Long,
    val name: String,
    val cover: String?,
    val preview: List<BookshelfBook>,
    val sort: Int,
    val refresh: Boolean,
    val onlyRead: Boolean,
)

data class BookshelfFolderBook(
    val card: BookshelfBookCardModel,
    val cover: String?,
    val sourceOrigin: String?,
)

data class BookshelfFolderState(
    val groupId: Long = BookGroup.IdRoot,
    val groups: List<BookshelfFolderGroup> = emptyList(),
    val books: List<BookshelfFolderBook> = emptyList(),
    val settings: BookshelfPageSettings = BookshelfPageSettings(),
    val header: BookshelfHeaderModel = BookshelfHeaderModel(),
    val loading: Boolean = true,
    val error: String? = null,
    val scrollRequest: Int = 0,
) {
    val selectedGroup: BookshelfFolderGroup?
        get() = groups.find { it.id == groupId }

    val root: Boolean
        get() = groupId == BookGroup.IdRoot

    val itemCount: Int
        get() = books.size + if (root) groups.size else 0

    val onlyRead: Boolean
        get() = if (root) false else selectedGroup?.onlyRead ?: false

    val canRefresh: Boolean
        get() = !loading && itemCount > 0 && (root || selectedGroup?.refresh == true)

    val index: Int
        get() = groups.indexOfFirst { it.id == groupId }

    val previous: Boolean
        get() = !root && index > 0

    val next: Boolean
        get() = !root && index >= 0 && index < groups.lastIndex
}

internal data class BookshelfFolderNavigation(
    val groupId: Long,
    val scrollRequest: Int,
    val nextScroll: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfFolderViewModel(
    private val repository: BookshelfFolderRepository,
    private val saved: SavedStateHandle,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val formatTime: (Long) -> String = { it.toTimeAgo() },
) : ViewModel() {
    private val selected = MutableStateFlow(saved.get<Long>("folder.group") ?: BookGroup.IdRoot)
    private val updates = MutableStateFlow<Set<String>>(emptySet())
    private val revision = MutableStateFlow(0)
    private val mutableState =
        MutableStateFlow(
            BookshelfFolderState(
                groupId = selected.value,
                scrollRequest = saved["folder.scrollRequest"] ?: 0,
            )
        )
    val state = mutableState.asStateFlow()
    private var nextScroll =
        maxOf(saved.get<Int>("folder.nextScroll") ?: 0, state.value.scrollRequest)

    internal fun captureHostNavigation() =
        BookshelfFolderNavigation(selected.value, state.value.scrollRequest, nextScroll)

    internal fun seedHostNavigation(navigation: BookshelfFolderNavigation?) {
        navigation ?: return
        selected.value = navigation.groupId
        nextScroll = navigation.nextScroll
        saved["folder.group"] = navigation.groupId
        saved["folder.scrollRequest"] = navigation.scrollRequest
        saved["folder.nextScroll"] = navigation.nextScroll
        mutableState.update {
            it.copy(groupId = navigation.groupId, scrollRequest = navigation.scrollRequest)
        }
    }

    private var storedBooks: List<Book> = emptyList()
    private var collection: Job? = null
    private var headerJob: Job? = null
    private var ticker: Job? = null

    private data class Configuration(
        val groups: List<BookGroup>,
        val settings: BookshelfPageSettings,
        val preferences: BookshelfHomePreferences,
    )

    private data class Projection(
        val groupId: Long,
        val books: List<Book>,
        val groups: List<BookshelfFolderGroup>,
        val entries: List<BookshelfFolderBook>,
        val settings: BookshelfPageSettings,
    )

    fun start() {
        if (collection != null) return
        collection = viewModelScope.launch {
            selected
                .flatMapLatest { id ->
                    val config =
                        combine(
                            repository.groups(),
                            repository.settings(),
                            repository.preferences(),
                        ) { groups, settings, prefs ->
                            Configuration(groups, settings, prefs)
                        }
                    val previews =
                        if (id == BookGroup.IdRoot) repository.previews()
                        else flowOf(emptyList<BookshelfBook>())
                    combine(config, repository.books(id), previews, updates, revision) {
                        options,
                        books,
                        allBooks,
                        running,
                        _ ->
                        withContext(worker) {
                            val groups =
                                options.groups.map { group ->
                                    BookshelfFolderGroup(
                                        group.groupId,
                                        group.groupName,
                                        group.cover,
                                        folderPreviewBooks(
                                            group,
                                            allBooks,
                                            options.preferences.sort,
                                        ),
                                        group.bookSort.takeIf { it >= 0 }
                                            ?: options.preferences.sort,
                                        group.enableRefresh,
                                        group.onlyUpdateRead,
                                    )
                                }
                            val sort = groups.find { it.id == id }?.sort ?: options.preferences.sort
                            val sorted = sortBookshelfPageBooks(books, sort).map { it.copy() }
                            val entries = sorted.map { book ->
                                BookshelfFolderBook(
                                    book.toBookshelfCardModel(
                                        options.settings.showUnread,
                                        options.settings.readProgressMode,
                                        book.bookUrl in running,
                                        if (
                                            options.settings.showLatestUpdate &&
                                                options.settings.layout < 2 &&
                                                !book.isLocal
                                        )
                                            formatTime(book.latestChapterTime)
                                        else null,
                                    ),
                                    book.getDisplayCover(),
                                    book.getCoverSourceOrigin(),
                                )
                            }
                            Projection(id, sorted, groups, entries, options.settings)
                        }
                    }
                }
                .catch {
                    mutableState.value =
                        state.value.copy(
                            loading = false,
                            error = it.localizedMessage ?: it.toString(),
                        )
                }
                .collect { projection ->
                    if (projection.groupId != selected.value) return@collect
                    if (
                        projection.groupId != BookGroup.IdRoot &&
                            projection.groups.none { it.id == projection.groupId }
                    ) {
                        navigate(BookGroup.IdRoot)
                        return@collect
                    }
                    storedBooks = projection.books
                    mutableState.value =
                        state.value.copy(
                            groupId = projection.groupId,
                            groups = projection.groups,
                            books = projection.entries,
                            settings = projection.settings,
                            loading = false,
                            error = null,
                        )
                }
        }
        headerJob = viewModelScope.launch {
            repository
                .preferences()
                .flatMapLatest { prefs -> repository.header(prefs).map { prefs to it } }
                .catch { io.legado.app.constant.AppLog.put("书架头部刷新出错", it) }
                .collect { (prefs, header) ->
                    mutableState.value =
                        state.value.copy(
                            header =
                                BookshelfHeaderModel(
                                    (header.bookCount to header.readingCount).takeIf {
                                        prefs.stats
                                    },
                                    header.recent
                                        ?.takeIf { prefs.recent }
                                        ?.toBookshelfCardModel(false, 1, false),
                                )
                        )
                }
        }
        ticker = viewModelScope.launch {
            while (true) {
                delay(30000)
                if (state.value.settings.showLatestUpdate && state.value.settings.layout < 2)
                    refreshTimes()
            }
        }
    }

    fun stop() {
        collection?.cancel()
        collection = null
        headerJob?.cancel()
        headerJob = null
        ticker?.cancel()
        ticker = null
    }

    fun retry() {
        stop()
        start()
    }

    fun refresh() {
        if (collection != null) retry()
    }

    fun openGroup(id: Long) {
        if (state.value.groups.any { it.id == id }) navigate(id)
    }

    private fun navigate(id: Long) {
        if (id == selected.value) return
        saved["folder.group"] = id
        selected.value = id
        storedBooks = emptyList()
        saved["folder.scrollRequest"] = 0
        mutableState.value =
            state.value.copy(
                groupId = id,
                books = emptyList(),
                loading = true,
                error = null,
                scrollRequest = 0,
            )
    }

    fun back(): Boolean {
        if (selected.value == BookGroup.IdRoot) return false
        navigate(BookGroup.IdRoot)
        return true
    }

    fun swipe(offset: Int) {
        if (offset in listOf(-1, 1) && !state.value.root && state.value.index >= 0)
            state.value.groups.getOrNull(state.value.index + offset)?.let { navigate(it.id) }
    }

    fun setOnlyRead(value: Boolean) {
        if (!state.value.root)
            mutableState.value =
                state.value.copy(
                    groups =
                        state.value.groups.map {
                            if (it.id == state.value.groupId) it.copy(onlyRead = value) else it
                        }
                )
    }

    fun getBooks() = storedBooks.map { it.copy() }

    fun getBook(key: String) = storedBooks.find { it.bookUrl == key }?.copy()

    fun setUpdating(key: String, running: Boolean) {
        updates.value = if (running) updates.value + key else updates.value - key
    }

    fun replaceUpdating(keys: Set<String>) {
        updates.value = keys.toSet()
    }

    fun refreshTimes() {
        revision.value++
    }

    fun gotoTop() {
        val token = ++nextScroll
        saved["folder.nextScroll"] = token
        saved["folder.scrollRequest"] = token
        mutableState.value = state.value.copy(scrollRequest = token)
    }

    fun scrolled(group: Long, token: Int) {
        if (state.value.groupId != group || state.value.scrollRequest != token) return
        saved["folder.scrollRequest"] = 0
        mutableState.value = state.value.copy(scrollRequest = 0)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
