package io.legado.app.ui.rss.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class RssReaderAction { Refresh, Favorite, Share, Speech, Login, Browser, ReadRecords, EditSource, Log }
data class RssReaderEffect(val action: RssReaderAction, val nonce: String = UUID.randomUUID().toString())
data class RssReaderState(val loaded: Boolean = false, val busy: Boolean = false, val missingOrigin: Boolean = false,
    val title: String = "", val progress: Int = 0, val article: Boolean = false, val favorite: Boolean = false,
    val canLogin: Boolean = false, val speaking: Boolean = false, val menu: Boolean = false,
    val error: String? = null, val pending: RssReaderEffect? = null, val document: Long = 0)

/** Owns immutable render projections and private snapshots; Android hosts receive exact small receipts. */
class RssReaderViewModel(private val repository: RssReaderRepository,
    private val sessions: RssReaderSessionRepository, private val speech: RssReaderSpeechRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val session = saved.get<String>("rssReader.session") ?: UUID.randomUUID().toString().also { saved["rssReader.session"] = it }
    private val mutable = MutableStateFlow(RssReaderState(menu = saved.get<Boolean>("rssReader.menu") == true,
        pending = saved.get<String>("rssReader.action")?.let { action -> runCatching {
            RssReaderEffect(RssReaderAction.valueOf(action), saved.get<String>("rssReader.nonce") ?: UUID.randomUUID().toString())
        }.getOrNull() }))
    val state: StateFlow<RssReaderState> = mutable
    @Volatile private var owned: RssReaderSnapshot? = null
    private var request: RssReaderRequest? = null
    private var acceptedLaunchTicket: String? = null
    private var pendingLaunch: String? = null
    private var launchRepository: RssReaderLaunchRepository? = null
    private var revision = saved.get<Long>("rssReader.revision") ?: 0L
    private var nextDocument = saved.get<Long>("rssReader.nextDocument") ?: 0L
    private var generation = 0L
    private var kernel: String? = null
    private var loading: Job? = null
    private var speechJob: Job? = null
    private var currentUrl: String? = null; private var currentTitle: String? = null
    private var favoriteOwner = saved.get<String>("rssReader.favoriteOwner")
    init { viewModelScope.launch { speech.speaking.collect { mutable.value = state.value.copy(speaking = it) } } }
    fun snapshot(): RssReaderSnapshot? = owned?.let(::clone)
    private fun clone(value: RssReaderSnapshot) = value.copy(source = value.source?.copy(), article = value.article?.copy(),
        favorite = value.favorite?.copy(), headers = value.headers.toMap(), document = when (val document = value.document) {
            is RssReaderDocument.Url -> document.copy(headers = document.headers.toMap())
            else -> document
        })
    fun bind(value: RssReaderRequest? = null) = bindInput(value, null, null)
    fun bindPrepared(ticket: String, launches: RssReaderLaunchRepository) {
        if (acceptedLaunchTicket == ticket && (state.value.loaded || loading?.isActive == true) || pendingLaunch == ticket && loading?.isActive == true) return
        bindInput(null, ticket, launches)
    }
    private fun bindInput(value: RssReaderRequest?, ticket: String?, launches: RssReaderLaunchRepository?) {
        if (ticket == null && value == null && (state.value.loaded || loading?.isActive == true)) return
        val replacedTicket = pendingLaunch?.takeUnless { it == ticket }
        val replacedRepository = launchRepository
        if (replacedTicket != null) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { replacedRepository?.release(replacedTicket) }
        }
        pendingLaunch = ticket; launchRepository = launches
        if (value != null) request = value
        loading?.cancel(); speechJob?.cancel(); saved.remove<String>("rssReader.speechNonce"); val current = ++generation
        mutable.value = state.value.copy(loaded = false, busy = false, error = null, missingOrigin = false, progress = 0, document = 0)
        loading = viewModelScope.launch {
            try {
                val disk = sessions.read(session); currentCoroutineContext().ensureActive(); if (current != generation) return@launch
                revision = maxOf(revision, disk?.revision ?: 0)
                val previous = owned?.request ?: disk?.request
                val prepared = if (ticket != null) {
                    if (disk?.acceptedLaunchTicket == ticket) disk.request else checkNotNull(launches).read(ticket)
                } else null
                currentCoroutineContext().ensureActive(); if (current != generation) return@launch
                val input = if (ticket != null) prepared else value ?: request ?: disk?.request
                if (input == null || input.origin == null) { mutable.value = state.value.copy(missingOrigin = true); return@launch }
                if (previous != null && previous != input) { clearEffect(); favoriteOwner = null; saved.remove<String>("rssReader.favoriteOwner") }
                request = input
                acceptedLaunchTicket = ticket ?: disk?.takeIf { value == null || it.request == input }?.acceptedLaunchTicket
                val restoring = value == null && (ticket == null || disk?.acceptedLaunchTicket == ticket)
                currentUrl = if (restoring) disk?.currentUrl else null
                currentTitle = if (restoring) disk?.currentTitle else null
                sessions.write(session, checkpoint()); currentCoroutineContext().ensureActive(); if (current != generation) return@launch
                if (ticket != null) {
                    checkNotNull(launches).release(ticket); currentCoroutineContext().ensureActive(); if (current != generation) return@launch
                    pendingLaunch = null; launchRepository = null
                }
                val loaded = repository.load(input); currentCoroutineContext().ensureActive(); if (current != generation) return@launch
                owned = loaded?.let(::clone)
                project(loaded)
                if (kernel != null && loaded?.document != null) documentReady()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation) failed(error.localizedMessage ?: "Error") }
        }
    }
    private fun project(value: RssReaderSnapshot?) {
        mutable.value = state.value.copy(loaded = value != null, busy = false, missingOrigin = value == null,
            title = currentTitle ?: value?.title.orEmpty(), article = value?.article != null,
            favorite = value?.favorite != null, canLogin = !value?.source?.loginUrl.isNullOrBlank(), error = null)
    }
    private fun checkpoint(): RssReaderSession {
        revision++; saved["rssReader.revision"] = revision
        return RssReaderSession(checkNotNull(request), revision, currentUrl, currentTitle, acceptedLaunchTicket)
    }
    /** A new WebView owner requires the current document; recomposition alone must not reload it. */
    fun attachKernel(owner: String) {
        if (kernel == owner) return
        kernel = owner
        if (state.value.loaded && owned?.document != null) documentReady()
    }
    fun detachKernel(owner: String) { if (kernel == owner) kernel = null }
    private fun documentReady() { nextDocument++; saved["rssReader.nextDocument"] = nextDocument; mutable.value = state.value.copy(document = nextDocument) }
    fun document(token: Long, owner: String): RssReaderSnapshot? = snapshot().takeIf { kernel == owner && state.value.document == token && token > 0 }
    fun documentDelivered(token: Long, owner: String): Boolean {
        if (kernel != owner || state.value.document != token || token <= 0) return false
        mutable.value = state.value.copy(document = 0); return true
    }
    fun progress(value: Int) { mutable.value = state.value.copy(progress = value.coerceIn(0, 100)) }
    fun page(url: String?, title: String?) {
        if (!state.value.loaded || request == null) return
        currentUrl = url; currentTitle = title
        mutable.value = state.value.copy(title = title ?: owned?.title.orEmpty())
        val checkpoint = checkpoint(); val current = generation
        viewModelScope.launch { try { sessions.write(session, checkpoint); currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation && checkpoint.revision == revision) failed(error.localizedMessage ?: "Error") } }
    }
    fun menu(open: Boolean) {
        if (!state.value.loaded || state.value.busy) return
        saved["rssReader.menu"] = open; mutable.value = state.value.copy(menu = open)
    }
    fun action(action: RssReaderAction) {
        if (!state.value.loaded || state.value.busy || state.value.pending != null) return
        if (action == RssReaderAction.Favorite && !state.value.article || action == RssReaderAction.Login && !state.value.canLogin) return
        menu(false)
        if (action == RssReaderAction.Speech && state.value.speaking) { speech.stop(); return }
        if (action == RssReaderAction.Favorite) {
            val article = owned?.article?.copy() ?: return
            mutate { current ->
                val favorite = repository.addFavorite(article); currentCoroutineContext().ensureActive()
                if (current == generation) {
                    owned = owned?.copy(favorite = favorite.copy()); mutable.value = state.value.copy(favorite = true)
                    favoriteOwner = owner(); saved["rssReader.favoriteOwner"] = favoriteOwner
                    effect(action)
                }
            }
        } else effect(action)
    }
    private fun effect(action: RssReaderAction) {
        val value = RssReaderEffect(action); saved["rssReader.action"] = action.name; saved["rssReader.nonce"] = value.nonce
        if (action == RssReaderAction.Speech) saved["rssReader.speechNonce"] = value.nonce
        mutable.value = state.value.copy(pending = value)
    }
    fun delivered(nonce: String): RssReaderEffect? {
        val value = state.value.pending?.takeIf { it.nonce == nonce } ?: return null; clearEffect(); return value
    }
    private fun clearEffect() { saved.remove<String>("rssReader.action"); saved.remove<String>("rssReader.nonce"); mutable.value = state.value.copy(pending = null) }
    fun updateFavorite(title: String?, group: String?) {
        val article = owned?.article?.copy() ?: return
        if (favoriteOwner != owner()) return
        mutate { current ->
            val value = repository.updateFavorite(article, title, group); currentCoroutineContext().ensureActive()
            if (current == generation) { owned = owned?.copy(article = value.first.copy(), favorite = value.second.copy()); mutable.value = state.value.copy(favorite = true) }
        }
    }
    fun deleteFavorite() {
        val article = owned?.article?.copy() ?: return
        if (favoriteOwner != owner()) return
        mutate { current ->
            repository.deleteFavorite(article.origin, article.link); currentCoroutineContext().ensureActive()
            if (current == generation) { owned = owned?.copy(favorite = null); mutable.value = state.value.copy(favorite = false) }
        }
    }
    private fun mutate(block: suspend (Long) -> Unit) {
        if (!state.value.loaded || state.value.busy) return
        val current = generation; mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block(current); currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation) failed(error.localizedMessage ?: "Error") }
            finally { if (current == generation) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun speakHtml(encoded: String, owner: String, nonce: String) {
        if (kernel != owner || !state.value.loaded || saved.get<String>("rssReader.speechNonce") != nonce) return
        saved.remove<String>("rssReader.speechNonce")
        val current = generation
        speechJob = viewModelScope.launch {
            try { speech.speakHtml(encoded); currentCoroutineContext().ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (current == generation) failed(error.localizedMessage ?: "Error") }
        }
    }
    private fun owner(): String? = owned?.request?.let { value -> java.security.MessageDigest.getInstance("SHA-256")
        .digest("${value.origin?.length ?: -1}:${value.origin.orEmpty()}${value.link?.length ?: -1}:${value.link.orEmpty()}${value.sort?.length ?: -1}:${value.sort.orEmpty()}${value.openUrl?.length ?: -1}:${value.openUrl.orEmpty()}".toByteArray())
        .joinToString("") { "%02x".format(it) } }
    fun retry() {
        val ticket = pendingLaunch; val launches = launchRepository
        if (ticket != null && launches != null) bindPrepared(ticket, launches)
        else if (!state.value.loaded) bind(request) else { failed(""); request?.let(::bind) }
    }
    fun failed(message: String) { mutable.value = state.value.copy(error = message.takeUnless { it.isEmpty() }, busy = false) }
    fun stop() { generation++; speech.stop(); viewModelScope.cancel() }
    override fun onCleared() {
        stop(); speech.release()
        val ticket = pendingLaunch; val launches = launchRepository
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sessions.release(session) }
            ticket?.let { runCatching { launches?.release(it) } }
        }
        super.onCleared()
    }
}
