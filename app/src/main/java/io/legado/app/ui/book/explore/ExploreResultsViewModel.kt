package io.legado.app.ui.book.explore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.data.repository.ExploreResultsAddResult
import io.legado.app.data.repository.ExploreResultsCategory
import io.legado.app.data.repository.ExploreResultsCheckpoint
import io.legado.app.data.repository.ExploreResultsNotice
import io.legado.app.data.repository.ExploreResultsRepository
import io.legado.app.data.repository.ExploreResultsRequest
import io.legado.app.data.repository.ExploreResultsRow
import io.legado.app.data.repository.ExploreResultsSessionRepository
import io.legado.app.data.repository.ExploreResultsSource
import io.legado.app.data.repository.ExploreResultsSourceMissing
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface ExploreResultsBookNavigation {
    suspend fun prepare(row: ExploreResultsRow): String

    suspend fun abandon(ticket: String)
}

data class ExploreResultsState(
    val checkpoint: ExploreResultsCheckpoint? = null,
    val loaded: Boolean = false,
    val closed: Boolean = false,
    val loading: Boolean = false,
    val loadingPrevious: Boolean = false,
    val loadingNext: Boolean = false,
    val loadingCategories: Boolean = false,
    val adding: Boolean = false,
    val changingCategories: Boolean = false,
    val missingSource: Boolean = false,
    val loadError: String? = null,
    val showCategories: Boolean = false,
    val loadCoverOnlyWifi: Boolean = false,
    val membership: Set<String> = emptySet(),
    val pagePicker: Int? = null,
    val interruptedPage: Boolean = false,
    val scrollRequest: Long = 0,
    val scrollTargetIndex: Int = 0,
    val scrollTargetOffset: Int = 0,
) {
    val rows: List<ExploreResultsRow>
        get() = checkpoint?.rows.orEmpty()

    val finished: Boolean
        get() = closed || checkpoint?.finished == true
}

/**
 * UI owns small tokens; unrestricted inputs, rows and confirmations remain in its private session.
 */
