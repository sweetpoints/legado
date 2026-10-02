package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.help.findTextRanges
import io.legado.app.help.parseHelpSections
import io.legado.app.ui.components.markdown.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TextDialogSection(val title: String, val content: String, val depth: Int)
data class TextDialogMatchPart(val leaf: Int, val start: Int, val end: Int)
data class TextDialogMatch(val parts: List<TextDialogMatchPart>)
data class TextDialogScroll(val id: Long, val y: Int? = null, val leaf: Int? = null, val offset: Int = 0)
data class TextDialogState(val request: TextDialogRequest? = null, val document: RichDocument = RichDocument(emptyList()),
    val sections: List<TextDialogSection> = emptyList(), val selectedSection: Int = 0, val loading: Boolean = true,
    val error: String? = null, val searchVisible: Boolean = false, val query: String = "", val matches: List<TextDialogMatch> = emptyList(),
    val matchIndex: Int = -1, val tocVisible: Boolean = false, val remaining: Long = 0, val scroll: TextDialogScroll? = null,
    val editPending: Boolean = false, val finished: Boolean = false, val searching: Boolean = false) {
    val help get() = request?.let { it.showToc && it.mode == "MD" } == true
    val canCancel get() = !loading && remaining <= 0
}
class TextDialogViewModel(private val repository: TextDialogRequestRepository, private val saved: SavedStateHandle,
    private val requestId: String, private val now: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val mutable = MutableStateFlow(TextDialogState(selectedSection = saved["text.section"] ?: 0,
        searchVisible = saved["text.search"] ?: false, query = saved["text.query"] ?: "", matchIndex = saved["text.index"] ?: -1,
        editPending = saved["text.edit"] ?: false, finished = saved["text.finished"] ?: false))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null; private var documentJob: Job? = null; private var timer: Job? = null; private var matchJob: Job? = null
    private var matchRevision = 0L
    private var scrollId = saved.get<Long>("text.scrollId") ?: 0L
    private var deadline = saved.get<Long>("text.deadline")
    private var revision = 0L
    init { if (!state.value.finished) load() else mutable.value = state.value.copy(loading = false) }
    private fun persist() {
        saved["text.section"] = state.value.selectedSection; saved["text.search"] = state.value.searchVisible
        saved["text.query"] = state.value.query; saved["text.index"] = state.value.matchIndex
        saved["text.finished"] = state.value.finished; saved["text.edit"] = state.value.editPending; saved["text.scrollId"] = scrollId
    }
    fun load() {
        if (state.value.finished || loadJob?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val request = repository.load(requestId)
                if (state.value.finished) return@launch
                val sections = withContext(compute) { if (request.showToc && request.mode == "MD") parseHelpSections(request.content).map { TextDialogSection(it.title, it.markdown, it.depth) } else emptyList() }
                val selected = if (state.value.query.isNotBlank()) 0 else state.value.selectedSection.coerceIn(0, sections.size)
                mutable.value = state.value.copy(request = request, sections = sections, selectedSection = selected)
                if (deadline == null) { deadline = now() + request.time.coerceAtLeast(0); saved["text.deadline"] = deadline }
                startTimer(); if (!state.value.finished) render(saved.get<Int>("text.scrollY") ?: 0, true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    private fun startTimer() {
        timer?.cancel(); tick()
        if (state.value.finished) return
        timer = viewModelScope.launch { while (!state.value.finished && (deadline ?: now()) > now()) {
            delay(minOf(1000L, ((deadline ?: now()) - now()).coerceAtLeast(1))); tick()
        } }
    }
    fun tick() {
        val remaining = ((deadline ?: now()) - now()).coerceAtLeast(0)
        mutable.value = state.value.copy(remaining = remaining)
        val request = state.value.request
        if (remaining == 0L && request?.autoClose == true && request.time > 0) close()
    }
    private fun render(y: Int = 0, keepIndex: Boolean = false) {
        documentJob?.cancel(); val token = ++revision
        mutable.value = state.value.copy(loading = true, error = null, scroll = null)
        val request = state.value.request ?: return
        val content = state.value.sections.getOrNull(state.value.selectedSection - 1)?.content ?: request.content
        documentJob = viewModelScope.launch {
            try {
                val document = withContext(compute) { projectTextDocument(content, request.mode) }
                if (token != revision || state.value.finished) return@launch
                mutable.value = state.value.copy(document = document, loading = false); updateMatches(keepIndex)
                if (state.value.query.isBlank()) scroll(y = y)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (token == revision) mutable.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun section(index: Int) {
        if (!state.value.help || index !in 0..state.value.sections.size || state.value.loading) return
        mutable.value = state.value.copy(tocVisible = false)
        if (index == state.value.selectedSection) return
        mutable.value = state.value.copy(selectedSection = index, query = if (index != 0) "" else state.value.query, matchIndex = -1)
        saved["text.scrollY"] = 0; persist(); render()
    }
    fun toc(show: Boolean) { if (state.value.help && !state.value.loading) mutable.value = state.value.copy(tocVisible = show) }
    fun toggleSearch() { if (state.value.help && !state.value.loading) { mutable.value = state.value.copy(searchVisible = !state.value.searchVisible); persist() } }
    fun query(value: String) {
        if (!state.value.help || state.value.finished) return
        mutable.value = state.value.copy(query = value, matchIndex = -1)
        if (value.isNotBlank() && state.value.selectedSection != 0) {
            mutable.value = state.value.copy(selectedSection = 0); saved["text.scrollY"] = 0; render()
        } else if (!state.value.loading) updateMatches()
        persist()
    }
    private fun updateMatches(keepIndex: Boolean = false) {
        matchJob?.cancel(); val token = ++matchRevision; val document = state.value.document; val query = state.value.query
        val previous = state.value.matchIndex
        mutable.value = state.value.copy(searching = true, matches = emptyList())
        matchJob = viewModelScope.launch {
            val matches = withContext(compute) {
                val leaves = document.leaves; val ranges = findTextRanges(document.text, query)
                val starts = mutableMapOf<Int, Int>(); var offset = 0
                leaves.forEach { starts[it.id] = offset; offset += it.text.length + 1 }
                ranges.map { range -> TextDialogMatch(leaves.mapNotNull { leaf ->
                    val start = checkNotNull(starts[leaf.id]); val left = maxOf(start, range.first); val right = minOf(start + leaf.text.length, range.last + 1)
                    if (left < right) TextDialogMatchPart(leaf.id, left - start, right - start) else null
                }) }
            }
            if (token != matchRevision || state.value.finished || state.value.document !== document || state.value.query != query) return@launch
            val index = if (matches.isEmpty()) -1 else if (keepIndex && previous in matches.indices) previous else 0
            mutable.value = state.value.copy(matches = matches, matchIndex = index, searching = false); persist(); currentMatchScroll()
        }
    }
    fun moveMatch(delta: Int) {
        if (state.value.loading || state.value.searching || state.value.matches.isEmpty()) return
        val size = state.value.matches.size; mutable.value = state.value.copy(matchIndex = ((state.value.matchIndex + delta) % size + size) % size)
        persist(); currentMatchScroll()
    }
    private fun currentMatchScroll() { state.value.matches.getOrNull(state.value.matchIndex)?.parts?.firstOrNull()?.let { scroll(leaf = it.leaf, offset = it.start) } }
    private fun scroll(y: Int? = null, leaf: Int? = null, offset: Int = 0) { mutable.value = state.value.copy(scroll = TextDialogScroll(++scrollId, y, leaf, offset)); persist() }
    fun scrolled(id: Long, y: Int) {
        if (state.value.scroll?.id != id) return
        saved["text.scrollY"] = y; mutable.value = state.value.copy(scroll = null)
    }
    fun position(y: Int) { if (!state.value.loading && state.value.scroll == null) saved["text.scrollY"] = y }
    fun edit() { if (!state.value.loading && !state.value.finished) { mutable.value = state.value.copy(editPending = true); persist() } }
    fun consumeEdit(): TextDialogRequest? {
        if (!state.value.editPending || state.value.request == null) return null
        mutable.value = state.value.copy(editPending = false); persist(); return state.value.request
    }
    fun close() { if (state.value.finished) return; loadJob?.cancel(); documentJob?.cancel(); matchJob?.cancel(); timer?.cancel(); mutable.value = state.value.copy(finished = true, loading = false); persist() }
    fun stop() { loadJob?.cancel(); documentJob?.cancel(); matchJob?.cancel(); timer?.cancel() }
    override fun onCleared() { stop() }
}
