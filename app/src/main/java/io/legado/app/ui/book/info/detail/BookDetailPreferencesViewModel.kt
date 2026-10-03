package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookDetailPreference
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailServicesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BookDetailPreferenceState(
    val values: BookDetailPreferences = BookDetailPreferences(true, false, false, false),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val dirty: Set<BookDetailPreference> = emptySet(),
)

/**
 * Only three small requested booleans may enter SavedState. Repository writes always patch one
 * field.
 */
class BookDetailPreferencesViewModel(
    private val saved: SavedStateHandle,
    private val repository: BookDetailServicesRepository,
) : ViewModel() {
    private data class Requested(val value: Boolean, val revision: Long)

    private val mutable = MutableStateFlow(BookDetailPreferenceState())
    val state: StateFlow<BookDetailPreferenceState> = mutable.asStateFlow()
    private val pending = linkedMapOf<BookDetailPreference, Requested>()
    private val failed = mutableSetOf<BookDetailPreference>()
    private val writes = Mutex()
    private var revision = 0L
    private var flushJob: Job? = null
    private var readJob: Job? = null

    init {
        BookDetailPreference.entries.forEach { field ->
            saved.get<Boolean>(key(field))?.let { value ->
                pending[field] = Requested(value, ++revision)
                mutable.update { it.copy(values = it.values.with(field, value)) }
            }
        }
        mutable.update { it.copy(dirty = pending.keys.toSet()) }
        refresh()
    }

    fun refresh() {
        readJob?.cancel()
        val observed = revision
        readJob = viewModelScope.launch {
            try {
                val actual = repository.preferences()
                ensureActive()
                if (observed == revision)
                    mutable.update {
                        it.copy(
                            values = overlay(actual),
                            loading = false,
                            loaded = true,
                            error = it.error.takeIf { failed.isNotEmpty() },
                        )
                    }
                else mutable.update { it.copy(loading = false, loaded = true) }
                flushRequested()
            } catch (error: Throwable) {
                ensureActive()
                mutable.update {
                    it.copy(loading = false, error = error.message ?: error.toString())
                }
            }
        }
    }

    fun toggle(field: BookDetailPreference) = set(field, !state.value.values.get(field))

    fun set(field: BookDetailPreference, value: Boolean) {
        val request = Requested(value, ++revision)
        pending[field] = request
        failed.remove(field)
        saved[key(field)] = value
        mutable.update {
            it.copy(
                values = it.values.with(field, value),
                dirty = pending.keys.toSet(),
                error = it.error.takeIf { failed.isNotEmpty() },
            )
        }
        flushRequested()
    }

    private fun overlay(actual: BookDetailPreferences) =
        pending.entries.fold(actual) { result, (field, request) ->
            result.with(field, request.value)
        }

    private fun flushRequested() {
        if (state.value.loading || flushJob?.isActive == true) return
        flushJob = viewModelScope.launch { writes.withLock { flushLocked() } }
    }

    private suspend fun flushLocked() {
        mutable.update { it.copy(saving = true) }
        try {
            while (true) {
                val item = pending.entries.firstOrNull { it.key !in failed } ?: break
                val field = item.key
                val requested = item.value
                try {
                    val actual = repository.preference(field, requested.value)
                    currentCoroutineContext().ensureActive()
                    if (pending[field]?.revision == requested.revision) {
                        pending.remove(field)
                        saved.remove<Boolean>(key(field))
                        failed.remove(field)
                        revision++
                    }
                    mutable.update {
                        it.copy(
                            values = overlay(actual),
                            dirty = pending.keys.toSet(),
                            error = it.error.takeIf { failed.isNotEmpty() },
                        )
                    }
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    // A failed old write must not suppress a newer request for the same field.
                    if (pending[field]?.revision == requested.revision) {
                        failed += field
                        mutable.update { it.copy(error = error.message ?: error.toString()) }
                    }
                }
            }
        } finally {
            mutable.update { it.copy(saving = false, dirty = pending.keys.toSet()) }
        }
    }

    fun retry() {
        failed.clear()
        mutable.update { it.copy(error = null) }
        if (!state.value.loaded) refresh() else flushRequested()
    }

    /** Native side effects must use committed preferences, including an earlier failed field. */
    suspend fun requireCommitted(): BookDetailPreferences {
        readJob?.join()
        currentCoroutineContext().ensureActive()
        return writes.withLock {
            flushLocked()
            check(state.value.loaded && pending.isEmpty() && state.value.error == null) {
                state.value.error ?: "Preferences are not saved"
            }
            state.value.values
        }
    }

    fun stop() {
        readJob?.cancel()
        flushJob?.cancel()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private fun key(field: BookDetailPreference) = "book.detail.preference." + field.name
    }
}

private fun BookDetailPreferences.get(field: BookDetailPreference) =
    when (field) {
        BookDetailPreference.DeleteAlert -> deleteAlert
        BookDetailPreference.DeleteOriginal -> deleteOriginal
        BookDetailPreference.UploadImported -> uploadImported
    }

private fun BookDetailPreferences.with(field: BookDetailPreference, value: Boolean) =
    when (field) {
        BookDetailPreference.DeleteAlert -> copy(deleteAlert = value)
        BookDetailPreference.DeleteOriginal -> copy(deleteOriginal = value)
        BookDetailPreference.UploadImported -> copy(uploadImported = value)
    }
