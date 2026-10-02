package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ChapterSourceMatch
import io.legado.app.help.book.matchChapterSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class ChapterSourceState(val loading: Boolean = true, val searching: Boolean = false,
    val tocLoading: Boolean = false, val contentLoading: Boolean = false, val caching: Boolean = false,
    val changing: Boolean = false, val cacheRecoveryError: Boolean = false, val busy: Boolean = false, val error: String? = null, val persistError: Boolean = false,
    val request: ChapterSourceSearchRequest? = null, val rows: List<ChapterSourceSearchRow> = emptyList(),
    val groups: List<String> = emptyList(), val chapterIndex: Int = 0, val chapterTitle: String = "", val originName: String = "",
    val batch: Boolean = false, val toc: ChapterSourceToc? = null, val tocVisible: Boolean = false,
    val selected: Set<Int> = emptySet(), val currentOriginal: ChapterSourceChapter? = null,
    val automation: ChapterSourceAutomation? = null, val automationFinished: Boolean = false,
    val pendingReceipt: String? = null, val finished: Boolean = false, val emptyGroup: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val sourceName: String = "", val searchOpen: Boolean = false)
internal class ChapterSourceViewModel(private val searches: ChapterSourceSearchRepository,
    private val content: ChapterSourceContentRepository, private val settings: ChapterSourceSettingsRepository,
    private val saved: SavedStateHandle, private val seed: suspend () -> ChapterSourceSession) : ViewModel() {
    val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(ChapterSourceState(searchOpen = saved.get<Boolean>("searchOpen") == true))
    val state = mutable.asStateFlow()
    private var current: ChapterSourceSession? = null
    private var revisionCounter = 0L
    private var originalOriginName = ""
    private var originalType: Int? = null
    private val writes = MutableStateFlow<ChapterSourceSession?>(null)
    private var load: Job? = null; private var search: Job? = null; private var tocTask: Job? = null
    private var operation: Job? = null; private var writer: Job? = null
    private var searchGeneration = 0L; private var tocGeneration = 0L; private var stopped = false
    private var commitStarted = false; private var pendingMeasurements = false
    private val deletedReceipts = mutableSetOf<String>()
    init {
        writer = viewModelScope.launch {
            writes.filterNotNull().collect { value ->
                try { content.write(session, value); currentCoroutineContext().ensureActive()
                    if (!stopped && current?.revision == value.revision && state.value.persistError) mutable.value = state.value.copy(persistError = false, error = null)
                } catch (canceled: CancellationException) { throw canceled }
                catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (!stopped && current?.revision == value.revision) mutable.value = state.value.copy(error = failure.localizedMessage.orEmpty(), persistError = true) }
            }
        }
        initialize()
    }
    private fun initialize() {
        if (stopped) return
        load?.cancel(); mutable.value = state.value.copy(loading = true, error = null, cacheRecoveryError = false)
        load = viewModelScope.launch {
            try {
                var loaded = content.read(session) ?: seed().also { content.write(session, it) }
                val preferences = settings.load(); loaded = loaded.copy(request = preferences.apply(loaded.request))
                if (loaded.batch && loaded.originalChapters.isEmpty()) {
                    val book = loaded.request.originalBookJson ?: error("原书籍不存在")
                    loaded = loaded.copy(originalChapters = content.original(book))
                }
                val recovered = try {
                    loaded.request.originalBookJson?.let { content.recoverCache(session, it, loaded.originalChapters) }
                } catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(cacheRecoveryError = true); throw error }
                if (loaded.pendingReceipt == null && recovered != null) loaded = loaded.copy(pendingReceipt = recovered.key)
                loaded.pendingReceipt?.let { key ->
                    val receipt = content.receipt(session, key)
                    if (receipt.consumed || saved.get<String>("consumedReceipt") == key) {
                        loaded = acknowledge(loaded, receipt).copy(pendingReceipt = null)
                        content.consume(session, key)
                    }
                }
                // Restoring never automatically repeats a potentially active network/cache commit.
                loaded.automation?.takeIf { it.stage == "Caching" || it.stage == "Ready" }?.let { automatic ->
                    if (loaded.pendingReceipt == null) loaded = loaded.copy(automation = automatic.copy(stage = "Paused", reason = "恢复后请确认目录选择再继续"))
                }
                revisionCounter = maxOf(revisionCounter, loaded.revision)
                loaded = loaded.copy(revision = nextRevision())
                content.write(session, loaded); currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                val groups = content.groups()
                val original = withContext(Dispatchers.Default) { loaded.request.originalBookJson?.let { GSON.fromJson(it, Book::class.java) } }
                val visible = project(loaded.request, loaded.rows)
                currentCoroutineContext().ensureActive()
                originalOriginName = original?.originName.orEmpty(); originalType = original?.type
                current = loaded; render(); mutable.value = state.value.copy(loading = false, groups = groups, rows = visible)
                currentCoroutineContext().ensureActive()
                if (loaded.finished || loaded.pendingReceipt != null) return@launch
                val cached = searches.cached(loaded.request); currentCoroutineContext().ensureActive()
                change(requireNotNull(current).copy(rows = cached.allRows)); mutable.value = state.value.copy(rows = cached.rows)
                if (cached.allRows.isEmpty()) startSearch() else if (loaded.request.loadWordCount) measure(true)
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(loading = false, error = failure.localizedMessage.orEmpty()) }
        }
    }
    private fun render() {
        val value = current ?: return
        val original = value.originalChapters.firstOrNull { it.index == value.chapterIndex }.takeUnless { value.finished }
        mutable.value = state.value.copy(request = value.request.copy(originalBookJson = null),
            chapterIndex = value.chapterIndex, chapterTitle = original?.title ?: value.chapterTitle, originName = originalOriginName,
            batch = value.batch, toc = value.toc, tocVisible = value.tocVisible, selected = value.selected.toSet(),
            currentOriginal = original, automation = value.automation, pendingReceipt = value.pendingReceipt, finished = value.finished)
    }
    private fun change(value: ChapterSourceSession) {
        if (stopped) return
        current = value.copy(revision = nextRevision()); render(); writes.value = current
    }
    private fun nextRevision(): Long {
        revisionCounter = maxOf(revisionCounter + 1, (current?.revision ?: 0) + 1, System.nanoTime())
        return revisionCounter
    }
    private suspend fun durable(value: ChapterSourceSession) {
        val baseline = current ?: value
        while (!stopped) {
            val latest = current ?: baseline
            val merged = latest.copy(
                request = if (value.request != baseline.request) value.request else latest.request,
                chapterIndex = if (value.chapterIndex != baseline.chapterIndex) value.chapterIndex else latest.chapterIndex,
                chapterTitle = if (value.chapterTitle != baseline.chapterTitle) value.chapterTitle else latest.chapterTitle,
                originalChapters = if (value.originalChapters != baseline.originalChapters) value.originalChapters else latest.originalChapters,
                toc = if (value.toc != baseline.toc) value.toc else latest.toc,
                tocVisible = if (value.tocVisible != baseline.tocVisible) value.tocVisible else latest.tocVisible,
                selected = if (value.selected != baseline.selected) value.selected else latest.selected,
                rows = if (value.rows != baseline.rows) value.rows else latest.rows,
                pendingReceipt = if (value.pendingReceipt != baseline.pendingReceipt) value.pendingReceipt else latest.pendingReceipt,
                automation = if (value.automation != baseline.automation) value.automation else latest.automation,
                finished = if (value.finished != baseline.finished) value.finished else latest.finished,
                revision = nextRevision())
            content.write(session, merged); currentCoroutineContext().ensureActive()
            if (current?.revision == latest.revision) { current = merged; render(); return }
        }
    }
    fun retry() {
        if (stopped) return
        if (state.value.persistError && current != null) { change(requireNotNull(current)); return }
        if (current == null) initialize() else { mutable.value = state.value.copy(error = null); refresh() }
    }
    fun retryCacheRecovery() {
        if (stopped || !state.value.cacheRecoveryError || state.value.loading) return
        mutable.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try { content.abandonUncommittedCache(session); currentCoroutineContext().ensureActive(); initialize() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(loading = false, error = error.localizedMessage.orEmpty()) }
        }
    }
    fun filterOpen() { val open = !state.value.searchOpen; saved["searchOpen"] = open; mutable.value = state.value.copy(searchOpen = open) }
    fun query(value: String) {
        val snapshot = current ?: return
        change(snapshot.copy(request = snapshot.request.copy(query = value.trim())))
        launchRead { refreshCached(false) }
    }
    fun startOrStop() { if (state.value.searching) stopSearch() else startSearch() }
    fun stopSearch() { searchGeneration++; search?.cancel(); search = null; pendingMeasurements = false
        if (!stopped) mutable.value = state.value.copy(searching = false) }
    fun startSearch() {
        val snapshot = current ?: return
        if (stopped || state.value.persistError || state.value.caching || state.value.changing) return
        stopSearch(); val token = ++searchGeneration
        search = viewModelScope.launch {
            mutable.value = state.value.copy(searching = true, error = null, emptyGroup = false)
            try { searches.search(snapshot.request, snapshot.rows).collect { update -> acceptSearch(update, token) } }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); failure(failure) }
            finally { if (!stopped && token == searchGeneration && currentCoroutineContext().isActive) {
                mutable.value = state.value.copy(searching = false); if (pendingMeasurements) { pendingMeasurements = false; measure(true) }
            } }
        }
    }
    private suspend fun acceptSearch(update: ChapterSourceSearchUpdate, token: Long) {
        currentCoroutineContext().ensureActive(); if (stopped || token != searchGeneration) return
        var snapshot = current ?: return
        if (snapshot.request.group != update.effectiveGroup && update.effectiveGroup.isEmpty()) {
            val prefs = settings.group(""); currentCoroutineContext().ensureActive(); snapshot = requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)); change(snapshot)
        }
        val visible = projectLatest(update.allRows, token) ?: return
        change(requireNotNull(current).copy(rows = update.allRows))
        mutable.value = state.value.copy(rows = visible, searching = update.running, completed = update.completed, total = update.total,
            sourceName = update.sourceName, emptyGroup = !update.running && visible.isEmpty() && requireNotNull(current).request.group.isNotEmpty())
    }
    private suspend fun project(request: ChapterSourceSearchRequest, rows: List<ChapterSourceSearchRow>) = searches.project(request,
        rows.filter { request.query.isEmpty() || it.name.contains(request.query) })
    private suspend fun projectLatest(rows: List<ChapterSourceSearchRow>, searchToken: Long? = null): List<ChapterSourceSearchRow>? {
        while (!stopped) {
            if (searchToken != null && searchToken != searchGeneration) return null
            val request = current?.request ?: return null
            val visible = project(request, rows)
            currentCoroutineContext().ensureActive()
            if (stopped || searchToken != null && searchToken != searchGeneration) return null
            if (current?.request == request) return visible
        }
        return null
    }
    private fun measure(missingOnly: Boolean) {
        val snapshot = current ?: return
        if (state.value.searching) { pendingMeasurements = true; return }
        if (stopped || !snapshot.request.loadWordCount) return
        val token = ++searchGeneration
        search = viewModelScope.launch {
            try { searches.measure(snapshot.request, snapshot.rows, missingOnly).collect { acceptSearch(it, token) } }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); failure(failure) }
            finally { if (!stopped && token == searchGeneration && currentCoroutineContext().isActive) mutable.value = state.value.copy(searching = false) }
        }
    }
    fun refresh() { launchRead { refreshCached(true) } }
    private suspend fun refreshCached(searchWhenEmpty: Boolean) {
        val snapshot = current ?: return
        val cached = searches.cached(snapshot.request); currentCoroutineContext().ensureActive()
        if (stopped || current?.request != snapshot.request) return
        change(requireNotNull(current).copy(rows = cached.allRows)); mutable.value = state.value.copy(rows = cached.rows)
        if (searchWhenEmpty && cached.allRows.isEmpty()) startSearch() else if (snapshot.request.loadWordCount) measure(true)
    }
    fun toggle(option: ChapterSourceOption) = launchOperation {
        val snapshot = current ?: return@launchOperation
        val prefs = settings.toggle(option); currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
        when (option) {
            ChapterSourceOption.Author -> refreshCached(false)
            ChapterSourceOption.WordCount, ChapterSourceOption.ResponseTime -> optionsChanged(true)
            else -> Unit
        }
    }
    fun group(value: String) = launchOperation {
        stopSearch(); val snapshot = current ?: return@launchOperation
        val prefs = settings.group(value); currentCoroutineContext().ensureActive()
        change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request))); mutable.value = state.value.copy(emptyGroup = false); refreshCached(true)
    }
    fun dismissEmptyGroup() { mutable.value = state.value.copy(emptyGroup = false) }
    fun optionsChanged(reload: Boolean) {
        launchRead {
            val snapshot = current ?: return@launchRead
            val prefs = settings.load(); currentCoroutineContext().ensureActive()
            change(requireNotNull(current).copy(request = prefs.apply(requireNotNull(current).request)))
            val visible = projectLatest(requireNotNull(current).rows) ?: return@launchRead
            if (!stopped) mutable.value = state.value.copy(rows = visible)
            if (reload) measure(true)
        }
    }
    fun openToc(id: String) {
        val snapshot = current ?: return; val row = snapshot.rows.find { it.id == id } ?: return
        if (snapshot.automation != null || state.value.caching || state.value.tocLoading) return
        tocTask?.cancel(); operation?.cancel(); val token = ++tocGeneration
        change(snapshot.copy(tocVisible = true, toc = null, selected = emptySet()))
        mutable.value = state.value.copy(tocLoading = true, contentLoading = false, busy = false, error = null)
        tocTask = viewModelScope.launch {
            try { val toc = content.toc(row, snapshot.chapterIndex, snapshot.chapterTitle); currentCoroutineContext().ensureActive()
                if (token == tocGeneration && !stopped) change(requireNotNull(current).copy(toc = toc, tocVisible = true))
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (token == tocGeneration && !stopped) { change(requireNotNull(current).copy(tocVisible = false)); failure(failure) } }
            finally { if (!stopped && token == tocGeneration && currentCoroutineContext().isActive) mutable.value = state.value.copy(tocLoading = false) }
        }
    }
    fun hideToc() {
        val snapshot = current ?: return
        if (snapshot.batch && (state.value.tocLoading || state.value.caching || snapshot.automation != null)) return
        tocGeneration++; tocTask?.cancel(); operation?.cancel()
        change(snapshot.copy(toc = null, tocVisible = false, selected = emptySet()))
        mutable.value = state.value.copy(tocLoading = false, contentLoading = false, busy = false)
    }
    fun chapter(position: Int) {
        val snapshot = current ?: return; val toc = snapshot.toc ?: return; val selected = toc.chapters.getOrNull(position) ?: return
        if (selected.volume || state.value.tocLoading || state.value.caching || state.value.contentLoading) return
        if (snapshot.batch) {
            if (snapshot.automation?.stage?.let { it != "Paused" } == true) return
            val next = snapshot.selected.toMutableSet(); if (!next.add(selected.index)) next.remove(selected.index)
            change(snapshot.copy(selected = next.toSet()))
        } else launchOperation {
            mutable.value = state.value.copy(contentLoading = true)
            try { val receipt = content.content(session, toc, position); currentCoroutineContext().ensureActive()
                durable(requireNotNull(current).copy(pendingReceipt = receipt.key))
            } finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(contentLoading = false) }
        }
    }
    private fun launchRead(block: suspend () -> Unit) {
        if (stopped) return
        viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); failure(failure) }
        }
    }
    private fun launchOperation(block: suspend () -> Unit) {
        if (stopped || state.value.loading || state.value.persistError || state.value.busy || state.value.pendingReceipt != null) return
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); failure(failure) }
            finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun receiptFailed(error: Throwable) = failure(error)
    private fun failure(error: Throwable) { if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) }
    fun stop() { stopped = true; searchGeneration++; tocGeneration++; load?.cancel(); search?.cancel(); tocTask?.cancel(); operation?.cancel(); writer?.cancel() }
    override fun onCleared() { stop(); super.onCleared() }
    // Batch/automation and receipt acknowledgements are defined below to keep all state transitions in this owner.
    fun cacheSelected() = launchOperation {
        val snapshot = current ?: return@launchOperation; val toc = snapshot.toc ?: return@launchOperation
        val positions = toc.chapters.mapIndexedNotNull { position, chapter -> position.takeIf { !chapter.volume && chapter.index in snapshot.selected } }
        if (positions.isNotEmpty()) cachePositions(positions)
    }
    private suspend fun cachePositions(positions: List<Int>) {
        val snapshot = current ?: return; val toc = snapshot.toc ?: return
        val original = snapshot.originalChapters.firstOrNull { it.index == snapshot.chapterIndex } ?: return
        val book = snapshot.request.originalBookJson ?: return
        val automatic = snapshot.automation?.copy(stage = "Caching", reason = null, positions = positions)
        durable(snapshot.copy(automation = automatic, selected = positions.mapTo(linkedSetOf()) { toc.chapters[it].index }))
        mutable.value = state.value.copy(caching = true); commitStarted = false
        try {
            val receipt = content.cache(session, toc, positions, book, original) {
                withContext(Dispatchers.Main.immediate) { commitStarted = true }
            }
            currentCoroutineContext().ensureActive()
            durable(requireNotNull(current).copy(pendingReceipt = receipt.key))
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val latest = current ?: throw failure
            latest.automation?.let { change(latest.copy(automation = it.copy(stage = "Paused", reason = failure.localizedMessage.orEmpty()))) }
            throw failure
        } finally { commitStarted = false; if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(caching = false) }
    }
    fun cancelCaching() {
        if (commitStarted) return
        operation?.cancel()
        current?.let { snapshot -> snapshot.automation?.takeIf { it.stage == "Caching" }?.let {
            change(snapshot.copy(automation = it.copy(stage = "Paused", reason = "已取消，请确认目录选择再继续")))
        } }
        mutable.value = state.value.copy(caching = false, busy = false)
    }
    fun rangeDefaults(): IntRange? {
        val snapshot = current ?: return null; val chapters = snapshot.originalChapters.filterNot { it.volume }
        val start = chapters.indexOfFirst { it.index == snapshot.chapterIndex }
        return if (start < 0) null else (start + 1)..chapters.size
    }
    fun startAutomation(start: Int, end: Int): Boolean {
        val snapshot = current ?: return false; val toc = snapshot.toc ?: return false
        if (state.value.busy || state.value.caching || snapshot.automation != null) return false
        val chapters = snapshot.originalChapters.filterNot { it.volume }
        if (start !in 1..chapters.size || end !in start..chapters.size) return false
        val selected = chapters.subList(start - 1, end).toList()
        val automatic = ChapterSourceAutomation(selected, target = toc)
        change(snapshot.copy(automation = automatic, chapterIndex = selected.first().index, chapterTitle = selected.first().title, selected = emptySet()))
        return true
    }
    fun runAutomationIfReady() = launchOperation {
        val snapshot = current ?: return@launchOperation; val automatic = snapshot.automation ?: return@launchOperation
        if (automatic.stage != "Ready") return@launchOperation
        val original = automatic.chapters.getOrNull(automatic.position) ?: return@launchOperation
        val match = withContext(Dispatchers.Default) {
            matchChapterSource(GSON.fromJson(original.json, BookChapter::class.java), automatic.target.chapters.map { GSON.fromJson(it.json, BookChapter::class.java) })
        }
        currentCoroutineContext().ensureActive()
        if (current?.automation != automatic) return@launchOperation
        when (match) {
            is ChapterSourceMatch.Unique -> cachePositions(listOf(match.targetPosition))
            is ChapterSourceMatch.Ambiguous -> change(snapshot.copy(automation = automatic.copy(stage = "Paused", reason = "Ambiguous", positions = match.targetPositions), selected = emptySet()))
            ChapterSourceMatch.Missing -> change(snapshot.copy(automation = automatic.copy(stage = "Paused", reason = "Missing", positions = emptyList()), selected = emptySet()))
        }
    }
    fun stopAutomation() {
        val snapshot = current ?: return; val automatic = snapshot.automation ?: return
        if (commitStarted) { change(snapshot.copy(automation = automatic.copy(stopAfterCurrent = true))); return }
        cancelCaching(); change(snapshot.copy(automation = null))
    }
    fun skip() {
        val snapshot = current ?: return
        if (state.value.tocLoading || state.value.caching) return
        val automatic = snapshot.automation
        if (automatic != null) {
            if (automatic.stage != "Ready" && automatic.stage != "Paused") return
            val original = automatic.chapters.getOrNull(automatic.position) ?: return
            change(acknowledge(snapshot, ChapterSourceReceipt("skip", ChapterSourceReceiptKind.Cache, chapterIndex = original.index)))
        } else {
            val next = snapshot.originalChapters.firstOrNull { !it.volume && it.index > snapshot.chapterIndex }
            change(if (next == null) snapshot.copy(finished = true, selected = emptySet()) else snapshot.copy(chapterIndex = next.index, chapterTitle = next.title, selected = emptySet()))
        }
    }
    private fun acknowledge(snapshot: ChapterSourceSession, receipt: ChapterSourceReceipt): ChapterSourceSession {
        if (receipt.kind == ChapterSourceReceiptKind.Content) return snapshot.copy(pendingReceipt = null, finished = true)
        if (receipt.kind != ChapterSourceReceiptKind.Cache) return snapshot.copy(pendingReceipt = null)
        val automatic = snapshot.automation
        if (automatic != null && automatic.chapters.getOrNull(automatic.position)?.index == receipt.chapterIndex) {
            val position = automatic.position + 1; val next = automatic.chapters.getOrNull(position)
            return when {
                next == null -> snapshot.copy(automation = null, finished = true, pendingReceipt = null, selected = emptySet())
                automatic.stopAfterCurrent -> snapshot.copy(automation = null, chapterIndex = next.index, chapterTitle = next.title, pendingReceipt = null, selected = emptySet())
                else -> snapshot.copy(automation = automatic.copy(position = position, stage = "Ready", positions = emptyList(), reason = null),
                    chapterIndex = next.index, chapterTitle = next.title, pendingReceipt = null, selected = emptySet())
            }
        }
        if (snapshot.chapterIndex != receipt.chapterIndex) return snapshot.copy(pendingReceipt = null, selected = emptySet())
        val next = snapshot.originalChapters.firstOrNull { !it.volume && it.index > requireNotNull(receipt.chapterIndex) }
        return if (next == null) snapshot.copy(finished = true, pendingReceipt = null, selected = emptySet())
            else snapshot.copy(chapterIndex = next.index, chapterTitle = next.title, pendingReceipt = null, selected = emptySet())
    }
    suspend fun prepareReceipt(key: String) = content.receipt(session, key)
    fun consumeReceipt(receipt: ChapterSourceReceipt) {
        val snapshot = current ?: return
        if (stopped || snapshot.pendingReceipt != receipt.key) return
        saved["consumedReceipt"] = receipt.key
        change(acknowledge(snapshot, receipt))
        viewModelScope.launch {
            try { content.consume(session, receipt.key); currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); failure(error) }
        }
    }
    fun disable(id: String) = sourceOperation(id) { row -> content.disableSource(row); refreshCached(false) }
    fun order(id: String, top: Boolean) = sourceOperation(id) { row -> content.order(row, top); refreshCached(false) }
    fun score(id: String, score: Int) = sourceOperation(id) { row -> content.score(row, score); refreshCached(false) }
    private fun sourceOperation(id: String, action: suspend (ChapterSourceSearchRow) -> Unit) = launchOperation {
        val row = current?.rows?.find { it.id == id } ?: return@launchOperation
        action(row)
    }
    fun deleteSource(id: String) = launchOperation {
        val snapshot = current ?: return@launchOperation; val row = snapshot.rows.find { it.id == id } ?: return@launchOperation
        if (snapshot.request.currentBookUrl != id) { content.deleteSource(row); currentCoroutineContext().ensureActive(); refreshCached(false); return@launchOperation }
        mutable.value = state.value.copy(changing = true)
        try {
            val type = originalType
            val candidates = searches.project(snapshot.request, snapshot.rows).filter { it.origin != row.origin && it.type == type }
            var replacement: ChapterSourceToc? = null
            for (candidate in candidates) {
                try { replacement = content.toc(candidate, snapshot.chapterIndex, snapshot.chapterTitle); break }
                catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { currentCoroutineContext().ensureActive() }
            }
            val receipt = content.change(session, replacement ?: error("没有有效源"), id)
            currentCoroutineContext().ensureActive(); durable(requireNotNull(current).copy(pendingReceipt = receipt.key))
        } finally { if (!stopped && currentCoroutineContext().isActive) mutable.value = state.value.copy(changing = false) }
    }
    fun completeSourceChange(receipt: ChapterSourceReceipt) {
        val id = receipt.deleteAfterId ?: return
        if (stopped || !deletedReceipts.add(receipt.key)) return
        val row = current?.rows?.find { it.id == id } ?: return
        viewModelScope.launch {
            try { content.deleteSource(row); currentCoroutineContext().ensureActive(); refreshCached(false) }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); failure(failure) }
        }
    }
}
