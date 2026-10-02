package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ContentEditorDraft
import io.legado.app.data.repository.ContentEditorRepository
import io.legado.app.data.repository.ContentEditorTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.regex.PatternSyntaxException

internal data class ContentEditorState(
    val raw: String = "", val text: String = "", val title: String = "",
    val hasDraft: Boolean = false, val hasChanges: Boolean = false,
    val loading: Boolean = true, val saving: Boolean = false, val finished: Boolean = false,
    val plainText: Boolean = false, val selectionStart: Int = 0, val selectionEnd: Int = 0,
    val scrollY: Int? = null, val searchVisible: Boolean = false, val query: String = "",
    val regex: Boolean = false, val matchCase: Boolean = false,
    val matches: List<IntRange> = emptyList(), val matchIndex: Int = -1,
    val searchInvalid: Boolean = false, val scrollRequest: Long = 0,
    val titleEditor: Boolean = false, val titleInput: String = "", val titleReady: Boolean = false, val error: String? = null,
) { val busy get() = saving || finished }

/** Large chapter text stays in an atomic disk checkpoint, never in SavedStateHandle. */
internal class ContentEditorViewModel(
    private val repository: ContentEditorRepository, private val saved: SavedStateHandle,
    val target: ContentEditorTarget, initialTitle: String? = null,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private fun <T> value(key: String, default: T): T = saved["contentEditor.$key"] ?: default
    private fun put(key: String, value: Any?) { saved["contentEditor.$key"] = value }
    val draftId: String = value("draftId", UUID.randomUUID().toString()).also { put("draftId", it) }
    private var revision = value("revision", 0L)
    private var baseline = ""
    private var restoredChanges = false
    private var rawStart = value("selectionStart", 0)
    private var rawEnd = value("selectionEnd", 0)
    private val mutable = MutableStateFlow(ContentEditorState(
        title = value("title", initialTitle.orEmpty()), finished = value("finished", false),
        scrollY = saved["contentEditor.scrollY"], searchVisible = value("searchVisible", false),
        query = value("query", ""), regex = value("regex", false), matchCase = value("matchCase", false),
        titleEditor = value("titleEditor", false), titleInput = value("titleInput", ""),
        titleReady = value("titleReady", false), saving = value("titleLoading", false),
    ))
    val state = mutable.asStateFlow()
    private val checkpointMutex = Mutex()
    private val preferencesMutex = Mutex()
    private val checkpoints = Channel<ContentEditorDraft>(Channel.CONFLATED)
    private val checkpointJob = viewModelScope.launch {
        for (draft in checkpoints) try {
            checkpointMutex.withLock { repository.writeDraft(draftId, draft) }
        } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message ?: "无法保存草稿") } }
    }
    private var loadJob: Job? = null
    private var pendingReset = false
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private inline fun update(block: ContentEditorState.() -> ContentEditorState) { mutable.value = mutable.value.block() }
    init {
        if (state.value.finished) update { copy(loading = false) }
        else {
            if (value("titleLoading", false) && state.value.titleEditor) fetchTitle()
            viewModelScope.launch {
            try {
                val plain = repository.plainText()
                val draft = repository.readDraft(draftId)
                update { copy(plainText = plain) }
                if (draft != null) {
                    check(draft.target.bookUrl == target.bookUrl && draft.target.chapterIndex == target.chapterIndex) { "草稿章节不匹配" }
                    revision = maxOf(revision, draft.revision); put("revision", revision)
                    baseline = draft.text; restoredChanges = draft.hasChanges
                    render(draft.text, draft.hasChanges); update { copy(loading = false) }; search(false)
                } else load(false)
            } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(loading = false, error = error.message ?: "无法读取草稿") } }
            }
        }
    }
    private fun render(raw: String, dirty: Boolean) {
        val projection = ContentEditProjection(raw)
        rawStart = rawStart.coerceIn(0, raw.length); rawEnd = rawEnd.coerceIn(0, raw.length)
        update { copy(raw = raw, text = if (plainText) projection.text else raw, hasDraft = true,
            hasChanges = dirty, selectionStart = if (plainText) projection.displayOffset(rawStart) else rawStart,
            selectionEnd = if (plainText) projection.displayOffset(rawEnd) else rawEnd) }
    }
    private fun draft() = ContentEditorDraft(target, state.value.raw, state.value.hasChanges, revision)
    private fun checkpoint() { if (state.value.hasDraft && !state.value.finished) checkpoints.trySend(draft()) }
    suspend fun flushDraft() {
        if (state.value.hasDraft && !state.value.finished) try {
            checkpointMutex.withLock { repository.writeDraft(draftId, draft()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            update { copy(error = error.message ?: "无法保存草稿") }
        }
    }
    fun edit(text: String, selectionStart: Int, selectionEnd: Int) {
        val current = state.value
        if (!current.hasDraft || current.busy) return
        val raw = if (!current.plainText) text else {
            var from = 0
            while (from < minOf(current.text.length, text.length) && current.text[from] == text[from]) from++
            var oldEnd = current.text.length; var newEnd = text.length
            while (oldEnd > from && newEnd > from && current.text[oldEnd - 1] == text[newEnd - 1]) { oldEnd--; newEnd-- }
            ContentEditProjection(current.raw).replace(from, oldEnd, text.substring(from, newEnd))
        }
        if (raw != current.raw) { revision++; put("revision", revision) }
        val projection = ContentEditProjection(raw)
        rawStart = if (current.plainText) projection.rawOffset(selectionStart) else selectionStart.coerceIn(0, raw.length)
        rawEnd = if (selectionStart == selectionEnd) rawStart else if (current.plainText) projection.rawOffset(selectionEnd, false) else selectionEnd.coerceIn(0, raw.length)
        put("selectionStart", rawStart); put("selectionEnd", rawEnd)
        render(raw, restoredChanges || raw != baseline)
        if (raw != current.raw) { checkpoint(); search(false) }
    }
    fun setScroll(y: Int) { if (!state.value.finished) { put("scrollY", y); update { copy(scrollY = y) } } }
    fun togglePlainText() {
        if (state.value.busy) return
        val plain = !state.value.plainText
        update { copy(plainText = plain) }; render(state.value.raw, state.value.hasChanges); search(false)
        viewModelScope.launch { try { preferencesMutex.withLock { repository.setPlainText(plain) } } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message) } } }
    }
    fun setSearchVisible(visible: Boolean) { put("searchVisible", visible); update { copy(searchVisible = visible) }; if (visible) search(true) }
    fun setQuery(query: String) { put("query", query); update { copy(query = query) }; search(true) }
    fun setRegex(value: Boolean) { put("regex", value); update { copy(regex = value) }; search(true) }
    fun setMatchCase(value: Boolean) { put("matchCase", value); update { copy(matchCase = value) }; search(true) }
    private fun search(navigate: Boolean) {
        searchJob?.cancel(); val generation = ++searchGeneration; val current = state.value
        searchJob = viewModelScope.launch {
            delay(120)
            try {
                val matches = withContext(searchDispatcher) { findContentMatches(current.text, current.query, current.regex, current.matchCase) { ensureActive() } }
                if (generation != searchGeneration) return@launch
                val index = if (matches.isEmpty()) -1 else matches.indexOfFirst { it.first >= current.selectionStart }.takeIf { it >= 0 } ?: 0
                update { copy(matches = matches, matchIndex = index, searchInvalid = false) }
                if (navigate && index >= 0) selectMatch(index)
            } catch (_: PatternSyntaxException) { if (generation == searchGeneration) update { copy(matches = emptyList(), matchIndex = -1, searchInvalid = true) } }
        }
    }
    fun nextMatch(direction: Int) { selectMatch(cycleContentMatchIndex(state.value.matchIndex, direction, state.value.matches.size)) }
    private fun selectMatch(index: Int) {
        val match = state.value.matches.getOrNull(index) ?: return
        edit(state.value.text, match.first, match.last + 1)
        update { copy(matchIndex = index, scrollRequest = scrollRequest + 1) }
    }
    fun reset() { if (!state.value.busy) load(true) }
    fun retryLoad() { if (!state.value.busy && !state.value.hasDraft) load(false) }
    private fun load(reset: Boolean) {
        if (loadJob?.isActive == true) { pendingReset = pendingReset || reset; return }
        val requestedRevision = revision
        update { copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val loaded = repository.load(target, reset)
                if (!state.value.finished) {
                    if (reset) put("reload", true)
                    if (requestedRevision == revision) {
                        revision++; put("revision", revision); baseline = loaded.text; restoredChanges = false
                        render(loaded.text, false); if (state.value.title.isEmpty()) { put("title", loaded.displayTitle); update { copy(title = loaded.displayTitle) } }
                        checkpoint(); search(false)
                    }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message ?: "无法读取正文") } }
            finally { update { copy(loading = false) }; loadJob = null; if (pendingReset && !state.value.finished) { pendingReset = false; load(true) } }
        }
    }
    fun openTitle() {
        if (state.value.busy || state.value.titleEditor) return
        put("titleEditor", true); put("titleReady", false)
        update { copy(titleEditor = true, titleReady = false) }; fetchTitle()
    }
    private fun fetchTitle() {
        put("titleLoading", true); update { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val title = repository.title(target)
                put("titleInput", title); put("titleReady", true)
                update { copy(titleInput = title, titleReady = true) }
            } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message) } }
            finally { put("titleLoading", false); update { copy(saving = false) } }
        }
    }
    fun editTitle(title: String) {
        if (!state.value.busy) {
            put("titleInput", title); put("titleReady", true)
            update { copy(titleInput = title, titleReady = true) }
        }
    }
    fun dismissTitle() { if (!state.value.busy) { put("titleEditor", false); update { copy(titleEditor = false) } } }
    fun saveTitle() {
        if (state.value.busy || !state.value.titleEditor || !state.value.titleReady) return
        val title = state.value.titleInput; update { copy(saving = true, error = null) }
        viewModelScope.launch {
            try { val label = repository.saveTitle(target, title); put("title", label); put("titleEditor", false); put("reload", true); update { copy(title = label, titleEditor = false) } }
            catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message ?: "无法保存标题") } }
            finally { update { copy(saving = false) } }
        }
    }
    fun save() = close(saveUnchanged = true)
    /** Native cancel/back has always saved a changed draft; await it so failure remains editable. */
    fun close(saveUnchanged: Boolean = false) {
        if (state.value.busy) return
        if (state.value.loading && !state.value.hasDraft) return
        update { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                while (loadJob != null) loadJob?.join()
                if (state.value.hasDraft && (saveUnchanged || state.value.hasChanges)) {
                    repository.save(target, state.value.raw); put("reload", true)
                }
                checkpointJob.cancel(); checkpointJob.join(); checkpointMutex.withLock { repository.deleteDraft(draftId) }
                put("finished", true); update { copy(finished = true) }
            } catch (error: Exception) { if (error is CancellationException) throw error; update { copy(error = error.message ?: "无法保存正文") } }
            finally { update { copy(saving = false) } }
        }
    }
    fun consumeReload(): Boolean = value("reload", false).also { if (it) put("reload", false) }
    fun copyText() = "${state.value.title}\n${state.value.text}"
}
