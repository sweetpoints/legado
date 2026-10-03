package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import io.legado.app.help.book.*
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.book.toc.*
import io.legado.app.model.localBook.PdfFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Screen rows never retain the mutable Room entities or the legacy adapter. */
data class TocChapterRow(
    val key: String,
    val index: Int,
    val readingIndex: Int?,
    val title: String,
    val depth: Int = 0,
    val volume: Boolean = false,
    val collapsed: Boolean = false,
    val canToggle: Boolean = false,
    val chapterCount: Int = 0,
    val matchedCount: Int? = null,
    val matchedSelf: Boolean = false,
    val current: Boolean = false,
    val tag: String? = null,
    val words: String? = null,
    val locked: Boolean = false,
    val cached: Boolean = true,
    val pdfPage: Int? = null,
    val parentVolumeIndex: Int? = null,
)

data class TocChapterOpen(
    val key: String,
    val longPress: Boolean,
    val owner: String,
    val nonce: String = UUID.randomUUID().toString(),
)

data class TocChapterDelivery(
    val navigation: TocChapterNavigation? = null,
    val title: String? = null,
)

data class TocChapterState(
    val loaded: Boolean = false,
    val rows: List<TocChapterRow> = emptyList(),
    val currentInfo: String = "",
    val query: String? = null,
    val pdf: Boolean = false,
    val error: String? = null,
    val scrollRequest: Long = 0,
    val scrollTarget: Int = 0,
    val open: TocChapterOpen? = null,
)

