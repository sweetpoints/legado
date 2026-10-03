package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.help.book.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class TocHostEffectKind { Regex, Log, PickJson, PickMarkdown, ExportSuccess, ExportFailure }
data class TocHostEffect(val kind: TocHostEffectKind, val nonce: String = UUID.randomUUID().toString(), val requestCode: Int = 0)
data class TocHostState(val loaded: Boolean = false, val noBook: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val localText: Boolean = false, val reverse: Boolean = false, val expanded: Boolean = true, val split: Boolean = false,
    val useReplace: Boolean = false, val countWords: Boolean = false, val chapterRevision: Long = 0,
    val resetCollapse: Boolean = false, val replaceAll: Boolean = false, val pending: TocHostEffect? = null)
class TocHostViewModel(private val repository: TocHostRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(TocHostState(pending = saved.get<String>("tocHost.effect")?.let { GSON.fromJsonObject<TocHostEffect>(it).getOrNull() }))
    val state: StateFlow<TocHostState> = mutable
    private var book: Book? = null
    private val expandedWrites = Mutex()
    private var generation = 0L; private var loading: Job? = null
    private var requestedUrl: String? = null
    private var nextCode = saved.get<Int>("tocHost.nextCode") ?: 0
    fun snapshot(): Book? = book?.let(::ownedTocBook)
    fun load(url: String) {
        if (requestedUrl == url && (state.value.loaded || loading?.isActive == true)) return
        val owner = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        if (saved.get<String>("tocHost.owner")?.let { it != owner } == true) {
            saved.remove<String>("tocHost.effect"); saved.remove<Int>("tocHost.awaitCode"); saved.remove<Boolean>("tocHost.awaitMarkdown")
            mutable.value = state.value.copy(pending = null)
        }
        saved["tocHost.owner"] = owner
        requestedUrl = url; val current = ++generation; loading?.cancel(); book = null
        mutable.value = state.value.copy(loaded = false, noBook = false, busy = false, error = null)
        loading = viewModelScope.launch {
            try {
                val value = repository.load(url); currentCoroutineContext().ensureActive()
                if (generation != current) return@launch
                if (value == null) { mutable.value = state.value.copy(noBook = true); return@launch }
                book = ownedTocBook(value); project(loaded = true, refresh = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation) failed(error.localizedMessage ?: "Error") }
        }
    }
    private fun project(loaded: Boolean = state.value.loaded, refresh: Boolean = false, reset: Boolean = false, replace: Boolean = false) {
        val book = book ?: return; val preferences = repository.preferences()
        mutable.value = state.value.copy(loaded = loaded, localText = book.isLocalTxt,
            reverse = if (book.isEpub || book.isPdf) book.getReverseToc() else book.getReverseTocDisplay(),
            expanded = book.getTocExpanded(), split = book.getSplitLongChapter(), useReplace = preferences.first, countWords = preferences.second,
            chapterRevision = if (refresh) state.value.chapterRevision + 1 else state.value.chapterRevision,
            resetCollapse = reset, replaceAll = replace)
    }
    fun reverse() = mutate { current -> repository.reverse(current).also { repository.synchronizeReverse(it) } }
    fun expanded() {
        if (!canMutate()) return; val current = snapshot()!!; val expanded = !current.getTocExpanded()
        current.setTocExpanded(expanded); book = current; repository.synchronizeExpanded(current.bookUrl, expanded)
        project(refresh = true, reset = true, replace = true)
        val owner = generation
        // Accept durable configuration persistence before returning from the user action.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) { expandedWrites.withLock { repository.expanded(current.bookUrl, expanded) } }; currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (owner == generation) failed(error.localizedMessage ?: "Error") }
        }
    }
    fun useReplace() { if (!canMutate()) return; repository.useReplace(!state.value.useReplace); project(refresh = true, replace = true) }
    fun countWords() { if (!canMutate()) return; repository.countWords(!state.value.countWords); project(refresh = true) }
    fun split() { if (!canMutate() || !state.value.localText) return; mutate(readerUpdate = true) { current -> current.setSplitLongChapter(!current.getSplitLongChapter()); repository.rebuild(current) } }
    fun regex(value: String) { if (!canMutate() || !state.value.localText) return; mutate(readerUpdate = true) { current -> current.tocUrl = value; repository.rebuild(current) } }
    private fun mutate(readerUpdate: Boolean = false, block: suspend (Book) -> Book) {
        if (!canMutate()) return; val value = snapshot()!!; val current = generation
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = block(value); currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                if (readerUpdate) repository.readerMessage(value, null)
                book = ownedTocBook(result); mutable.value = state.value.copy(busy = false); project(refresh = true, reset = true, replace = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation) { if (readerUpdate) repository.readerMessage(value, error); mutable.value = state.value.copy(busy = false); failed(error.localizedMessage ?: "Error") } }
        }
    }
    private fun canMutate(): Boolean = state.value.loaded && !state.value.busy
    fun effect(kind: TocHostEffectKind) {
        if (!canMutate() || state.value.pending != null || saved.get<Int>("tocHost.awaitCode") != null) return
        if (kind == TocHostEffectKind.Regex && !state.value.localText) return
        val request = if (kind == TocHostEffectKind.PickJson || kind == TocHostEffectKind.PickMarkdown) ++nextCode else 0
        saved["tocHost.nextCode"] = nextCode
        pending(TocHostEffect(kind, requestCode = request))
    }
    private fun pending(value: TocHostEffect) { saved["tocHost.effect"] = GSON.toJson(value); mutable.value = state.value.copy(pending = value) }
    fun delivered(nonce: String): TocHostEffect? {
        val value = state.value.pending?.takeIf { it.nonce == nonce } ?: return null
        if (value.kind == TocHostEffectKind.PickJson || value.kind == TocHostEffectKind.PickMarkdown) {
            saved["tocHost.awaitCode"] = value.requestCode; saved["tocHost.awaitMarkdown"] = value.kind == TocHostEffectKind.PickMarkdown
        }
        saved.remove<String>("tocHost.effect"); mutable.value = state.value.copy(pending = null); return value
    }
    fun picked(requestCode: Int, directory: String?) {
        if (saved.get<Int>("tocHost.awaitCode") != requestCode) return
        if (!state.value.loaded && loading?.isActive == true) {
            viewModelScope.launch { loading?.join(); currentCoroutineContext().ensureActive(); picked(requestCode, directory) }
            return
        }
        val markdown = saved.get<Boolean>("tocHost.awaitMarkdown") == true
        saved.remove<Int>("tocHost.awaitCode"); saved.remove<Boolean>("tocHost.awaitMarkdown")
        val current = snapshot() ?: return; if (directory == null) return; val owner = generation
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { repository.export(current, directory, markdown); currentCoroutineContext().ensureActive()
                if (owner == generation) { mutable.value = state.value.copy(busy = false); pending(TocHostEffect(TocHostEffectKind.ExportSuccess)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (owner == generation) { mutable.value = state.value.copy(busy = false); failed(error.localizedMessage ?: "Error"); pending(TocHostEffect(TocHostEffectKind.ExportFailure)) } }
        }
    }
    fun retry() { val url = requestedUrl ?: return; if (!state.value.loaded) load(url) else mutable.value = state.value.copy(error = null) }
    fun failed(value: String) { mutable.value = state.value.copy(error = value) }
    fun stop() { viewModelScope.cancel() }
}
