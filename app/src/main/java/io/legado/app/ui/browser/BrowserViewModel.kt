package io.legado.app.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.model.browser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class BrowserState(val loading: Boolean = true, val loadFailed: Boolean = false,
    val busy: Boolean = false, val page: BrowserPage? = null, val title: String = "", val progress: Int = 0,
    val fullscreen: Boolean = false, val cloudflare: Boolean = false, val capture: String? = null,
    val imageActions: Boolean = false, val imageRequest: String? = null, val selectImageDirectory: Boolean = false,
    val receipt: String? = null, val finished: Boolean = false, val error: String? = null, val persistError: Boolean = false)
internal class BrowserViewModel(private val repository: BrowserRepository, private val saved: SavedStateHandle,
    private val seed: suspend () -> BrowserRequest) : ViewModel() {
    val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(BrowserState(fullscreen = saved.get<Boolean>("fullscreen") == true)); val state = mutable.asStateFlow()
    private var pendingReceipt: BrowserReceipt? = null
    private var current: BrowserSession? = null; private var seedValue: BrowserRequest? = null; private var prepared: BrowserPage? = null
    private var revision = 0L; private var generation = 0L; private var stopped = false
    private var load: Job? = null; private var operation: Job? = null
    private val cookies = Mutex(); private val writes = MutableStateFlow<BrowserSession?>(null)
    private val writer = viewModelScope.launch {
        writes.filterNotNull().collect { value ->
            try { repository.write(session, value); currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision && pendingReceipt == null) {
                    mutable.value = state.value.copy(error = if (state.value.persistError) null else state.value.error, persistError = false)
                    drainVerification()
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped && current?.revision == value.revision) mutable.value = state.value.copy(persistError = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    init { initialize(false) }
    private fun nextRevision(): Long { revision = maxOf(revision + 1, System.nanoTime(), (current?.revision ?: 0) + 1); return revision }
    private fun initialize(retry: Boolean) {
        if (stopped) return
        load?.cancel(); mutable.value = state.value.copy(loading = true, loadFailed = false, error = null)
        load = viewModelScope.launch {
            try {
                var snapshot = repository.read(session) ?: repository.create(session, BrowserSession(seedValue ?: seed().also { seedValue = it }))
                revision = maxOf(revision, snapshot.revision)
                if (snapshot.page == null) {
                    check(retry || saved.get<Boolean>("preparing") != true || prepared != null) { "网页请求已中断，请重试" }
                    saved["preparing"] = true
                    val page = prepared?.takeIf { it.request == snapshot.request } ?: repository.prepare(snapshot.request).also { prepared = it }
                    currentCoroutineContext().ensureActive(); if (stopped) return@launch
                    snapshot = snapshot.copy(page = page, revision = nextRevision()); repository.write(session, snapshot)
                    currentCoroutineContext().ensureActive(); if (stopped) return@launch
                    prepared = null; seedValue = null; saved["preparing"] = false
                }
                if (snapshot.receipt?.id == saved.get<String>("consumedReceipt")) {
                    snapshot = snapshot.copy(receipt = null, revision = nextRevision()); repository.write(session, snapshot)
                }
                currentCoroutineContext().ensureActive(); if (stopped) return@launch
                current = snapshot
                mutable.value = state.value.copy(loading = false, page = snapshot.page, title = snapshot.title,
                    receipt = snapshot.receipt?.id, finished = snapshot.finished, capture = saved.get<String>("capture"),
                    busy = saved.get<String>("capture") != null, imageActions = saved.get<Boolean>("imageActions") == true,
                    imageRequest = saved.get<String>("imageRequest"), selectImageDirectory = saved.get<Boolean>("selectImageDirectory") == true)
                drainVerification()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: BrowserSessionClosedException) { currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(loading = false, finished = true) }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped) mutable.value = state.value.copy(loading = false, loadFailed = true, error = error.localizedMessage.orEmpty()) }
        }
    }
    private fun change(value: BrowserSession) {
        if (stopped) return
        current = value.copy(revision = nextRevision()); writes.value = current
    }
    suspend fun webCookies(url: String) = repository.webCookies(url)
    fun progress(value: Int) { if (!stopped) mutable.value = state.value.copy(progress = value.coerceIn(0, 100)) }
    fun title(value: String) {
        val snapshot = current ?: return; if (stopped || snapshot.finished || snapshot.title == value) return
        change(snapshot.copy(title = value)); mutable.value = state.value.copy(title = value)
    }
    fun fullscreen(value: Boolean) { if (!stopped) { saved["fullscreen"] = value; mutable.value = state.value.copy(fullscreen = value) } }
    fun cookie(url: String, value: String?) {
        if (stopped) return
        viewModelScope.launch { try { cookies.withLock { repository.cookie(url, value) }; currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); if (!stopped) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty()) } }
    }
    fun challenge(challenged: Boolean) {
        if (stopped) return
        if (challenged) mutable.value = state.value.copy(cloudflare = true)
        else if (state.value.cloudflare && current?.request?.verificationEnabled == true) verify()
    }
    fun windowClose() { if (!state.value.cloudflare) verify() }
    fun verify() {
        val snapshot = current ?: return
        if (stopped || snapshot.finished) return
        if (state.value.busy || state.value.capture != null || state.value.receipt != null || state.value.persistError || pendingReceipt != null) { saved["verifyAfterOperation"] = true; return }
        if (!snapshot.request.verificationEnabled) { change(snapshot.copy(finished = true)); mutable.value = state.value.copy(finished = true); return }
        if (snapshot.request.refetchAfterSuccess) launch { publish(BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.Verified, repository.refetch(requireNotNull(current?.page)))) }
        else {
            val id = UUID.randomUUID().toString(); saved["capture"] = id
            mutable.value = state.value.copy(capture = id, busy = true, error = null)
        }
    }
    fun captured(id: String, html: String, url: String) {
        if (stopped || state.value.capture != id) return
        saved["capture"] = null; mutable.value = state.value.copy(capture = null, busy = false)
        launch { publish(BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.Verified, repository.captured(html, url))) }
    }
    fun image(value: String) {
        val snapshot = current ?: return; if (stopped || snapshot.finished || state.value.persistError || pendingReceipt != null || state.value.busy || state.value.receipt != null) return
        change(snapshot.copy(image = value)); saved["imageActions"] = true
        mutable.value = state.value.copy(imageActions = true)
    }
    fun dismissImageActions() { saved["imageActions"] = false; mutable.value = state.value.copy(imageActions = false) }
    fun requestImageDirectory(select: Boolean) {
        if (stopped || current?.image == null || state.value.busy || state.value.finished || state.value.persistError || pendingReceipt != null || state.value.receipt != null) return
        dismissImageActions(); val id = UUID.randomUUID().toString()
        saved["imageRequest"] = id; saved["selectImageDirectory"] = select
        mutable.value = state.value.copy(imageRequest = id, selectImageDirectory = select)
    }
    suspend fun imageDirectory(): String? {
        // The image URL/data is durable before starting an external document picker.
        flush(); currentCoroutineContext().ensureActive(); return repository.imageDirectory()
    }
    fun consumeImageRequest(id: String): Boolean {
        if (stopped || state.value.imageRequest != id) return false
        saved["imageRequest"] = null; mutable.value = state.value.copy(imageRequest = null); return true
    }
    fun saveImage(directory: String, remember: Boolean = false) {
        val data = current?.image ?: return
        launch {
            if (remember) repository.imageDirectory(directory)
            val receipt = try { repository.saveImage(data, directory); currentCoroutineContext().ensureActive()
                BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.ImageSaved)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                repository.forgetImageDirectory(directory); currentCoroutineContext().ensureActive()
                BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.ImageFailed, message = error.localizedMessage.orEmpty()) }
            publish(receipt)
        }
    }
    fun disableSource() { val request = current?.request ?: return; if (request.sourceOrigin.isEmpty()) return
        launch { repository.disableSource(request.sourceOrigin, request.sourceType); currentCoroutineContext().ensureActive(); publish(BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.Close)) } }
    fun deleteSource() { val request = current?.request ?: return; if (request.sourceOrigin.isEmpty()) return
        launch { repository.deleteSource(request.sourceOrigin, request.sourceType); currentCoroutineContext().ensureActive(); publish(BrowserReceipt(UUID.randomUUID().toString(), BrowserReceiptKind.Close)) } }
    private fun launch(block: suspend () -> Unit) {
        if (stopped || current == null || state.value.busy || state.value.finished || state.value.receipt != null || state.value.persistError || pendingReceipt != null) return
        val token = ++generation; mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block(); currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped && token == generation) mutable.value = state.value.copy(error = error.localizedMessage.orEmpty(), persistError = pendingReceipt != null) }
            finally { if (currentCoroutineContext().isActive && !stopped && token == generation) { mutable.value = state.value.copy(busy = false); drainVerification() } }
        }
    }
    private suspend fun publish(receipt: BrowserReceipt) {
        pendingReceipt = receipt
        while (!stopped) {
            val snapshot = current ?: return
            val finish = receipt.kind == BrowserReceiptKind.Close || receipt.kind == BrowserReceiptKind.Verified
            val value = snapshot.copy(receipt = receipt, finished = finish, revision = nextRevision())
            repository.write(session, value); currentCoroutineContext().ensureActive()
            if (current?.revision == snapshot.revision) { current = value; pendingReceipt = null
                mutable.value = state.value.copy(receipt = receipt.id, finished = finish, persistError = false, error = null); return }
        }
    }
    fun prepareReceipt(id: String): BrowserReceipt = requireNotNull(current?.receipt).also { check(it.id == id) }
    fun consumeReceipt(id: String): Boolean {
        val snapshot = current ?: return false
        if (stopped || snapshot.receipt?.id != id || saved.get<String>("consumedReceipt") == id) return false
        saved["consumedReceipt"] = id; change(snapshot.copy(receipt = null)); mutable.value = state.value.copy(receipt = null); drainVerification(); return true
    }
    private fun drainVerification() {
        if (stopped || state.value.busy || state.value.receipt != null || state.value.persistError || pendingReceipt != null || saved.get<Boolean>("verifyAfterOperation") != true) return
        saved["verifyAfterOperation"] = false
        if (!state.value.finished) verify()
    }
    fun retry() {
        if (stopped || state.value.busy) return
        if (state.value.loadFailed) { initialize(true); return }
        val retained = pendingReceipt
        if (retained == null) { current?.let { change(it) }; return }
        val token = ++generation; mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { publish(retained); currentCoroutineContext().ensureActive() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive()
                if (!stopped && token == generation) mutable.value = state.value.copy(persistError = true, error = error.localizedMessage.orEmpty()) }
            finally { if (currentCoroutineContext().isActive && !stopped && token == generation) { mutable.value = state.value.copy(busy = false); drainVerification() } }
        }
    }
    suspend fun flush() { if (!stopped) current?.let { repository.write(session, it) } }
    suspend fun releaseOwnedSession() { stop(); repository.release(session) }
    fun stop() { if (stopped) return; stopped = true; ++generation; load?.cancel(); operation?.cancel(); writer.cancel() }
    override fun onCleared() { stop(); super.onCleared() }
}
