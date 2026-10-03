package io.legado.app.ui.book.cache

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.verificationField
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookCacheRow(val book: BookCacheItem, val cached: Set<String>? = null,
    val total: Int = book.total, val downloading: Boolean = false,
    val progress: Int? = null, val message: String? = null)
data class BookCacheSection(val ticket: String, val path: String, val all: Boolean = false,
    val size: String = "1", val scope: String = "", val name: String = "",
    val preview: String? = null, val invalidScope: Boolean = false, val revision: Long = 0)
data class BookCacheFolder(val ticket: String, val path: String?, val delivered: Boolean = false)
data class BookCacheSettings(val ticket: String, val kind: String, val draft: String, val revision: Long = 1)
enum class BookCacheIssue { NoBook, SettingsNotSaved, ExportInterrupted }
private class BookCacheSettingsNotSaved : Exception()
data class BookCacheState(val group: Long = -1, val groups: List<BookCacheGroup> = emptyList(),
    val rows: List<BookCacheRow> = emptyList(), val loading: Boolean = true,
    val running: Boolean = false, val closed: Boolean = false, val preferencesLoaded: Boolean = false, val busy: Boolean = false,
    val preferences: BookCachePreferences = BookCachePreferences(),
    val folder: BookCacheFolder? = null, val settings: BookCacheSettings? = null, val section: BookCacheSection? = null,
    val confirmAfterCurrent: Boolean? = null, val preferencesDirty: Boolean = false, val issue: BookCacheIssue? = null, val error: String? = null)

