package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ThemeListItem
import io.legado.app.data.repository.ThemeListRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class ThemeListEventKind {
    Clipboard,
    Share,
    ImportFailed,
}

internal data class ThemeListEvent(val kind: ThemeListEventKind, val receipt: String? = null)

internal data class ThemeListState(
    val items: List<ThemeListItem> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val deleteKey: String? = null,
    val event: ThemeListEvent? = null,
)

internal class ThemeListViewModel(
    private val repository: ThemeListRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val session =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable =
        MutableStateFlow(
            ThemeListState(
                deleteKey = saved["deleteKey"],
                event =
                    saved.get<String>("event")?.let { name ->
                        runCatching {
                            ThemeListEvent(ThemeListEventKind.valueOf(name), saved["receipt"])
                        }
                            .getOrNull()
                    },
            )
        )
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var operationJob: Job? = null
    private var stopped = false

    init {
        reload()
    }

    fun reload() {
        if (stopped) return
        val token = ++generation
        loadJob?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val rows = repository.list()
                currentCoroutineContext().ensureActive()
                if (token == generation && !stopped) {
                    val target = state.value.deleteKey?.takeIf { key -> rows.any { it.key == key } }
                    saved["deleteKey"] = target
                    mutable.value =
                        state.value.copy(items = rows, loading = false, deleteKey = target)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                if (token == generation && !stopped)
                    mutable.value =
                        state.value.copy(loading = false, error = error.localizedMessage.orEmpty())
            }
        }
    }

    fun delete(key: String) {
        if (
            stopped ||
                state.value.loading ||
                state.value.busy ||
                state.value.items.none { it.key == key }
        )
            return
        saved["deleteKey"] = key
        mutable.value = state.value.copy(deleteKey = key)
    }

    fun cancelDelete() {
        if (stopped) return
        saved.remove<String>("deleteKey")
        mutable.value = state.value.copy(deleteKey = null)
    }

    fun confirmDelete() {
        val item = state.value.items.find { it.key == state.value.deleteKey } ?: return
        cancelDelete()
        operation {
            repository.delete(item)
            currentCoroutineContext().ensureActive()
            if (!stopped) reload()
        }
    }

    fun apply(key: String) {
        state.value.items
            .find { it.key == key }
            ?.let { item -> operation { repository.apply(item) } }
    }

    fun importClipboard() {
        if (!stopped && !state.value.loading && !state.value.busy && state.value.event == null)
            event(ThemeListEvent(ThemeListEventKind.Clipboard))
    }

    fun importText(text: String?) {
        if (text == null) return
        operation {
            val added = repository.add(text)
            currentCoroutineContext().ensureActive()
            if (!stopped) {
                if (added) reload() else event(ThemeListEvent(ThemeListEventKind.ImportFailed))
            }
        }
    }

    fun share(key: String) {
        val item = state.value.items.find { it.key == key } ?: return
        operation {
            val receipt = UUID.randomUUID().toString()
            repository.stageShare(session, receipt, item)
            currentCoroutineContext().ensureActive()
            if (!stopped) event(ThemeListEvent(ThemeListEventKind.Share, receipt))
        }
    }

    suspend fun sharePayload(receipt: String) = repository.share(session, receipt)

    fun failed(error: Throwable) {
        if (stopped) return
        mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
    }

    private fun operation(block: suspend () -> Unit) {
        if (stopped || state.value.loading || state.value.busy || state.value.event != null) return
        mutable.value = state.value.copy(busy = true, error = null)
        operationJob = viewModelScope.launch {
            try {
                block()
                currentCoroutineContext().ensureActive()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                failed(error)
            } finally {
                if (!stopped && currentCoroutineContext().isActive)
                    mutable.value = state.value.copy(busy = false)
            }
        }
    }

    private fun event(value: ThemeListEvent) {
        if (stopped) return
        saved["event"] = value.kind.name
        saved["receipt"] = value.receipt
        mutable.value = state.value.copy(event = value)
    }

    fun consume(value: ThemeListEvent) {
        if (stopped || state.value.event != value) return
        saved.remove<String>("event")
        saved.remove<String>("receipt")
        mutable.value = state.value.copy(event = null)
    }

    fun stop() {
        stopped = true
        generation++
        loadJob?.cancel()
        operationJob?.cancel()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
