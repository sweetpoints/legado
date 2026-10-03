package io.legado.app.ui.main.bookshelf.style1.books

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookshelfPageRepository
import io.legado.app.data.repository.BookshelfPageSettings
import io.legado.app.data.repository.sortBookshelfPageBooks
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookshelfPageParameters(
    val position: Int = 0,
    val groupId: Long = -1,
    val sort: Int = 0,
    val enableRefresh: Boolean = true,
    val onlyUpdateRead: Boolean = false,
)

data class BookshelfPageEntry(
    val key: String,
    val name: String,
    val author: String,
    val currentChapter: String,
    val latestChapter: String,
    val cover: String?,
    val sourceOrigin: String?,
    val unread: Int,
    val highlightUnread: Boolean,
    val updating: Boolean,
    val progress: Float?,
    val latestUpdate: String?,
)

data class BookshelfPageUiState(
    val parameters: BookshelfPageParameters,
    val settings: BookshelfPageSettings = BookshelfPageSettings(),
    val entries: List<BookshelfPageEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val scrollRequest: Int = 0,
) {
    val canRefresh: Boolean
        get() = parameters.enableRefresh && entries.isNotEmpty()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookshelfPageViewModel(
    private val repository: BookshelfPageRepository,
    private val savedState: SavedStateHandle,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val formatUpdateTime: (Long) -> String = { it.toTimeAgo() },
) : ViewModel() {
    private val parameters =
        MutableStateFlow(
            BookshelfPageParameters(
                savedState["position"] ?: 0,
                savedState["groupId"] ?: -1L,
                savedState["bookSort"] ?: 0,
                savedState["enableRefresh"] ?: true,
                savedState["onlyUpdateRead"] ?: false,
            )
        )
    private val updates = MutableStateFlow<Set<String>>(emptySet())
    private val timeRevision = MutableStateFlow(0)
    private val mutableState =
        MutableStateFlow(
            BookshelfPageUiState(
                parameters.value,
                scrollRequest = savedState["booksPage.scrollRequest"] ?: 0,
            )
        )
    val state = mutableState.asStateFlow()
    private var nextScrollRequest =
        maxOf(savedState.get<Int>("booksPage.nextScrollRequest") ?: 0, state.value.scrollRequest)
    private var books: List<Book> = emptyList()
    private var collection: Job? = null
    private var ticker: Job? = null

    fun start() {
        if (collection != null) return
        collection = viewModelScope.launch {
            parameters
                .flatMapLatest { params ->
                    combine(
                        repository.books(params.groupId),
                        repository.settings(),
                        updates,
                        timeRevision,
                    ) { books, settings, updating, _ ->
                        withContext(worker) {
                            val sorted =
                                sortBookshelfPageBooks(books, params.sort).map { it.copy() }
                            val entries = sorted.map { book ->
                                BookshelfPageEntry(
                                    book.bookUrl,
                                    book.name,
                                    book.author,
                                    book.durChapterTitle.orEmpty(),
                                    book.latestChapterTitle.orEmpty(),
                                    book.getDisplayCover(),
                                    book.getCoverSourceOrigin(),
                                    if (settings.showUnread) book.getUnreadChapterNum() else 0,
                                    book.lastCheckCount > 0,
                                    !book.isLocal && book.bookUrl in updating,
                                    book.readProgress().takeIf {
                                        settings.readProgressMode !=
                                            BookshelfReadProgressMode.HIDDEN
                                    },
                                    if (
                                        settings.showLatestUpdate &&
                                            settings.layout < 2 &&
                                            !book.isLocal
                                    )
                                        formatUpdateTime(book.latestChapterTime)
                                    else null,
                                )
                            }
                            Triple(params, sorted, settings to entries)
                        }
                    }
                }
                .catch { error ->
                    mutableState.value =
                        state.value.copy(
                            loading = false,
                            error = error.localizedMessage ?: error.toString(),
                        )
                }
                .collect { (params, sorted, display) ->
                    if (params != parameters.value) return@collect
                    books = sorted
                    mutableState.value =
                        state.value.copy(
                            parameters = params,
                            settings = display.first,
                            entries = display.second,
                            loading = false,
                            error = null,
                        )
                }
        }
        ticker = viewModelScope.launch {
            while (true) {
                delay(30000)
                if (state.value.settings.showLatestUpdate && state.value.settings.layout < 2)
                    refreshTimeLabels()
            }
        }
    }

    fun stop() {
        collection?.cancel()
        collection = null
        ticker?.cancel()
        ticker = null
    }

    fun retry() {
        stop()
        start()
    }

    fun upSort(sort: Int) = configure(parameters.value.copy(sort = sort))

    fun setEnableRefresh(enabled: Boolean) =
        configure(parameters.value.copy(enableRefresh = enabled))

    fun setOnlyUpdateRead(enabled: Boolean) =
        configure(parameters.value.copy(onlyUpdateRead = enabled))

    fun configure(next: BookshelfPageParameters) {
        if (next == parameters.value) return
        savedState["position"] = next.position
        savedState["groupId"] = next.groupId
        savedState["bookSort"] = next.sort
        savedState["enableRefresh"] = next.enableRefresh
        savedState["onlyUpdateRead"] = next.onlyUpdateRead
        val changedGroup = next.groupId != parameters.value.groupId
        if (changedGroup) books = emptyList()
        parameters.value = next
        mutableState.value =
            if (changedGroup)
                state.value.copy(
                    parameters = next,
                    entries = emptyList(),
                    loading = true,
                    error = null,
                )
            else state.value.copy(parameters = next)
    }

    fun setUpdating(key: String, running: Boolean) {
        updates.value = if (running) updates.value + key else updates.value - key
    }

    fun replaceUpdating(keys: Set<String>) {
        updates.value = keys.toSet()
    }

    fun refreshTimeLabels() {
        timeRevision.value++
    }

    fun getBooks(): List<Book> = books.map { it.copy() }

    fun getBook(key: String): Book? = books.find { it.bookUrl == key }?.copy()

    fun gotoTop() {
        val request = ++nextScrollRequest
        savedState["booksPage.nextScrollRequest"] = request
        savedState["booksPage.scrollRequest"] = request
        mutableState.value = state.value.copy(scrollRequest = request)
    }

    fun scrolled(request: Int) {
        if (state.value.scrollRequest != request) return
        savedState["booksPage.scrollRequest"] = 0
        mutableState.value = state.value.copy(scrollRequest = 0)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
