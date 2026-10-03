package io.legado.app.ui.qrcode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.QrScanRepository
import io.legado.app.data.repository.QrScanSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class QrScanState(val loading: Boolean = true, val decoding: Boolean = false,
    val pendingResult: Long? = null, val completed: Boolean = false, val error: String? = null)
internal data class QrScanDelivery(val text: String?)

/** Only the session ID and consumed revision enter SavedState, never bitmap/URI/result payloads. */
internal class QrScanViewModel(private val repository: QrScanRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(QrScanState()); val state = mutable.asStateFlow()
    private var current: QrScanSession? = null
    private var initializing: Job? = null
    private var work: Job? = null
    private var generation = 0L
    private var closed = false
    private val mutations = Mutex()
    private var queuedGallery: String? = null
    private var queuedResult: QrScanDelivery? = null
    init { initialize() }
    private fun version() = maxOf(System.nanoTime(), (current?.revision ?: 0) + 1)
    private fun publish(value: QrScanSession) {
        current = value
        mutable.value = QrScanState(loading = false, pendingResult = value.revision.takeIf { value.resultReady && !value.completed }, completed = value.completed)
    }
    private fun initialize() {
        if (closed) return
        initializing?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        initializing = viewModelScope.launch {
            try {
                val opened = repository.open(saved.get<String>("session"))
                if (closed || !isActive) { withContext(NonCancellable) { if (closed) repository.release(opened.id) }; return@launch }
                saved["session"] = opened.id
                var value = opened
                if (saved.get<Long>("consumed") == value.revision && value.resultReady) {
                    value = value.copy(completed = true, revision = maxOf(System.nanoTime(), value.revision + 1)); repository.write(value)
                }
                ensureActive(); publish(value)
                if (!value.completed && !value.resultReady) {
                    val result = queuedResult; queuedResult = null
                    if (result != null) capture(result.text)
                    else (queuedGallery ?: value.gallery)?.let { queuedGallery = null; gallery(it) }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { ensureActive(); if (!closed) mutable.value = state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun gallery(uri: String?) {
        if (uri == null || closed || state.value.completed || current?.resultReady == true) return
        if (current == null) { queuedGallery = uri; return }
        work?.cancel(); val token = ++generation
        mutable.value = state.value.copy(decoding = true, error = null)
        work = viewModelScope.launch {
            try {
                mutations.withLock {
                    val value = requireNotNull(current).copy(gallery = uri, revision = version())
                    repository.write(value); ensureActive(); if (closed || token != generation) return@withLock; current = value
                }
                val result = repository.decode(uri); ensureActive()
                if (!closed && token == generation) persistResult(result, token)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { ensureActive(); if (!closed && token == generation) mutable.value = state.value.copy(decoding = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    fun capture(text: String?) {
        if (closed || state.value.completed || current?.resultReady == true) return
        queuedResult = QrScanDelivery(text)
        if (current == null) return
        work?.cancel(); val token = ++generation
        mutable.value = state.value.copy(decoding = true, error = null)
        work = viewModelScope.launch {
            try { persistResult(text, token) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { ensureActive(); if (!closed && token == generation) mutable.value = state.value.copy(decoding = false, error = error.localizedMessage ?: "ERROR") }
        }
    }
    private suspend fun persistResult(text: String?, token: Long) = mutations.withLock {
        currentCoroutineContext().ensureActive()
        if (closed || token != generation) return@withLock
        val value = requireNotNull(current).copy(gallery = null, resultReady = true, result = text, revision = version())
        repository.write(value); currentCoroutineContext().ensureActive()
        if (!closed && token == generation) { queuedResult = null; publish(value) }
    }
    suspend fun consume(revision: Long, canDeliver: () -> Boolean): QrScanDelivery? = mutations.withLock {
        val value = current ?: return@withLock null
        if (closed || value.completed || !value.resultReady || value.revision != revision || !canDeliver()) return@withLock null
        val caller = currentCoroutineContext()
        val claimed = value.copy(completed = true, revision = version())
        withContext(NonCancellable) { repository.write(claimed) }
        if (closed || !caller.isActive || !canDeliver()) {
            if (!closed) withContext(NonCancellable) {
                val rollback = value.copy(revision = maxOf(System.nanoTime(), claimed.revision + 1))
                repository.write(rollback); publish(rollback)
            }
            return@withLock null
        }
        saved["consumed"] = revision
        publish(claimed)
        QrScanDelivery(value.result)
    }
    fun retry() { if (closed) return; val value = current; if (value == null) initialize() else if (!value.completed) { val pending = queuedResult; if (pending != null) capture(pending.text) else value.gallery?.let(::gallery) } }
    suspend fun close() {
        if (closed) return
        closed = true; ++generation; initializing?.cancel(); work?.cancel()
        val id = current?.id ?: saved.get<String>("session")
        withContext(NonCancellable) { mutations.withLock { if (id != null) repository.release(id) } }
    }
    override fun onCleared() { ++generation; initializing?.cancel(); work?.cancel() }
}
