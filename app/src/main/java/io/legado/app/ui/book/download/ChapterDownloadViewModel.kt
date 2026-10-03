package io.legado.app.ui.book.download

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ChapterDownloadSessionRepository
import io.legado.app.model.download.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChapterDownloadState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val mode: ChapterDownloadMode = ChapterDownloadMode.Book,
    val start: String = "",
    val end: String = "",
    val invalidRange: Boolean = false,
    val error: String? = null,
    val pending: ChapterDownloadSelection? = null,
    val finished: Boolean = false,
    val closed: Boolean = false,
)

data class ChapterDownloadDelivery(
    val book: Book,
    val mode: ChapterDownloadMode,
    val range: ChapterDownloadRange,
)

/**
 * Small text lives in SavedState; the full immutable request and consumed receipt live in private
 * storage.
 */
class ChapterDownloadViewModel(
    private val saved: SavedStateHandle,
    private val sessions: ChapterDownloadSessionRepository,
    val ticket: String,
    private val cleanup: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ViewModel() {
    private val mutable =
        MutableStateFlow(ChapterDownloadState(closed = saved.get<Boolean>(CLOSED) == true))
    val state = mutable.asStateFlow()
    private var owned: ChapterDownloadSession? = null
    private var revision = 0L
    private var operation: Job? = null
    private var loading: Job? = null
    private val gate = Mutex()

    init {
        if (!state.value.closed) load()
    }

    private fun load() {
        if (state.value.busy || state.value.closed) return
        loading?.cancel()
        mutable.value = state.value.copy(loaded = false, error = null)
        loading = viewModelScope.launch {
            try {
                val current =
                    checkNotNull(sessions.read(ticket)) { "Chapter download request missing" }
                currentCoroutineContext().ensureActive()
                if (state.value.closed) return@launch
                owned = current
                revision = maxOf(revision, current.revision)
                val first = saved.get<String>(START) ?: current.initialChapter.toString().take(5)
                val last = saved.get<String>(END) ?: current.chapterCount.toString().take(5)
                saved[START] = first
                saved[END] = last
                mutable.value =
                    state.value.copy(
                        loaded = true,
                        mode = current.mode,
                        start = first,
                        end = last,
                        pending = current.pending,
                        finished = current.completed,
                    )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!state.value.closed)
                    mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun start(value: String) = edit(value, true)

    fun end(value: String) = edit(value, false)

    private fun edit(value: String, first: Boolean) {
        if (
            !state.value.loaded ||
                state.value.busy ||
                state.value.closed ||
                state.value.pending != null ||
                state.value.finished ||
                value.length > 5 ||
                !value.all(Char::isDigit)
        )
            return
        saved[if (first) START else END] = value
        mutable.value =
            if (first) state.value.copy(start = value, invalidRange = false, error = null)
            else state.value.copy(end = value, invalidRange = false, error = null)
    }

    fun confirm() {
        val current = owned ?: return
        if (
            state.value.busy ||
                state.value.closed ||
                state.value.pending != null ||
                state.value.finished
        )
            return
        val range =
            chapterDownloadRange(
                current.mode,
                state.value.start,
                state.value.end,
                current.chapterCount,
            )
        if (range == null) {
            mutable.value = state.value.copy(invalidRange = true)
            return
        }
        val next =
            current.copy(
                revision = ++revision,
                pending = ChapterDownloadSelection(UUID.randomUUID().toString(), range),
            )
        mutable.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                sessions.write(ticket, next)
                currentCoroutineContext().ensureActive()
                if (!state.value.closed) {
                    owned = next
                    mutable.value = state.value.copy(pending = next.pending)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!state.value.closed)
                    mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            } finally {
                if (currentCoroutineContext().isActive && !state.value.closed)
                    mutable.value = state.value.copy(busy = false)
            }
        }
    }

    suspend fun claim(token: String, available: () -> Boolean): ChapterDownloadDelivery? =
        gate.withLock {
            val current = owned ?: return@withLock null
            val selection = current.pending?.takeIf { it.token == token } ?: return@withLock null
            if (state.value.closed || current.completed || !available()) return@withLock null
            val context = currentCoroutineContext()
            val book = sessions.book(current)
            context.ensureActive()
            if (!available() || state.value.closed) return@withLock null
            mutable.value = state.value.copy(busy = true, error = null)
            try {
                val next = current.copy(revision = ++revision, pending = null, completed = true)
                withContext(NonCancellable) { sessions.write(ticket, next) }
                if (!context.isActive || !available() || state.value.closed) {
                    val rollback = current.copy(revision = ++revision)
                    withContext(NonCancellable) { sessions.write(ticket, rollback) }
                    owned = rollback
                    context.ensureActive()
                    return@withLock null
                }
                owned = next
                mutable.value = state.value.copy(pending = null, finished = true)
                ChapterDownloadDelivery(book, current.mode, selection.range)
            } finally {
                if (!state.value.closed) mutable.value = state.value.copy(busy = false)
            }
        }

    fun failure(error: Throwable) {
        if (!state.value.closed)
            mutable.value =
                state.value.copy(error = error.localizedMessage ?: "Error", busy = false)
    }

    fun retry() {
        if (!state.value.busy) {
            if (!state.value.loaded) load()
            else if (state.value.pending == null) confirm()
            else mutable.value = state.value.copy(error = null)
        }
    }

    fun close() {
        if (state.value.closed) return
        saved[CLOSED] = true
        mutable.value = state.value.copy(closed = true, busy = false)
        loading?.cancel()
        operation?.cancel()
        cleanup.launch { runCatching { sessions.release(ticket) } }
    }

    fun stop() {
        viewModelScope.cancel()
    }

    override fun onCleared() {
        close()
        stop()
        super.onCleared()
    }

    private companion object {
        const val START = "chapterDownload.start"
        const val END = "chapterDownload.end"
        const val CLOSED = "chapterDownload.closed"
    }
}
