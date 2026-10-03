package io.legado.app.ui.rss.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppConst
import io.legado.app.data.repository.*
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RssReaderImageEffectKind {
    Picker,
    Saved,
}

data class RssReaderImageEffect(
    val kind: RssReaderImageEffectKind,
    val nonce: String = UUID.randomUUID().toString(),
)

data class RssReaderImageState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val previousDirectory: String? = null,
    val pending: RssReaderImageEffect? = null,
    val error: String? = null,
)

/**
 * Restorable image copy operation, separate from the lifetime of the WebView and directory
 * Activity.
 */
class RssReaderImageViewModel(
    private val repository: RssReaderImageRepository,
    private val sessions: RssReaderImageSessionRepository,
    private val saved: SavedStateHandle,
    private val newFileName: () -> String = { "${AppConst.fileNameFormat.format(Date())}.jpg" },
) : ViewModel() {
    private val mutable = MutableStateFlow(RssReaderImageState())
    val state: StateFlow<RssReaderImageState> = mutable
    private var ticket = saved.get<String>("rssReaderImage.ticket")
    private var owner: String? = null
    private var value: RssReaderImageSession? = null
    private var generation = 0L
    private var job: Job? = null
    private var earlyResult: Pair<String, String?>? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fence the previous reader immediately, before an asynchronous full-request fingerprint is
     * ready.
     */
    fun invalidateOwner() {
        generation++
        job?.cancel()
        job = null
        owner = null
        mutable.value = state.value.copy(loaded = false, busy = false, pending = null, error = null)
        // Preserve the disk ticket and picker nonce. A result arriving now is buffered until bind
        // verifies its owner.
    }

    fun bind(currentOwner: String) {
        if (owner == currentOwner && (state.value.loaded || job?.isActive == true)) return
        owner = currentOwner
        job?.cancel()
        val epoch = ++generation
        mutable.value = state.value.copy(loaded = false, busy = false, pending = null, error = null)
        job = viewModelScope.launch {
            try {
                val id = ticket
                val restored = id?.let { sessions.read(it) }
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                if (restored != null && restored.owner == currentOwner) {
                    value = restored
                    mutable.value = state.value.copy(loaded = true)
                    when (restored.phase) {
                        RssReaderImagePhase.PickDirectory -> {
                            val early = earlyResult
                            earlyResult = null
                            if (early != null) picked(early.first, early.second) else picker()
                        }
                        RssReaderImagePhase.Save -> copy(epoch)
                        RssReaderImagePhase.Complete -> completed()
                    }
                } else {
                    if (id != null) release(id)
                    clear()
                    mutable.value = RssReaderImageState(loaded = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    fun save(image: String, currentOwner: String) = start(image, currentOwner, choose = false)

    fun chooseDirectory(currentOwner: String) = start(null, currentOwner, choose = true)

    private fun start(image: String?, currentOwner: String, choose: Boolean) {
        if (
            !state.value.loaded ||
                state.value.busy ||
                owner != currentOwner ||
                state.value.pending != null ||
                saved.get<Boolean>("rssReaderImage.pickerLaunched") == true
        )
            return
        val previous = ticket
        if (previous != null) release(previous)
        clear()
        val id = UUID.randomUUID().toString()
        ticket = id
        saved["rssReaderImage.ticket"] = id
        value = RssReaderImageSession(currentOwner, image, revision = 1)
        val epoch = generation
        mutable.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                sessions.write(id, value!!)
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                val directory = if (choose) null else repository.directory()
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                if (directory.isNullOrEmpty()) picker()
                else {
                    value =
                        value!!.copy(
                            directory = directory,
                            fileName = newFileName(),
                            phase = RssReaderImagePhase.Save,
                            revision = value!!.revision + 1,
                        )
                    sessions.write(id, value!!)
                    currentCoroutineContext().ensureActive()
                    if (epoch == generation) copy(epoch)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    private suspend fun picker() {
        if (saved.get<Boolean>("rssReaderImage.pickerLaunched") == true) {
            mutable.value = state.value.copy(loaded = true, busy = false, pending = null)
            return
        }
        val previous = repository.directory()
        currentCoroutineContext().ensureActive()
        val nonce =
            saved.get<String>("rssReaderImage.pickerNonce")
                ?: UUID.randomUUID().toString().also { saved["rssReaderImage.pickerNonce"] = it }
        mutable.value =
            state.value.copy(
                loaded = true,
                busy = false,
                previousDirectory = previous,
                pending = RssReaderImageEffect(RssReaderImageEffectKind.Picker, nonce),
            )
    }

    fun pickerDelivered(nonce: String): Boolean {
        if (
            state.value.pending?.let {
                it.kind == RssReaderImageEffectKind.Picker && it.nonce == nonce
            } != true
        )
            return false
        mutable.value = state.value.copy(pending = null)
        saved["rssReaderImage.pickerLaunched"] = true
        return true
    }

    fun picked(nonce: String, directory: String?) {
        if (!state.value.loaded && saved.get<String>("rssReaderImage.pickerNonce") == nonce) {
            earlyResult = nonce to directory
            return
        }
        if (
            saved.get<String>("rssReaderImage.pickerNonce") != nonce ||
                saved.get<Boolean>("rssReaderImage.pickerLaunched") != true ||
                state.value.busy
        )
            return
        saved["rssReaderImage.pickerLaunched"] = false
        if (directory == null) {
            ticket?.let(::release)
            clear()
            mutable.value = state.value.copy(busy = false, pending = null)
            return
        }
        val id = ticket ?: return
        val current = value ?: return
        val epoch = generation
        mutable.value = state.value.copy(busy = true, pending = null, error = null)
        value =
            current.copy(
                directory = directory,
                fileName = if (current.image != null) newFileName() else null,
                phase = RssReaderImagePhase.Save,
                revision = current.revision + 1,
            )
        job = viewModelScope.launch {
            try {
                sessions.write(id, value!!)
                currentCoroutineContext().ensureActive()
                if (epoch == generation) copy(epoch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    private suspend fun copy(epoch: Long) {
        val id = ticket ?: return
        val current = value ?: return
        val directory = current.directory ?: return
        mutable.value = state.value.copy(busy = true, pending = null)
        repository.directory(directory)
        currentCoroutineContext().ensureActive()
        if (epoch != generation) return
        try {
            current.image?.let { repository.save(it, directory, checkNotNull(current.fileName)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (epoch == generation && repository.directory() == directory)
                repository.directory(null)
            throw error
        }
        currentCoroutineContext().ensureActive()
        if (epoch != generation) return
        value = current.copy(phase = RssReaderImagePhase.Complete, revision = current.revision + 1)
        sessions.write(id, value!!)
        currentCoroutineContext().ensureActive()
        if (epoch == generation) completed()
    }

    private fun completed() {
        if (value?.image == null) {
            ticket?.let(::release)
            clear()
            mutable.value = state.value.copy(busy = false, pending = null)
            return
        }
        val nonce =
            saved.get<String>("rssReaderImage.savedNonce")
                ?: UUID.randomUUID().toString().also { saved["rssReaderImage.savedNonce"] = it }
        mutable.value =
            state.value.copy(
                loaded = true,
                busy = false,
                error = null,
                pending = RssReaderImageEffect(RssReaderImageEffectKind.Saved, nonce),
            )
    }

    fun delivered(nonce: String): Boolean {
        if (
            state.value.pending?.let {
                it.kind == RssReaderImageEffectKind.Saved && it.nonce == nonce
            } != true
        )
            return false
        ticket?.let(::release)
        clear()
        mutable.value = state.value.copy(pending = null)
        return true
    }

    fun retry() {
        if (state.value.busy) return
        val id = ticket
        val current = value
        if (id == null || current == null) {
            mutable.value = state.value.copy(loaded = false)
            owner?.let { bind(it) }
            return
        }
        val epoch = generation
        mutable.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                sessions.write(id, current)
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                when (current.phase) {
                    RssReaderImagePhase.PickDirectory -> picker()
                    RssReaderImagePhase.Save -> copy(epoch)
                    RssReaderImagePhase.Complete -> completed()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        mutable.value =
            state.value.copy(loaded = true, busy = false, error = error.localizedMessage ?: "Error")
    }

    private fun clear() {
        ticket = null
        value = null
        earlyResult = null
        saved.remove<String>("rssReaderImage.ticket")
        saved.remove<String>("rssReaderImage.pickerNonce")
        saved.remove<Boolean>("rssReaderImage.pickerLaunched")
        saved.remove<String>("rssReaderImage.savedNonce")
    }

    private fun release(id: String) {
        cleanup.launch { runCatching { sessions.release(id) } }
    }

    fun stop() {
        generation++
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        ticket?.let(::release)
        super.onCleared()
    }
}