class TocChapterViewModel(
    private val repository: TocChapterRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            TocChapterState(
                open =
                    saved.get<String>("tocChapter.open")?.let {
                        GSON.fromJsonObject<TocChapterOpen>(it).getOrNull()
                    }
            )
        )
    val state: StateFlow<TocChapterState> = mutable
    private val session =
        saved.get<String>("tocChapter.session")
            ?: UUID.randomUUID().toString().also { saved["tocChapter.session"] = it }
    private var parameters: TocChapterParameters? = null
    private var generation = 0L
    private var revision = saved.get<Long>("tocChapter.revision") ?: 0L
    private var nextScroll = saved.get<Long>("tocChapter.nextScroll") ?: 0L
    private var loading: Job? = null
    private var searching: Job? = null
    private var titlesJob: Job? = null
    private var cacheJob: Job? = null
    private var restoring: Job? = null
    private val writes = Mutex()
    private var snapshot: TocChapterSnapshot? = null
    private var toc = TocListState()
    private var pdf: PdfOutlineListState? = null
    private var items: List<TocListItem> = emptyList()
    private var titles = emptyMap<String, String>()
    private var cache = TocChapterCache()
    private var cacheReady = false
    private val pendingAudio = linkedMapOf<AudioCacheKey, Pair<String?, Boolean>>()
    private val pendingFiles = linkedSetOf<String>()

    init {
        restoring = viewModelScope.launch {
            try {
                val value = repository.checkpoint(session)
                currentCoroutineContext().ensureActive()
                if (generation == 0L && value != null) bind(value.parameters)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failed(error.localizedMessage ?: "Error")
            }
        }
    }

    fun bind(value: TocChapterParameters, resetCollapse: Boolean = false) {
        loading?.cancel()
        searching?.cancel()
        titlesJob?.cancel()
        cacheJob?.cancel()
        val book = value.book.copy(readConfig = value.book.readConfig?.copy())
        val current = ++generation
        parameters = value.copy(book = book, search = value.search?.takeIf { it.isNotBlank() })
        if (state.value.open?.owner?.let { it != owner(book.bookUrl) } == true)
            delivered(state.value.open!!.nonce)
        snapshot = null
        toc = TocListState()
        pdf = null
        items = emptyList()
        titles = emptyMap()
        cache = TocChapterCache()
        cacheReady = book.isPdf
        pendingAudio.clear()
        pendingFiles.clear()
        mutable.value =
            state.value.copy(
                loaded = false,
                rows = emptyList(),
                error = null,
                query = parameters!!.search,
                pdf = false,
                currentInfo =
                    "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})",
                scrollRequest = 0,
            )
        loading = viewModelScope.launch {
            try {
                val checkpoint = repository.checkpoint(session)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                revision = maxOf(revision, checkpoint?.revision ?: 0L)
                val loaded = repository.load(parameters!!)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                snapshot = loaded
                if (loaded.pdf.any { it.pageIndex != null }) {
                    pdf = PdfOutlineListState(loaded.pdf, book.getTocExpanded())
                } else {
                    toc.setFullChapters(
                        loaded.chapters,
                        book.getReverseToc(),
                        resetCollapse = true,
                        defaultExpanded = book.getTocExpanded(),
                        currentChapterIndex = book.durChapterIndex,
                        epubToc = loaded.epub,
                        reverseDisplay = !book.isPdf && !book.isEpub && book.getReverseTocDisplay(),
                    )
                }
                if (
                    !resetCollapse &&
                        checkpoint?.parameters?.book?.bookUrl == book.bookUrl &&
                        checkpoint.parameters.book.getReverseToc() == book.getReverseToc() &&
                        checkpoint.parameters.book.getReverseTocDisplay() ==
                            book.getReverseTocDisplay() &&
                        checkpoint.parameters.book.getTocExpanded() == book.getTocExpanded()
                ) {
                    toc.restoreCollapsed(checkpoint.collapsed)
                    pdf?.restoreCollapsed(checkpoint.pdfCollapsed)
                }
                show(current, debounce = false)
                persist()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error.localizedMessage ?: "Error")
            }
        }
        cacheJob = viewModelScope.launch {
            try {
                val loaded = repository.cache(book)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                val keys = loaded.audio.toMutableSet()
                pendingAudio.forEach { (key, event) ->
                    if (event.first == loaded.tree) {
                        if (event.second) keys.add(key) else keys.remove(key)
                    }
                }
                cache = loaded.copy(audio = keys.toSet(), files = loaded.files + pendingFiles)
                pendingAudio.clear()
                pendingFiles.clear()
                cacheReady = true
                project()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error.localizedMessage ?: "Error")
            }
        }
    }

    fun update(
        value: TocChapterParameters,
        resetCollapse: Boolean = false,
        replaceAll: Boolean = false,
    ) {
        val old = parameters?.book
        val book = value.book
        if (
            old == null ||
                old.bookUrl != book.bookUrl ||
                resetCollapse ||
                replaceAll ||
                old.getReverseToc() != book.getReverseToc() ||
                old.getReverseTocDisplay() != book.getReverseTocDisplay() ||
                old.getTocExpanded() != book.getTocExpanded()
        ) {
            bind(value, resetCollapse)
        } else {
            parameters =
                parameters?.copy(
                    book = book.copy(readConfig = book.readConfig?.copy()),
                    countWords = value.countWords,
                )
            search(value.search, false, replaceAll)
        }
    }

    fun search(query: String?, resetCollapse: Boolean = false, replaceAll: Boolean = false) {
        val old = parameters ?: return
        parameters = old.copy(search = query?.takeIf { it.isNotBlank() })
        mutable.value = state.value.copy(query = parameters!!.search)
        if (resetCollapse) {
            snapshot?.let {
                toc.setFullChapters(
                    it.chapters,
                    old.book.getReverseToc(),
                    resetCollapse = true,
                    defaultExpanded = old.book.getTocExpanded(),
                    currentChapterIndex = old.book.durChapterIndex,
                    epubToc = it.epub,
                    reverseDisplay =
                        !old.book.isPdf && !old.book.isEpub && old.book.getReverseTocDisplay(),
                )
            }
            pdf?.setExpanded(old.book.getTocExpanded())
        }
        if (replaceAll) clearTitles()
        persist()
        if (snapshot != null) show(generation, debounce = true)
    }

    private fun show(
        current: Long,
        debounce: Boolean,
        anchor: String? = null,
        chapter: Int? = null,
    ) {
        searching?.cancel()
        searching = viewModelScope.launch {
            try {
                val params = parameters ?: return@launch
                if (debounce && !params.search.isNullOrBlank() && pdf == null) delay(150)
                val match =
                    if (pdf == null && params.search != null) {
                        if (!snapshot?.epub.isNullOrEmpty()) toc.searchIndexes(params.search)
                        else repository.search(params, params.search)
                    } else emptyList()
                currentCoroutineContext().ensureActive()
                if (current != generation || parameters?.search != params.search) return@launch
                items =
                    if (pdf != null) emptyList()
                    else if (params.search == null) toc.showNormal(params.book.durChapterIndex)
                    else toc.showSearch(match, params.book.durChapterIndex)
                project(loaded = true)
                val target =
                    when {
                        anchor != null ->
                            state.value.rows.indexOfFirst { it.key == anchor }.coerceAtLeast(0)
                        chapter != null -> currentTarget(chapter)
                        pdf != null ->
                            null // Outline originally stays at its current list position until
                        // explicit locate.
                        params.search != null -> 0
                        else ->
                            toc.findFallbackVisiblePositionForChapterIndex(
                                    params.book.durChapterIndex
                                )
                                .coerceAtLeast(0)
                    }
                if (target != null) scroll(target)
                updateTitles(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error.localizedMessage ?: "Error")
            }
        }
    }

    private fun project(loaded: Boolean = state.value.loaded) {
        val params = parameters ?: return
        val book = params.book
        val rows =
            pdf?.items(params.search, book.getReverseToc())?.map { row ->
                TocChapterRow(
                    "pdf:${row.node.id}",
                    row.node.id,
                    null,
                    row.node.title,
                    minOf(row.node.depth, 8),
                    volume = row.hasChildren,
                    collapsed = row.collapsed,
                    canToggle = row.canToggle,
                    pdfPage = row.node.pageIndex,
                )
            }
                ?: items.map { item ->
                    val chapter = item.chapter
                    val volume = item as? TocListItem.Volume
                    TocChapterRow(
                        item.key,
                        chapter.index,
                        item.readingChapter?.index,
                        titles[item.key] ?: chapter.title,
                        item.depth,
                        volume = volume != null,
                        collapsed = volume?.collapsed == true,
                        canToggle = volume?.canToggle == true,
                        chapterCount = volume?.chapterCount ?: 0,
                        matchedCount = volume?.matchedCount,
                        matchedSelf = volume?.matchedSelf == true,
                        current =
                            item.readingChapter?.index == book.durChapterIndex ||
                                volume?.containsCurrentChapter == true,
                        parentVolumeIndex = (item as? TocListItem.Chapter)?.parentVolumeIndex,
                        tag = chapter.tag,
                        words = chapter.wordCount.takeIf { params.countWords && volume == null },
                        locked = chapter.isVip && !chapter.isPay && volume == null,
                        cached =
                            book.isLocal ||
                                volume != null ||
                                if (book.isAudio)
                                    !cacheReady || AudioCacheKey.from(chapter) in cache.audio
                                else chapter.getFileName() in cache.files,
                    )
                }
        mutable.value = state.value.copy(rows = rows, loaded = loaded, pdf = pdf != null)
    }

    private fun updateTitles(current: Long) {
        titlesJob?.cancel()
        val book = parameters?.book ?: return
        val currentItems = items.toList()
        titlesJob = viewModelScope.launch {
            try {
                repository.titles(book, currentItems).collect { (key, title) ->
                    currentCoroutineContext().ensureActive()
                    if (current == generation && items.any { it.key == key }) {
                        titles = titles + (key to title)
                        project()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error.localizedMessage ?: "Error")
            }
        }
    }

    fun clearTitles() {
        titlesJob?.cancel()
        titles = emptyMap()
        project()
        updateTitles(generation)
    }

    fun countWords(value: Boolean) {
        parameters = parameters?.copy(countWords = value)
        project()
        persist()
    }

    fun toggle(key: String, firstVisibleKey: String? = null) {
        if (!state.value.loaded || state.value.open != null || parameters?.search != null) return
        val row = state.value.rows.find { it.key == key && it.canToggle } ?: return
        if (pdf != null) {
            pdf!!.toggle(row.index)
            project()
            persist()
            return
        }
        val first = items.find { it.key == firstVisibleKey }
        val anchor =
            if (
                first != null &&
                    toc.isDescendantOf(first.chapter.index, row.index) &&
                    !toc.isVolumeCollapsed(row.index)
            )
                key
            else firstVisibleKey
        if (toc.toggleVolume(row.index)) {
            show(generation, false, anchor = anchor)
            persist()
        }
    }

    fun locate() {
        val book = parameters?.book ?: return
        if (pdf != null) {
            pdf!!.setExpanded(true)
            project()
            scroll(currentTarget(book.durChapterIndex))
            persist()
            return
        }
        if (parameters?.search == null) toc.expandVolumeContainingChapter(book.durChapterIndex)
        show(generation, false, chapter = book.durChapterIndex)
        persist()
    }

    private fun currentTarget(index: Int): Int =
        if (pdf != null)
            state.value.rows
                .withIndex()
                .filter { it.value.pdfPage != null }
                .minByOrNull { kotlin.math.abs(it.value.pdfPage!! - index * PdfFile.PAGE_SIZE) }
                ?.index ?: 0
        else toc.findFallbackVisiblePositionForChapterIndex(index).coerceAtLeast(0)

    fun top() = scroll(0)

    fun bottom() {
        if (state.value.rows.isNotEmpty()) scroll(state.value.rows.lastIndex)
    }

    private fun scroll(index: Int) {
        if (!state.value.loaded) return
        val token = ++nextScroll
        saved["tocChapter.nextScroll"] = token
        mutable.value = state.value.copy(scrollRequest = token, scrollTarget = index)
    }

    fun scrolled(token: Long) {
        if (token == state.value.scrollRequest) mutable.value = state.value.copy(scrollRequest = 0)
    }

    fun contentSaved(bookUrl: String, chapter: BookChapter) {
        if (parameters?.book?.bookUrl != bookUrl || parameters?.book?.isAudio == true) return
        if (!cacheReady) pendingFiles.add(chapter.getFileName())
        else {
            cache = cache.copy(files = cache.files + chapter.getFileName())
            project()
        }
    }

    fun audioChanged(event: AudioCacheStateChanged, currentTree: String?) {
        val book = parameters?.book ?: return
        if (!book.isAudio || event.bookUrl != book.bookUrl || event.treeUri != currentTree) return
        if (!cacheReady) pendingAudio[event.key] = event.treeUri to event.cached
        else {
            cache =
                cache.copy(
                    audio = if (event.cached) cache.audio + event.key else cache.audio - event.key
                )
            project()
        }
    }

    fun request(key: String, longPress: Boolean = false, firstVisibleKey: String? = null) {
        if (!state.value.loaded || state.value.open != null) return
        val row = state.value.rows.find { it.key == key } ?: return
        if (!longPress && row.readingIndex == null && row.pdfPage == null) {
            if (row.canToggle) toggle(key, firstVisibleKey)
            return
        }
        val value = TocChapterOpen(key, longPress, owner(parameters!!.book.bookUrl))
        saved["tocChapter.open"] = GSON.toJson(value)
        mutable.value = state.value.copy(open = value)
    }

    suspend fun resolve(ticket: TocChapterOpen): TocChapterDelivery? {
        val params = parameters ?: return null
        if (state.value.open?.nonce != ticket.nonce || ticket.owner != owner(params.book.bookUrl))
            return null
        val row = state.value.rows.find { it.key == ticket.key } ?: return null
        val result =
            if (ticket.longPress) {
                val item = items.find { it.key == ticket.key }
                TocChapterDelivery(
                    title = if (item != null) repository.title(params.book, item) else row.title
                )
            } else if (row.pdfPage != null) {
                val index = row.pdfPage / PdfFile.PAGE_SIZE
                TocChapterDelivery(
                        navigation =
                            TocChapterNavigation(
                                index,
                                index != params.book.durChapterIndex,
                                pdfPage = row.pdfPage,
                            )
                    )
                    .takeIf { index in 0 until params.book.totalChapterNum }
            } else {
                val chapter = items.find { it.key == ticket.key }?.readingChapter ?: return null
                repository.resolve(params.book, chapter.url)?.let {
                    TocChapterDelivery(navigation = it)
                }
            }
        currentCoroutineContext().ensureActive()
        return result.takeIf {
            state.value.open?.nonce == ticket.nonce &&
                parameters?.book?.bookUrl == params.book.bookUrl
        }
    }

    fun delivered(nonce: String): TocChapterOpen? {
        val value = state.value.open?.takeIf { it.nonce == nonce } ?: return null
        saved.remove<String>("tocChapter.open")
        mutable.value = state.value.copy(open = null)
        return value
    }

    private fun persist() {
        val params = parameters ?: return
        val current = generation
        val value =
            TocChapterCheckpoint(
                params.copy(book = params.book.copy(readConfig = params.book.readConfig?.copy())),
                0,
                toc.collapsedIndexes(),
                pdf?.collapsedIndexes().orEmpty(),
            )
        viewModelScope.launch {
            try {
                writes.withLock {
                    val disk = repository.checkpoint(session)
                    currentCoroutineContext().ensureActive()
                    if (current != generation) return@withLock
                    revision = maxOf(revision, disk?.revision ?: 0L) + 1
                    saved["tocChapter.revision"] = revision
                    val pending =
                        if (
                            snapshot == null &&
                                disk?.parameters?.book?.bookUrl == params.book.bookUrl
                        )
                            value.copy(collapsed = disk.collapsed, pdfCollapsed = disk.pdfCollapsed)
                        else value
                    repository.checkpoint(session, pending.copy(revision = revision))
                    currentCoroutineContext().ensureActive()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) failed(error.localizedMessage ?: "Error")
            }
        }
    }

    fun retry() {
        parameters?.let { bind(it) }
    }

    fun failed(message: String) {
        mutable.value = state.value.copy(error = message)
    }

    fun unbind() {
        generation++
        restoring?.cancel()
        loading?.cancel()
        searching?.cancel()
        titlesJob?.cancel()
        cacheJob?.cancel()
    }

    private fun owner(url: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    fun stop() {
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            runCatching { repository.release(session) }
        }
        super.onCleared()
    }
}