/** SavedState holds small UI drafts; export selections live in an owned disk ticket. */
class BookCacheViewModel(private val repository: BookCacheRepository,
    private val saved: SavedStateHandle, initialGroup: Long = -1) : ViewModel() {
    private val mutable = MutableStateFlow(BookCacheState(group = saved.get<Long>("cache.group") ?: initialGroup, closed = saved.get<Boolean>("cache.closed") == true))
    val state = mutable.asStateFlow()
    private var stopped = false
    private var booksJob: Job? = null
    private var scanJob: Job? = null
    private var generation = 0L
    private var previewJob: Job? = null
    private var runtimeGeneration = 0L
    private var preferenceRevision = 0L
    private var dispatchingTicket: String? = null
    private var processingFolder: String? = null
    private val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private data class PreferenceWrite(val value: BookCachePreferences, val fields: Set<BookCachePreference>, val done: CompletableDeferred<Unit>)
    private val writes = Channel<PreferenceWrite>(Channel.UNLIMITED)
    private var preferenceFence = CompletableDeferred(Unit)
    private val failedPreferences = mutableSetOf<BookCachePreference>()
    private val sectionWrites = Channel<BookCacheSection>(Channel.CONFLATED)
    private val counts = mutableMapOf<String, BookCacheScan>()
    private val savedDuringScan = mutableMapOf<String, Set<String>>()
    private var runtime = BookCacheRuntime(false, emptySet(), emptyMap(), emptyMap())
    init {
        if (state.value.closed) stop()
        scope.launch {
            for (write in writes) try {
                repository.preferences(write.value, write.fields)
                failedPreferences.removeAll(write.fields)
                publish { it.copy(preferencesDirty = failedPreferences.isNotEmpty()) }
                write.done.complete(Unit)
            } catch (error: Throwable) {
                failedPreferences.addAll(write.fields)
                publish { it.copy(preferencesDirty = true) }
                write.done.completeExceptionally(error)
                currentCoroutineContext().ensureActive(); publish { it.copy(error = error.localizedMessage ?: "ERROR") }
            }
        }
        scope.launch {
            for (section in sectionWrites) try {
                withContext(NonCancellable) { repository.writeSection(section.ticket, section.draft()) }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (state.value.section?.ticket == section.ticket || state.value.settings?.ticket == section.ticket) fail(error)
            }
        }
        scope.launch {
            try {
                val preferences = repository.preferences()
                currentCoroutineContext().ensureActive()
                publish { it.copy(preferences = preferences, preferencesLoaded = true) }
                restorePending()
                refresh()
                pendingFolderTicket()?.let { consumeFolderResult(it) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
        scope.launch {
            try { repository.groups().collect { groups -> publish { it.copy(groups = groups.toList()) } } }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
        observeBooks()
    }
    private fun publish(change: (BookCacheState) -> BookCacheState) { if (!stopped) mutable.value = change(mutable.value) }
    private fun fail(error: Throwable) = publish { it.copy(busy = false, loading = false, issue = if (error is BookCacheSettingsNotSaved) BookCacheIssue.SettingsNotSaved else null, error = if (error is BookCacheSettingsNotSaved) null else error.localizedMessage ?: "ERROR") }
    private fun observeBooks() {
        booksJob?.cancel(); scanJob?.cancel()
        val token = ++generation
        val group = state.value.group
        booksJob = scope.launch {
            try { repository.books(group).collect { books ->
                currentCoroutineContext().ensureActive()
                if (token != generation || stopped) return@collect
                val keep = books.map { it.key }.toSet()
                counts.keys.retainAll(keep); savedDuringScan.keys.retainAll(keep)
                publish { it.copy(rows = books.map { book -> row(book) }, loading = false, error = null) }
                scanJob?.cancel()
                scanJob = scope.launch {
                    try { for (book in books) {
                        if (book.local || counts.containsKey(book.key)) continue
                        val result = repository.scan(book.key)
                        currentCoroutineContext().ensureActive()
                        if (stopped || token != generation) return@launch
                        if (result != null) {
                            counts[book.key] = result.copy(chapters = (result.chapters + savedDuringScan[book.key].orEmpty()).toSet())
                            publish { it.copy(rows = it.rows.map { item -> if (item.book.key == book.key) row(item.book) else item }) }
                        }
                    } } catch (error: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (token == generation) fail(error)
                    }
                }
            } } catch (error: Throwable) { currentCoroutineContext().ensureActive(); if (token == generation) fail(error) }
        }
    }
    private fun row(book: BookCacheItem): BookCacheRow {
        val scan = counts[book.key]
        return BookCacheRow(book, scan?.chapters, scan?.total ?: book.total,
            book.key in runtime.downloading, runtime.exportProgress[book.key], runtime.exportMessages[book.key])
    }
    fun group(id: Long) {
        if (stopped || id == state.value.group) return
        saved["cache.group"] = id
        publish { it.copy(group = id, loading = true, rows = emptyList()) }
        counts.clear(); savedDuringScan.clear(); observeBooks()
    }
    fun chapterSaved(key: String, chapter: String) {
        if (stopped) return
        savedDuringScan[key] = savedDuringScan[key].orEmpty() + chapter
        counts[key]?.let { counts[key] = it.copy(chapters = it.chapters + chapter) }
        publish { it.copy(rows = it.rows.map { item -> if (item.book.key == key) row(item.book) else item }) }
    }
    fun refresh() {
        if (stopped) return
        val token = ++runtimeGeneration
        scope.launch {
            try {
                val value = repository.runtime()
                currentCoroutineContext().ensureActive()
                if (stopped || token != runtimeGeneration) return@launch
                runtime = value
                publish { it.copy(running = value.running, rows = it.rows.map { item -> row(item.book) }) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun preferences(change: (BookCachePreferences) -> BookCachePreferences) {
        if (stopped || !state.value.preferencesLoaded) return
        val old = state.value.preferences
        val value = change(old)
        val fields = buildSet {
            if (old.replace != value.replace) add(BookCachePreference.Replace)
            if (old.custom != value.custom) add(BookCachePreference.Custom)
            if (old.noChapterName != value.noChapterName) add(BookCachePreference.NoChapterName)
            if (old.webDav != value.webDav) add(BookCachePreference.WebDav)
            if (old.pictures != value.pictures) add(BookCachePreference.Pictures)
            if (old.parallel != value.parallel) add(BookCachePreference.Parallel)
            if (old.type != value.type) add(BookCachePreference.Type)
            if (old.charset != value.charset) add(BookCachePreference.Charset)
            if (old.fileName != value.fileName) add(BookCachePreference.FileName)
            if (old.episodeFileName != value.episodeFileName) add(BookCachePreference.EpisodeFileName)
        }
        if (fields.isEmpty()) return
        preferenceRevision++
        publish { it.copy(preferences = value) }
        val done = CompletableDeferred<Unit>(); preferenceFence = done
        writes.trySend(PreferenceWrite(value, fields, done))
    }
    private fun operation(onFinally: () -> Unit = {}, block: suspend () -> Unit) {
        if (stopped || state.value.busy) return
        publish { it.copy(busy = true, error = null, issue = null) }
        scope.launch {
            try {
                preferenceFence.await()
                if (failedPreferences.isNotEmpty()) throw BookCacheSettingsNotSaved()
                block(); currentCoroutineContext().ensureActive()
            }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
            finally { onFinally(); publish { it.copy(busy = false) } }
        }
    }
    fun download(afterCurrent: Boolean) {
        if (state.value.running) operation { repository.stopDownloads(); refresh() }
        else { saved["cache.confirm"] = afterCurrent; publish { it.copy(confirmAfterCurrent = afterCurrent) } }
    }
    fun cancelDownloadConfirmation() { saved.remove<Boolean>("cache.confirm"); publish { it.copy(confirmAfterCurrent = null) } }
    fun confirmDownload() {
        val after = state.value.confirmAfterCurrent ?: return
        val keys = state.value.rows.map { it.book.key }
        cancelDownloadConfirmation()
        operation { repository.download(keys, after); refresh() }
    }
    fun toggleDownload(key: String) = operation { repository.toggleDownload(key); refresh() }
    fun export(key: String? = null, folderOnly: Boolean = false) {
        if (!state.value.preferencesLoaded || state.value.folder != null || state.value.section != null) return
        val keys = if (folderOnly) emptyList() else if (key != null) listOf(key) else state.value.rows.map { it.book.key }
        if (!folderOnly && keys.isEmpty()) { publish { it.copy(issue = BookCacheIssue.NoBook, error = null) }; return }
        operation {
            var created: String? = null
            try {
                val ticket = withContext(NonCancellable) { repository.stage(keys).also { created = it } }
                currentCoroutineContext().ensureActive()
                val path = repository.cachedPath()
                if (path != null) repository.writeSection(ticket, BookCacheSectionDraft(path, false, "1", "", "", 0))
                // Old export-all uses its saved folder directly; single-book checks write access.
                val needFolder = folderOnly || path.isNullOrEmpty() || (key != null && !repository.writable(path))
                currentCoroutineContext().ensureActive()
                saved["cache.ticket"] = ticket
                saved["cache.customAllowed"] = key != null || folderOnly
                created = null
                if (needFolder) {
                    saved["cache.folder"] = true; saved["cache.folderDelivered"] = false
                    publish { it.copy(folder = BookCacheFolder(ticket, path)) }
                } else continueExport(ticket, path!!, customAllowed = key != null)
                created = null
            } finally { created?.let { withContext(NonCancellable) { repository.release(it) } } }
        }
    }
    fun consumeFolder(ticket: String): Boolean {
        val folder = state.value.folder ?: return false
        if (folder.ticket != ticket || folder.delivered || stopped) return false
        saved["cache.folderDelivered"] = true
        publish { it.copy(folder = folder.copy(delivered = true)) }
        return true
    }
    fun pendingFolderTicket(): String? = saved.get<String>("cache.ticket").takeIf {
        saved.get<Boolean>("cache.folder") == true && !state.value.closed
    }
    fun folderResult(path: String?) { pendingFolderTicket()?.let { folderResult(it, path) } }
    fun folderResult(ticket: String, path: String?) {
        if (stopped || ticket != pendingFolderTicket()) return
        scope.launch {
            try {
                withContext(NonCancellable) { repository.folderResult(ticket, BookCacheFolderResult(path)) }
                currentCoroutineContext().ensureActive()
                state.first { it.closed || (it.preferencesLoaded && !it.busy) }
                currentCoroutineContext().ensureActive()
                consumeFolderResult(ticket)
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    private suspend fun consumeFolderResult(ticket: String) {
        if (ticket != pendingFolderTicket() || processingFolder == ticket) return
        val result = repository.folderResult(ticket) ?: return
        currentCoroutineContext().ensureActive()
        state.first { it.closed || !it.busy }
        currentCoroutineContext().ensureActive()
        if (ticket != pendingFolderTicket() || processingFolder == ticket) return
        processingFolder = ticket
        operation(onFinally = { processingFolder = null }) {
            try {
                if (result.path.isNullOrEmpty()) clearPending(ticket)
                else {
                    repository.rememberPath(result.path)
                    currentCoroutineContext().ensureActive()
                    saved["cache.folder"] = false
                    publish { it.copy(folder = null) }
                    continueExport(ticket, result.path, customAllowed = true)
                }
            } finally { processingFolder = null }
        }
    }
    private suspend fun continueExport(ticket: String, path: String, customAllowed: Boolean) {
        val revision = (repository.readSection(ticket)?.revision ?: 0) + 1
        val draft = BookCacheSection(ticket, path, name = state.value.preferences.episodeFileName.orEmpty(), revision = revision)
        repository.writeSection(ticket, draft.draft())
        currentCoroutineContext().ensureActive()
        if (customAllowed && state.value.preferences.customEpub) {
            saved["cache.section"] = true
            publish { it.copy(section = draft) }
        } else {
            val keys = repository.staged(ticket)
            currentCoroutineContext().ensureActive()
            dispatchingTicket = ticket
            try { withContext(NonCancellable) {
                repository.export(BookCacheExport(keys, path, state.value.preferences.exportType))
                clearPending(ticket)
            } } finally {
                dispatchingTicket = null
                if (state.value.closed) withContext(NonCancellable) { runCatching { repository.release(ticket) } }
            }

        }
    }
    fun section(all: Boolean? = null, size: String? = null, scope: String? = null, name: String? = null) {
        val section = state.value.section ?: return
        val next = section.copy(all = all ?: section.all, size = size ?: section.size,
            scope = scope ?: section.scope, name = name ?: section.name, preview = null, invalidScope = false, revision = section.revision + 1)
        sectionWrites.trySend(next)
        previewJob?.cancel()
        publish { it.copy(section = next) }
    }
    fun previewEpisodeName() {
        val section = state.value.section ?: return
        previewJob?.cancel()
        previewJob = scope.launch {
            try {
                val key = repository.staged(section.ticket).singleOrNull()
                val preview = key?.let { repository.episodeName(it, section.name) }
                currentCoroutineContext().ensureActive()
                if (state.value.section == section) publish { it.copy(section = section.copy(preview = preview ?: "Error")) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun rememberEpisodeName() {
        val section = state.value.section ?: return
        scope.launch {
            try {
                val valid = repository.validEpisodeName(section.name)
                currentCoroutineContext().ensureActive()
                if (valid && state.value.section?.name == section.name) preferences { it.copy(episodeFileName = section.name) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun confirmSection() {
        val section = state.value.section ?: return
        if (!section.all && !verificationField(section.scope)) {
            publish { it.copy(section = section.copy(invalidScope = true)) }; return
        }
        operation {
            val keys = repository.staged(section.ticket)
            currentCoroutineContext().ensureActive()
            // Custom sections were available only for a single row, including after folder selection.
            dispatchingTicket = section.ticket
            try { withContext(NonCancellable) {
                if (section.all) repository.export(BookCacheExport(keys, section.path, state.value.preferences.exportType))
                else if (keys.size == 1) repository.export(BookCacheExport(keys, section.path, "epub", section.size.toIntOrNull() ?: 1, section.scope))
                clearPending(section.ticket)
            } } finally {
                dispatchingTicket = null
                if (state.value.closed) withContext(NonCancellable) { runCatching { repository.release(section.ticket) } }
            }
        }
    }
    fun cancelSection() {
        val section = state.value.section ?: return
        previewJob?.cancel()
        operation { clearPending(section.ticket) }
    }
    private suspend fun clearPending(ticket: String) {
        for (key in pendingKeys) saved.remove<Any>(key)
        publish { it.copy(folder = null, section = null) }
        withContext(NonCancellable) { repository.release(ticket) }
    }
    private suspend fun restorePending() {
        val ticket = saved.get<String>("cache.ticket")
        if (ticket != null && saved.get<Boolean>("cache.folder") == true) {
            val path = repository.readSection(ticket)?.path
            val returned = repository.folderResult(ticket) != null
            currentCoroutineContext().ensureActive()
            if (returned) saved["cache.folderDelivered"] = true
            publish { it.copy(folder = BookCacheFolder(ticket, path, returned || saved.get<Boolean>("cache.folderDelivered") == true)) }
        }
        if (ticket != null && saved.get<Boolean>("cache.section") == true) {
            val draft = repository.readSection(ticket) ?: error("Export draft is missing")
            currentCoroutineContext().ensureActive()
            publish { it.copy(section = BookCacheSection(ticket, draft.path, draft.all, draft.size, draft.scope, draft.name, revision = draft.revision)) }
        }
        saved.get<String>("cache.settingsTicket")?.let { settingsTicket ->
            val draft = repository.readSection(settingsTicket) ?: error("Export settings draft is missing")
            currentCoroutineContext().ensureActive()
            publish { it.copy(settings = BookCacheSettings(settingsTicket, draft.path, draft.name, draft.revision)) }
        }
        publish { it.copy(confirmAfterCurrent = saved.get<Boolean>("cache.confirm")) }
        if (ticket != null && state.value.folder == null && state.value.section == null) publish {
            it.copy(issue = BookCacheIssue.ExportInterrupted, error = null)
        }
    }
    fun retryExport() {
        val ticket = saved.get<String>("cache.ticket") ?: return
        if (state.value.folder != null || state.value.section != null) return
        operation {
            val path = repository.folderResult(ticket)?.path ?: repository.readSection(ticket)?.path ?: error("Export folder is missing")
            currentCoroutineContext().ensureActive()
            continueExport(ticket, path, saved.get<Boolean>("cache.customAllowed") == true)
        }
    }
    fun openSettings(kind: String) {
        if (kind !in setOf("name", "charset") || !state.value.preferencesLoaded || state.value.settings != null) return
        val draft = if (kind == "name") state.value.preferences.fileName.orEmpty() else state.value.preferences.charset
        operation {
            var created: String? = null
            try {
                val ticket = withContext(NonCancellable) { repository.stage(emptyList()).also { created = it } }
                val settings = BookCacheSettings(ticket, kind, draft)
                repository.writeSection(ticket, settings.section().draft())
                currentCoroutineContext().ensureActive()
                saved["cache.settingsTicket"] = ticket
                publish { it.copy(settings = settings) }
                created = null
            } finally { created?.let { withContext(NonCancellable) { repository.release(it) } } }
        }
    }
    fun settings(text: String) {
        val settings = state.value.settings ?: return
        val next = settings.copy(draft = text, revision = settings.revision + 1)
        publish { it.copy(settings = next) }
        sectionWrites.trySend(next.section())
    }
    private fun BookCacheSettings.section() = BookCacheSection(ticket, kind, name = draft, revision = revision)
    fun confirmSettings() {
        val settings = state.value.settings ?: return
        preferences { if (settings.kind == "name") it.copy(fileName = settings.draft) else it.copy(charset = settings.draft) }
        cancelSettings()
    }
    fun cancelSettings() {
        val settings = state.value.settings ?: return
        saved.remove<String>("cache.settingsTicket")
        publish { it.copy(settings = null) }
        scope.launch { withContext(NonCancellable) { runCatching { repository.release(settings.ticket) } } }
    }
    fun refreshPreferences() {
        if (stopped) return
        val revision = preferenceRevision
        scope.launch {
            try {
                preferenceFence.await()
                val preferences = repository.preferences()
                currentCoroutineContext().ensureActive()
                if (revision == preferenceRevision && failedPreferences.isEmpty()) publish { it.copy(preferences = preferences, preferencesLoaded = true) }
            } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun abandonFolder() {
        val folder = state.value.folder ?: return
        scope.launch {
            try { clearPending(folder.ticket) }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun close() {
        if (state.value.closed) return
        val ticket = saved.get<String>("cache.ticket")
        val settingsTicket = saved.remove<String>("cache.settingsTicket")
        saved["cache.closed"] = true
        for (key in pendingKeys) saved.remove<Any>(key)
        publish { it.copy(closed = true, folder = null, section = null, settings = null) }
        stop()
        if (ticket != null && ticket != dispatchingTicket) viewModelScope.launch(NonCancellable) { runCatching { repository.release(ticket) } }
        if (settingsTicket != null) viewModelScope.launch(NonCancellable) { runCatching { repository.release(settingsTicket) } }
    }
    private fun BookCacheSection.draft() = BookCacheSectionDraft(path, all, size, scope, name, revision)
    suspend fun flushSection() {
        val section = state.value.section; val settings = state.value.settings
        withContext(NonCancellable) {
            section?.let { repository.writeSection(it.ticket, it.draft()) }
            settings?.let { repository.writeSection(it.ticket, it.section().draft()) }
        }
    }
    fun retryPreferences() {
        if (stopped || failedPreferences.isEmpty()) return
        val done = CompletableDeferred<Unit>(); preferenceFence = done
        writes.trySend(PreferenceWrite(state.value.preferences, failedPreferences.toSet(), done))
        scope.launch {
            try { done.await(); currentCoroutineContext().ensureActive(); pendingFolderTicket()?.let { consumeFolderResult(it) } }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) }
        }
    }
    fun retry() {
        when {
            state.value.preferencesDirty -> retryPreferences()
            state.value.section != null -> confirmSection()
            pendingFolderTicket() != null -> scope.launch { try { consumeFolderResult(pendingFolderTicket() ?: return@launch) } catch (error: Throwable) { currentCoroutineContext().ensureActive(); fail(error) } }
            saved.get<String>("cache.ticket") != null -> retryExport()
            else -> { clearError(); observeBooks(); refreshPreferences(); refresh() }
        }
    }
    fun clearError() = publish { it.copy(error = null, issue = null) }
    fun stop() {
        if (stopped) return
        stopped = true; generation++; booksJob?.cancel(); scanJob?.cancel(); previewJob?.cancel()
        state.value.section?.let { section -> viewModelScope.launch(NonCancellable) { runCatching { repository.writeSection(section.ticket, section.draft()) } } }
        state.value.settings?.let { settings -> viewModelScope.launch(NonCancellable) { runCatching { repository.writeSection(settings.ticket, settings.section().draft()) } } }
        scope.cancel(); writes.close(); sectionWrites.close()
    }
    override fun onCleared() { stop() }
    private companion object {
        val pendingKeys = listOf("cache.ticket", "cache.customAllowed", "cache.folder", "cache.folderDelivered",
            "cache.section")
    }
}
