package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class BookSourceState(
    val loading: Boolean = true,
    val searching: Boolean = false,
    val busy: Boolean = false,
    val changing: Boolean = false,
    val changeCancelable: Boolean = true,
    val relativeWarning: Boolean = false,
    val error: String? = null,
    val persistError: Boolean = false,
    val request: ChapterSourceSearchRequest? = null,
    val rows: List<ChapterSourceSearchRow> = emptyList(),
    val originName: String = "",
    val mismatchId: String? = null,
    val pendingReceipt: String? = null,
    val finished: Boolean = false,
    val emptyGroup: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val sourceName: String = "",
    val searchOpen: Boolean = false,
)

internal class BookSourceViewModel(
    private val searches: BookSourceSearchRepository,
    private val changes: BookSourceChangeRepository,
    private val settings: ChapterSourceSettingsRepository,
    private val saved: SavedStateHandle,
    private val seed: suspend () -> BookSourceChangeSession,
) : ViewModel() {
    val session =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable =
        MutableStateFlow(
            BookSourceState(
                searchOpen = saved.get<Boolean>("searchOpen") == true,
                relativeWarning = saved.get<Boolean>("relativeWarning") == true,
            )
        )
    val state = mutable.asStateFlow()
    private var current: BookSourceChangeSession? = null
    private var oldType = 0
    private var originName = ""
    private var revision = 0L
    private var stopped = false
    private var searchGeneration = 0L
    private var readGeneration = 0L
    private var changeGeneration = 0L
    private var pendingMeasurement = false
    private var relativeWarned = saved.get<Boolean>("relativeWarned") == true
    private var load: Job? = null
    private var search: Job? = null
    private var read: Job? = null
    private var operation: Job? = null
    private var changeTask: Job? = null
    private val writes = MutableStateFlow<BookSourceChangeSession?>(null)
    private val writer = viewModelScope.launch {
        writes.filterNotNull().collect { value ->
            try {
                changes.write(session, value)
                currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision)
                    mutable.value = state.value.copy(persistError = false)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision)
                    mutable.value =
                        state.value.copy(
                            persistError = true,
                            error = error.localizedMessage.orEmpty(),
                        )
            }
        }
    }

    init {
        initialize()
    }

    private fun nextRevision(): Long {
        revision = maxOf(revision + 1, System.nanoTime(), (current?.revision ?: 0) + 1)
        return revision
    }

    private fun initialize() {
        if (stopped) return
        load?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        load = viewModelScope.launch {
            try {
                var snapshot = changes.read(session) ?: seed().also { changes.write(session, it) }
                revision = maxOf(revision, snapshot.revision)
                snapshot = snapshot.copy(request = settings.load().apply(snapshot.request))
                snapshot.pendingReceipt?.let { key ->
                    val receipt = changes.receipt(session, key)
                    if (receipt.consumed || saved.get<String>("consumedReceipt") == key) {
                        changes.consume(session, key)
                        snapshot =
                            snapshot.copy(
                                pendingReceipt = null,
                                finished = snapshot.finished || receipt.deleteAfter == null,
                            )
                    }
                }
                val original =
                    withContext(Dispatchers.Default) {
                        snapshot.request.originalBookJson?.let {
                            GSON.fromJson(it, Book::class.java)
                        }
                    }
                val projected = project(snapshot.request, snapshot.rows)
                currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                oldType = original?.type ?: 0
                originName = original?.originName.orEmpty()
                snapshot = snapshot.copy(revision = nextRevision())
                changes.write(session, snapshot)
                currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                current = snapshot
                render()
                mutable.value = state.value.copy(loading = false, rows = projected)
                if (
                    snapshot.pendingReceipt != null ||
                        snapshot.finished ||
                        snapshot.mismatchId != null
                )
                    return@launch
                refreshCached(true)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutable.value =
                        state.value.copy(loading = false, error = error.localizedMessage.orEmpty())
            }
        }
    }

    private fun render() {
        current?.let {
            mutable.value =
                state.value.copy(
                    request = it.request.copy(originalBookJson = null),
                    originName = originName,
                    mismatchId = it.mismatchId,
                    pendingReceipt = it.pendingReceipt,
                    finished = it.finished,
                )
        }
    }

    private fun change(snapshot: BookSourceChangeSession) {
        if (stopped) return
        current = snapshot.copy(revision = nextRevision())
        render()
        writes.value = current
    }

    private suspend fun publishReceipt(key: String) {
        while (!stopped) {
            val snapshot = current ?: return
            val next =
                snapshot.copy(pendingReceipt = key, mismatchId = null, revision = nextRevision())
            changes.write(session, next)
            currentCoroutineContext().ensureActive()
            if (current?.revision == snapshot.revision) {
                current = next
                render()
                return
            }
        }
    }

    private suspend fun project(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
    ) = searches.project(request, rows)

    private suspend fun projectLatest(
        rows: List<ChapterSourceSearchRow>,
        token: Long? = null,
        filterTitles: Boolean = false,
    ): List<ChapterSourceSearchRow>? {
        while (!stopped) {
            if (token != null && token != searchGeneration) return null
            val request = current?.request ?: return null
            val result = project(request, rows)
            currentCoroutineContext().ensureActive()
            if (stopped || token != null && token != searchGeneration) return null
            if (current?.request == request)
                return if (filterTitles)
                    result.filter { request.query.isEmpty() || it.name.contains(request.query) }
                else result
        }
        return null
    }

    fun retry() {
        if (stopped) return
        if (current == null) initialize()
        else if (state.value.persistError) change(requireNotNull(current))
        else {
            mutable.value = state.value.copy(error = null)
            refresh()
        }
    }

    fun filterOpen() {
        val open = !state.value.searchOpen
        saved["searchOpen"] = open
        mutable.value = state.value.copy(searchOpen = open)
    }

    fun query(value: String) {
        val snapshot = current ?: return
        change(snapshot.copy(request = snapshot.request.copy(query = value.trim())))
        launchRead { refreshCached(false) }
    }

    fun startOrStop() {
        if (state.value.searching) stopSearch() else startSearch()
    }

    fun stopSearch() {
        searchGeneration++
        search?.cancel()
        search = null
        pendingMeasurement = false
        if (!stopped) mutable.value = state.value.copy(searching = false)
    }

    fun startSearch(origin: String? = null) {
        val snapshot = current ?: return
        if (
            stopped ||
                state.value.persistError ||
                state.value.changing ||
                snapshot.pendingReceipt != null
        )
            return
        stopSearch()
        resetWarning()
        val token = ++searchGeneration
        search = viewModelScope.launch {
            mutable.value = state.value.copy(searching = true, emptyGroup = false, error = null)
            try {
                searches.search(snapshot.request, snapshot.rows, origin).collect { update ->
                    acceptSearch(update, token)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped && token == searchGeneration && currentCoroutineContext().isActive) {
                    mutable.value = state.value.copy(searching = false)
                    if (pendingMeasurement) {
                        pendingMeasurement = false
                        measure(true)
                    }
                }
            }
        }
    }

    private suspend fun acceptSearch(update: ChapterSourceSearchUpdate, token: Long) {
        currentCoroutineContext().ensureActive()
        if (stopped || token != searchGeneration) return
        val snapshot = current ?: return
        if (snapshot.request.group != update.effectiveGroup && update.effectiveGroup.isEmpty()) {
            val prefs = settings.group("")
            currentCoroutineContext().ensureActive()
            if (stopped || token != searchGeneration) return
            change(
                requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request))
            )
        }
        val visible = projectLatest(update.allRows, token, filterTitles = true) ?: return
        change(requireNotNull(current).copy(rows = update.allRows))
        mutable.value =
            state.value.copy(
                rows = visible,
                searching = update.running,
                completed = update.completed,
                total = update.total,
                sourceName = update.sourceName,
                emptyGroup =
                    !update.running &&
                        visible.isEmpty() &&
                        requireNotNull(current).request.group.isNotEmpty(),
            )
        val request = requireNotNull(current).request
        if (
            !update.running &&
                request.loadWordCount &&
                request.filterMode == 2 &&
                update.referenceWordCount == null &&
                !relativeWarned
        ) {
            relativeWarned = true
            saved["relativeWarned"] = true
            saved["relativeWarning"] = true
            mutable.value = state.value.copy(relativeWarning = true)
        }
    }

    private fun resetWarning() {
        relativeWarned = false
        saved["relativeWarned"] = false
        consumeWarning()
    }

    fun consumeWarning() {
        saved["relativeWarning"] = false
        mutable.value = state.value.copy(relativeWarning = false)
    }

    private fun measure(missingOnly: Boolean) {
        val snapshot = current ?: return
        if (stopped || missingOnly && !snapshot.request.loadWordCount) return
        if (missingOnly && state.value.searching) {
            pendingMeasurement = true
            return
        }
        stopSearch()
        if (!missingOnly) resetWarning()
        val token = ++searchGeneration
        search = viewModelScope.launch {
            try {
                searches.measure(snapshot.request, snapshot.rows, missingOnly).collect {
                    acceptSearch(it, token)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped && token == searchGeneration && currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(searching = false)
            }
        }
    }

    fun refresh() = launchRead { refreshCached(true) }

    fun refreshMeasurements() {
        if (current != null && !state.value.changing) measure(false)
    }

    private suspend fun refreshCached(searchWhenEmpty: Boolean) {
        val snapshot = current ?: return
        val cached = searches.cached(snapshot.request)
        currentCoroutineContext().ensureActive()
        if (stopped || current?.request != snapshot.request) return
        change(requireNotNull(current).copy(rows = cached.allRows))
        mutable.value = state.value.copy(rows = cached.rows)
        if (searchWhenEmpty && cached.allRows.isEmpty()) startSearch()
        else if (snapshot.request.loadWordCount) measure(true)
    }

    fun toggle(option: ChapterSourceOption) = launchOperation {
        val prefs = settings.toggle(option)
        currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
        when (option) {
            ChapterSourceOption.Author -> refreshCached(false)
            ChapterSourceOption.WordCount,
            ChapterSourceOption.ResponseTime -> updateProjection(true)
            else -> Unit
        }
    }

    fun group(value: String) = launchOperation {
        stopSearch()
        val prefs = settings.group(value)
        currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
        mutable.value = state.value.copy(emptyGroup = false)
        refreshCached(true)
    }

    fun dismissEmptyGroup() {
        mutable.value = state.value.copy(emptyGroup = false)
    }

    fun searchAll() = launchOperation {
        val prefs = settings.group("")
        currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
        mutable.value = state.value.copy(emptyGroup = false)
        startSearch()
    }

    fun optionsChanged(reload: Boolean) = launchRead {
        val prefs = settings.load()
        currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
        updateProjection(reload)
    }

    private suspend fun updateProjection(reload: Boolean) {
        projectLatest(requireNotNull(current).rows)?.let {
            mutable.value = state.value.copy(rows = it)
        }
        if (reload) measure(true)
    }

    fun choose(id: String) {
        val snapshot = current ?: return
        val row = snapshot.rows.find { it.id == id } ?: return
        if (
            stopped ||
                id == snapshot.request.currentBookUrl ||
                state.value.busy ||
                state.value.changing ||
                state.value.persistError ||
                snapshot.pendingReceipt != null
        )
            return
        if ((row.type and BookType.allBookTypeLocal) != (oldType and BookType.allBookTypeLocal))
            change(snapshot.copy(mismatchId = id))
        else prepareChange(row, null)
    }

    fun dismissMismatch() {
        current?.let { change(it.copy(mismatchId = null)) }
    }

    fun confirmMismatch() {
        val snapshot = current ?: return
        val row = snapshot.rows.find { it.id == snapshot.mismatchId } ?: return
        change(snapshot.copy(mismatchId = null))
        prepareChange(row, null)
    }

    fun deleteSource(id: String) {
        val snapshot = current ?: return
        val row = snapshot.rows.find { it.id == id } ?: return
        if (id == snapshot.request.currentBookUrl) {
            if (state.value.changing || state.value.busy || snapshot.pendingReceipt != null) return
            val candidates =
                state.value.rows.filter { it.origin != row.origin && it.type == oldType }
            prepareChange(null, row, candidates)
        } else
            launchOperation {
                changes.delete(row)
                currentCoroutineContext().ensureActive()
                remove(row)
            }
    }

    private fun prepareChange(
        row: ChapterSourceSearchRow?,
        deleteAfter: ChapterSourceSearchRow?,
        candidates: List<ChapterSourceSearchRow> = emptyList(),
    ) {
        if (stopped || state.value.persistError) return
        cancelChange()
        val token = ++changeGeneration
        mutable.value =
            state.value.copy(changing = true, changeCancelable = deleteAfter == null, error = null)
        changeTask = viewModelScope.launch {
            try {
                val result =
                    if (row != null) changes.prepare(session, row)
                    else {
                        var found: BookSourceChangeReceipt? = null
                        for (candidate in candidates) {
                            try {
                                found = changes.prepare(session, candidate, deleteAfter)
                                break
                            } catch (canceled: CancellationException) {
                                throw canceled
                            } catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        found ?: error("没有有效源")
                    }
                currentCoroutineContext().ensureActive()
                if (token == changeGeneration && !stopped) publishReceipt(result.key)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == changeGeneration) failure(error)
            } finally {
                if (!stopped && token == changeGeneration && currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(changing = false, changeCancelable = true)
            }
        }
    }

    fun cancelChange() {
        if (!state.value.changeCancelable) return
        changeGeneration++
        changeTask?.cancel()
        changeTask = null
        if (!stopped) mutable.value = state.value.copy(changing = false, changeCancelable = true)
    }

    suspend fun prepareReceipt(key: String) = changes.receipt(session, key)

    fun consumeReceipt(receipt: BookSourceChangeReceipt) {
        val snapshot = current ?: return
        if (snapshot.pendingReceipt != receipt.key || stopped) return
        saved["consumedReceipt"] = receipt.key
        change(
            snapshot.copy(
                pendingReceipt = null,
                finished = snapshot.finished || receipt.deleteAfter == null,
            )
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                changes.consume(session, receipt.key)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            }
        }
    }

    // The host invokes this suspend function from its application-owned successful callback.
    suspend fun completeReceipt(receipt: BookSourceChangeReceipt) {
        changes.complete(session, receipt.key)
        currentCoroutineContext().ensureActive()
        if (!stopped) receipt.deleteAfter?.let { remove(it) }
    }

    fun receiptFailed(error: Throwable) = failure(error)

    fun disable(id: String) =
        rowOperation(id) {
            changes.disable(it)
            remove(it)
        }

    fun order(id: String, top: Boolean) =
        rowOperation(id) {
            changes.order(it, top)
            refreshCached(false)
        }

    fun score(id: String, score: Int) =
        rowOperation(id) {
            val nextScore = if (it.score == score) 0 else score
            changes.score(it, nextScore)
            currentCoroutineContext().ensureActive()
            val snapshot = current ?: return@rowOperation
            val rows =
                snapshot.rows.map { row ->
                    if (row.id == it.id) row.copy(score = nextScore) else row
                }
            change(snapshot.copy(rows = rows))
            projectLatest(rows)?.let { visible -> mutable.value = state.value.copy(rows = visible) }
        }

    private suspend fun remove(row: ChapterSourceSearchRow) {
        currentCoroutineContext().ensureActive()
        val snapshot = current ?: return
        val rows = snapshot.rows.filterNot { it.id == row.id }
        change(snapshot.copy(rows = rows))
        projectLatest(rows)?.let { mutable.value = state.value.copy(rows = it) }
    }

    fun updateCurrent(book: Book) = launchRead {
        val snapshot = book.copy()
        val json = withContext(Dispatchers.Default) { GSON.toJson(snapshot) }
        currentCoroutineContext().ensureActive()
        val currentValue = current ?: return@launchRead
        oldType = snapshot.type
        originName = snapshot.originName
        change(
            currentValue.copy(
                request =
                    currentValue.request.copy(
                        originalBookJson = json,
                        currentBookUrl = snapshot.bookUrl,
                    )
            )
        )
        updateProjection(false)
    }

    private fun rowOperation(id: String, block: suspend (ChapterSourceSearchRow) -> Unit) {
        val row = current?.rows?.find { it.id == id } ?: return
        launchOperation {
            block(row)
            currentCoroutineContext().ensureActive()
        }
    }

    private fun launchRead(block: suspend () -> Unit) {
        if (stopped || current == null) return
        read?.cancel()
        val token = ++readGeneration
        read = viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == readGeneration) failure(error)
            }
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (
            stopped ||
                state.value.loading ||
                state.value.busy ||
                state.value.changing ||
                state.value.persistError ||
                current?.pendingReceipt != null
        )
            return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(error)
            } finally {
                if (!stopped && currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(busy = false)
            }
        }
    }

    private fun failure(error: Throwable) {
        if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
    }

    fun stop() {
        stopped = true
        searchGeneration++
        readGeneration++
        changeGeneration++
        load?.cancel()
        search?.cancel()
        read?.cancel()
        operation?.cancel()
        changeTask?.cancel()
        writer.cancel()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