class ExploreResultsViewModel(
    private val saved: SavedStateHandle,
    private val repository: ExploreResultsRepository,
    private val sessions: ExploreResultsSessionRepository,
    private val navigation: ExploreResultsBookNavigation,
) : ViewModel() {
    val sessionId: String =
        saved.get<String>("exploreResults.session")
            ?: UUID.randomUUID().toString().also { saved["exploreResults.session"] = it }
    private val mutableState = MutableStateFlow(ExploreResultsState())
    val state = mutableState.asStateFlow()
    private val checkpointMutex = Mutex()
    private val paging = ExplorePaginationState()
    private var source: ExploreResultsSource? = null
    private var initialInput: ExploreResultsRequest? = null
    private var checkpoint: ExploreResultsCheckpoint? = null
    private var generation = 0L
    private var nextRevision = saved.get<Long>("exploreResults.revision") ?: 0L
    private var nextScrollRequest = saved.get<Long>("exploreResults.scrollRequest") ?: 0L
    private var loadingJob: Job? = null
    private var pageJob: Job? = null
    private var categoriesJob: Job? = null
    private var addJob: Job? = null
    private var membershipJob: Job? = null
    private var scrollWriteJob: Job? = null
    private var acceptedAdd: ExploreResultsAddResult? = null
    private var stopped = false

    fun load(input: ExploreResultsRequest? = null) {
        if (stopped || state.value.finished || state.value.loading || state.value.loaded) return
        if (input != null) initialInput = input
        val epoch = generation
        mutableState.value =
            state.value.copy(loading = true, loadError = null, missingSource = false)
        loadingJob = viewModelScope.launch {
            try {
                var stored = sessions.read(sessionId)
                currentCoroutineContext().ensureActive()
                if (stored == null) {
                    stored =
                        ExploreResultsCheckpoint(
                            checkNotNull(initialInput) { "Explore session is missing" }
                        )
                    check(sessions.write(sessionId, stored))
                }
                currentCoroutineContext().ensureActive()
                if (!owns(epoch)) return@launch
                checkpoint = stored
                nextRevision = maxOf(nextRevision, stored.revision)
                saved["exploreResults.revision"] = nextRevision
                if (stored.finished) {
                    mutableState.value = state.value.copy(checkpoint = stored, loading = false)
                    return@launch
                }
                val loadedSource = repository.source(stored.request.sourceUrl)
                val showCategories = repository.showCategories()
                val coverOnlyWifi = repository.loadCoverOnlyWifi()
                currentCoroutineContext().ensureActive()
                if (!owns(epoch)) return@launch
                source = loadedSource
                initialInput = null
                paging.skipTo(stored.nextPage)
                mutableState.value =
                    ExploreResultsState(
                        checkpoint = stored,
                        loaded = true,
                        showCategories = showCategories,
                        loadCoverOnlyWifi = coverOnlyWifi,
                        interruptedPage = stored.pendingPage != null,
                        pagePicker = saved.get<Int>("exploreResults.pagePicker"),
                    )
                requestScroll(stored.scrollIndex, stored.scrollOffset)
                observeMembership()
                if (showCategories) loadCategories()
                if (
                    stored.rows.isEmpty() &&
                        stored.error == null &&
                        stored.pendingPage == null &&
                        stored.hasMore
                )
                    next()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (owns(epoch))
                    mutableState.value =
                        state.value.copy(
                            loading = false,
                            missingSource = error is ExploreResultsSourceMissing,
                            loadError = error.message ?: error.toString(),
                        )
            }
        }
    }

    private fun observeMembership() {
        membershipJob?.cancel()
        membershipJob = viewModelScope.launch {
            try {
                repository.membership().collect { membership ->
                    if (!stopped)
                        mutableState.value = state.value.copy(membership = membership.toSet())
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                // Shelf indicators are advisory. Failure must not replace parsed books or query
                // errors.
            }
        }
    }

    fun resume() {
        if (!ready()) return
        command {
            val show = repository.showCategories()
            val coverOnlyWifi = repository.loadCoverOnlyWifi()
            currentCoroutineContext().ensureActive()
            if (!ready()) return@command
            mutableState.value =
                state.value.copy(showCategories = show, loadCoverOnlyWifi = coverOnlyWifi)
            if (show) loadCategories()
        }
    }

    fun toggleCategories() {
        if (!ready() || state.value.changingCategories) return
        val desired = !state.value.showCategories
        mutableState.value = state.value.copy(changingCategories = true)
        command {
            try {
                repository.showCategories(desired)
                currentCoroutineContext().ensureActive()
                if (!ready()) return@command
                mutableState.value = state.value.copy(showCategories = desired)
                if (desired) loadCategories()
            } finally {
                if (!stopped) mutableState.value = state.value.copy(changingCategories = false)
            }
        }
    }

    private fun loadCategories() {
        val currentSource = source ?: return
        if (categoriesJob?.isActive == true || checkpoint?.categories?.isNotEmpty() == true) return
        val epoch = generation
        mutableState.value = state.value.copy(loadingCategories = true)
        categoriesJob = viewModelScope.launch {
            try {
                val categories = repository.categories(currentSource)
                currentCoroutineContext().ensureActive()
                if (owns(epoch)) update(epoch) { it.copy(categories = categories) }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (owns(epoch)) reportError(error)
            } finally {
                if (owns(epoch)) mutableState.value = state.value.copy(loadingCategories = false)
            }
        }
    }

    fun category(category: ExploreResultsCategory) {
        if (!ready() || category.url.isBlank() || checkpoint?.selectedCategory == category) return
        reset(category, 1)
    }

    fun showPagePicker() {
        if (!ready()) return
        val page = checkpoint!!.displayedPage.coerceIn(1, 999)
        saved["exploreResults.pagePicker"] = page
        mutableState.value = state.value.copy(pagePicker = page)
    }

    fun pagePicker(value: Int) {
        if (state.value.pagePicker == null) return
        val page = value.coerceIn(1, 999)
        saved["exploreResults.pagePicker"] = page
        mutableState.value = state.value.copy(pagePicker = page)
    }

    fun cancelPagePicker() {
        saved.remove<Int>("exploreResults.pagePicker")
        mutableState.value = state.value.copy(pagePicker = null)
    }

    fun confirmPage() {
        val page = state.value.pagePicker ?: return
        cancelPagePicker()
        if (page != checkpoint?.displayedPage) reset(checkpoint!!.selectedCategory, page)
    }

    private fun reset(category: ExploreResultsCategory, page: Int) {
        val epoch = ++generation
        pageJob?.cancel()
        categoriesJob?.cancel()
        paging.skipTo(page)
        mutableState.value =
            state.value.copy(
                loadingNext = false,
                loadingPrevious = false,
                loadingCategories = false,
            )
        command {
            if (
                update(epoch) {
                    it.copy(
                        selectedCategory = category,
                        rows = emptyList(),
                        firstPage = page,
                        displayedPage = page,
                        nextPage = page,
                        hasMore = true,
                        error = null,
                        topError = null,
                        pendingPage = null,
                        pendingPrevious = false,
                        scrollKey = null,
                        scrollIndex = 0,
                        scrollOffset = 0,
                        detailKey = null,
                        detailNonce = null,
                        detailTicket = null,
                        detailDelivered = false,
                    )
                }
            ) {
                requestScroll(0, 0)
                next()
                if (state.value.showCategories) loadCategories()
            }
        }
    }

    fun next(force: Boolean = false) {
        val current = checkpoint ?: return
        if (!ready() || paging.isLoading || (!current.hasMore && !force)) return
        val page = current.pendingPage?.takeUnless { current.pendingPrevious } ?: paging.nextPage
        fetch(page, previous = false)
    }

    fun previous() {
        val current = checkpoint ?: return
        if (!ready() || paging.isLoading) return
        if (current.firstPage <= 1) {
            command { update(generation) { it.copy(displayedPage = 1) } }
            return
        }
        fetch(
            current.pendingPage?.takeIf { current.pendingPrevious } ?: current.firstPage - 1,
            previous = true,
        )
    }

    private fun fetch(page: Int, previous: Boolean) {
        val currentSource = source ?: return
        val epoch = generation
        val request = if (previous) paging.startPage(page) ?: return else paging.startNextPage()
        val category = checkpoint!!.selectedCategory
        mutableState.value =
            state.value.copy(
                loadingPrevious = previous,
                loadingNext = !previous,
                interruptedPage = false,
            )
        pageJob = viewModelScope.launch {
            try {
                if (
                    !update(epoch) {
                        it.copy(
                            pendingPage = request.page,
                            pendingPrevious = previous,
                            error = if (previous) it.error else null,
                            topError = if (previous) null else it.topError,
                        )
                    }
                )
                    return@launch
                val rows =
                    if (BuildConfig.DEBUG)
                        repository.page(currentSource, category.url, request.page)
                    else
                        withTimeout(60_000) {
                            repository.page(currentSource, category.url, request.page)
                        }
                currentCoroutineContext().ensureActive()
                if (!owns(epoch) || !paging.isActive(request)) return@launch
                repository.cache(rows)
                currentCoroutineContext().ensureActive()
                if (!owns(epoch) || !paging.isActive(request)) return@launch
                val accepted =
                    update(epoch) { stored ->
                        val merged =
                            if (previous) (rows + stored.rows).distinctBy { it.bookUrl }
                            else (stored.rows + rows).distinctBy { it.bookUrl }
                        val anchor =
                            stored.scrollKey?.let { key ->
                                merged.indexOfFirst { it.key == key }.takeIf { it >= 0 }
                            }
                        stored.copy(
                            rows = merged,
                            firstPage = if (previous) request.page else stored.firstPage,
                            displayedPage = request.page,
                            nextPage = if (previous) paging.nextPage else request.page + 1,
                            hasMore =
                                if (previous) stored.hasMore else merged.size > stored.rows.size,
                            pendingPage = null,
                            pendingPrevious = false,
                            scrollIndex =
                                if (previous)
                                    anchor ?: stored.scrollIndex + (merged.size - stored.rows.size)
                                else stored.scrollIndex,
                        )
                    }
                if (!accepted || !paging.complete(request)) return@launch
                if (previous) requestScroll(checkpoint!!.scrollIndex, checkpoint!!.scrollOffset)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (owns(epoch) && paging.fail(request)) {
                    try {
                        update(epoch) {
                            if (previous)
                                it.copy(
                                    topError = error.message ?: error.toString(),
                                    pendingPage = null,
                                )
                            else
                                it.copy(
                                    error = error.message ?: error.toString(),
                                    pendingPage = null,
                                )
                        }
                    } catch (persistenceError: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (owns(epoch))
                            mutableState.value =
                                state.value.copy(
                                    loadError =
                                        persistenceError.message ?: persistenceError.toString()
                                )
                    }
                }
            } finally {
                if (owns(epoch))
                    mutableState.value =
                        state.value.copy(loadingPrevious = false, loadingNext = false)
            }
        }
    }

    fun retryLoad() {
        if (!state.value.loaded) load()
        else if (checkpoint?.pendingPrevious == true) previous() else next(force = true)
    }

    fun scroll(key: String?, index: Int, offset: Int) {
        if (!ready() || state.value.scrollRequest != 0L) return
        scrollWriteJob?.cancel()
        val previousWrite = scrollWriteJob
        val epoch = generation
        scrollWriteJob = viewModelScope.launch {
            previousWrite?.join()
            update(epoch) {
                it.copy(
                    scrollKey = key,
                    scrollIndex = index.coerceAtLeast(0),
                    scrollOffset = offset.coerceAtLeast(0),
                )
            }
        }
    }

    private fun requestScroll(index: Int, offset: Int) {
        val token = ++nextScrollRequest
        saved["exploreResults.scrollRequest"] = token
        mutableState.value =
            state.value.copy(
                scrollRequest = token,
                scrollTargetIndex = index,
                scrollTargetOffset = offset,
            )
    }

    fun scrolled(token: Long) {
        if (state.value.scrollRequest == token)
            mutableState.value = state.value.copy(scrollRequest = 0)
    }

    fun askAdd() {
        if (!ready()) return
        if (state.value.adding) {
            command { notice(ExploreResultsNotice.AlreadyAdding) }
        } else if (state.value.rows.isEmpty()) {
            command { notice(ExploreResultsNotice.EmptyResults) }
        } else {
            val rows = state.value.rows.toList()
            val epoch = generation
            command { update(epoch) { it.copy(addRows = rows) } }
        }
    }

    fun cancelAdd() {
        if (state.value.adding || acceptedAdd != null) return
        command { update(generation) { it.copy(addRows = null) } }
    }

    fun confirmAdd() {
        val rows = checkpoint?.addRows ?: return
        if (!ready() || state.value.adding || rows.isEmpty()) return
        mutableState.value = state.value.copy(adding = true)
        addJob = viewModelScope.launch {
            try {
                val accepted = acceptedAdd
                if (accepted != null) recordAdd(accepted)
                else repository.addToShelfRecorded(rows, ::recordAdd)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!stopped) reportError(error)
            } finally {
                if (!stopped) mutableState.value = state.value.copy(adding = false)
            }
        }
    }

    private suspend fun recordAdd(result: ExploreResultsAddResult) =
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            acceptedAdd = result
            checkpointMutex.withLock {
                val current = checkNotNull(checkpoint)
                val durable = sessions.read(sessionId)
                val revision = maxOf(nextRevision, durable?.revision ?: -1) + 1
                val receipt =
                    current.copy(
                        revision = revision,
                        addRows = null,
                        messageId = UUID.randomUUID().toString(),
                        addedCount = result.added,
                        skippedCount = result.skipped,
                        message = null,
                        notice = null,
                    )
                // This short receipt write outlives cancellation of the accepted add operation.
                // A failed write retains the accepted result for an explicit retry without adding
                // the same snapshot a second time.
                check(sessions.write(sessionId, receipt))
                nextRevision = revision
                saved["exploreResults.revision"] = revision
                checkpoint = receipt
                acceptedAdd = null
                if (!stopped) mutableState.value = state.value.copy(checkpoint = receipt)
            }
        }

    private suspend fun notice(value: ExploreResultsNotice) {
        update(generation) {
            it.copy(
                messageId = UUID.randomUUID().toString(),
                notice = value,
                message = null,
                addedCount = null,
                skippedCount = null,
            )
        }
    }

    private suspend fun reportError(error: Throwable) {
        try {
            message(error.message ?: error.toString())
        } catch (persistenceError: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!stopped)
                mutableState.value =
                    state.value.copy(
                        loadError = persistenceError.message ?: persistenceError.toString()
                    )
        }
    }

    private suspend fun message(value: String) {
        update(generation) {
            it.copy(
                messageId = UUID.randomUUID().toString(),
                message = value,
                notice = null,
                addedCount = null,
                skippedCount = null,
            )
        }
    }

    suspend fun messageDelivered(id: String): Boolean {
        if (checkpoint?.messageId != id) return false
        return update(generation) {
            it.copy(
                messageId = null,
                message = null,
                notice = null,
                addedCount = null,
                skippedCount = null,
            )
        }
    }

    fun detail(key: String) {
        if (!ready() || checkpoint!!.rows.none { it.key == key }) return
        command {
            update(generation) {
                it.copy(
                    detailKey = key,
                    detailNonce = UUID.randomUUID().toString(),
                    detailTicket = null,
                    detailDelivered = false,
                )
            }
        }
    }

    suspend fun prepareDetail(nonce: String): String? {
        val current = checkpoint ?: return null
        if (!ready() || current.detailNonce != nonce || current.detailDelivered) return null
        current.detailTicket?.let {
            return it
        }
        val row = current.rows.firstOrNull { it.key == current.detailKey } ?: return null
        var owned: String? = null
        try {
            owned = navigation.prepare(row)
            currentCoroutineContext().ensureActive()
            if (!ready() || checkpoint?.detailNonce != nonce) return null
            val ticket = checkNotNull(owned)
            if (
                update(generation) {
                    it.copy(
                        detailTicket = ticket,
                        ownedDetailTickets = it.ownedDetailTickets + ticket,
                    )
                }
            ) {
                owned = null
                return ticket
            }
            return null
        } finally {
            owned?.let { withContext(Dispatchers.IO + NonCancellable) { navigation.abandon(it) } }
        }
    }

    suspend fun detailClaimed(nonce: String): Boolean {
        if (!ready() || checkpoint?.detailNonce != nonce || checkpoint?.detailDelivered == true)
            return false
        return update(generation) { it.copy(detailDelivered = true) }
    }

    suspend fun detailDeferred(nonce: String) =
        withContext(NonCancellable) {
            if (checkpoint?.detailNonce == nonce && !stopped)
                update(generation) { it.copy(detailDelivered = false) }
        }

    suspend fun detailHandled(nonce: String) {
        if (checkpoint?.detailNonce == nonce)
            update(generation) {
                it.copy(
                    detailKey = null,
                    detailNonce = null,
                    detailTicket = null,
                    detailDelivered = false,
                )
            }
    }

    fun close() {
        if (stopped || state.value.adding) return
        val epoch = ++generation
        pageJob?.cancel()
        loadingJob?.cancel()
        command {
            if (checkpoint == null) mutableState.value = state.value.copy(closed = true)
            else update(epoch) { it.copy(finished = true) }
        }
    }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!stopped)
                    mutableState.value =
                        state.value.copy(loadError = error.message ?: error.toString())
            }
        }
    }

    private fun ready(): Boolean = !stopped && state.value.loaded && !state.value.finished

    private fun owns(epoch: Long): Boolean = !stopped && generation == epoch

    private suspend fun update(
        epoch: Long,
        transform: (ExploreResultsCheckpoint) -> ExploreResultsCheckpoint,
    ): Boolean = checkpointMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!owns(epoch)) return@withLock false
        val current = checkpoint ?: return@withLock false
        val durable = sessions.read(sessionId)
        currentCoroutineContext().ensureActive()
        if (!owns(epoch)) return@withLock false
        val revision = maxOf(nextRevision, durable?.revision ?: -1) + 1
        nextRevision = revision
        saved["exploreResults.revision"] = revision
        val next = transform(current).copy(revision = revision)
        check(sessions.write(sessionId, next)) { "Explore session is unavailable" }
        currentCoroutineContext().ensureActive()
        if (!owns(epoch)) return@withLock false
        checkpoint = next
        mutableState.value = state.value.copy(checkpoint = next)
        true
    }

    fun stop() {
        stopped = true
        generation++
        loadingJob?.cancel()
        pageJob?.cancel()
        categoriesJob?.cancel()
        addJob?.cancel()
        membershipJob?.cancel()
        scrollWriteJob?.cancel()
    }

    override fun onCleared() {
        val tickets = checkpoint?.ownedDetailTickets.orEmpty()
        stop()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            tickets.forEach { runCatching { navigation.abandon(it) } }
            sessions.release(sessionId)
        }
        super.onCleared()
    }
}
