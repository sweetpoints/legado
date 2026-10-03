package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.TocHostSession
import io.legado.app.data.repository.TocHostSessionRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TocHostSessionState(
    val ready: Boolean = false,
    val bookUrl: String? = null,
    val query: String = "",
    val tab: Int = 0,
    val searchOpen: Boolean = false,
    val menuOpen: Boolean = false,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val error: String? = null,
)

/**
 * Large host inputs live in an owned private session; the Android saved bundle keeps only small
 * presentation fields.
 */
class TocHostSessionViewModel(
    private val repository: TocHostSessionRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val session =
        saved.get<String>("tocHost.session")
            ?: UUID.randomUUID().toString().also { saved["tocHost.session"] = it }
    private val owner = UUID.randomUUID().toString()
    private val mutable =
        MutableStateFlow(
            TocHostSessionState(
                tab = (saved.get<Int>("tocHost.tab") ?: 0).coerceIn(0, 2),
                searchOpen = saved.get<Boolean>("tocHost.searchOpen") == true,
                menuOpen = saved.get<Boolean>("tocHost.menuOpen") == true,
                selectionStart = saved.get<Int>("tocHost.selectionStart") ?: 0,
                selectionEnd = saved.get<Int>("tocHost.selectionEnd") ?: 0,
            )
        )
    val state: StateFlow<TocHostSessionState> = mutable
    private var revision = saved.get<Long>("tocHost.revision") ?: 0L
    private var generation = 0L
    private var reading: Job? = null
    private var expectedUrl: String? = null

    fun bind(bookUrl: String) {
        if (expectedUrl == bookUrl && (reading?.isActive == true || state.value.ready)) return
        expectedUrl = bookUrl
        reading?.cancel()
        val current = ++generation
        mutable.value = state.value.copy(ready = false, error = null)
        reading = viewModelScope.launch {
            try {
                val previous = repository.read(session)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                val accepted = repository.claim(session, bookUrl, owner)
                currentCoroutineContext().ensureActive()
                if (current != generation) return@launch
                revision = accepted.revision
                saved["tocHost.revision"] = revision
                val query = accepted.query
                val changedOwner = previous != null && previous.bookUrl != bookUrl
                if (changedOwner) {
                    saved["tocHost.tab"] = 0
                    saved["tocHost.searchOpen"] = false
                    saved["tocHost.menuOpen"] = false
                }
                mutable.value =
                    state.value.copy(
                        ready = true,
                        bookUrl = bookUrl,
                        query = query,
                        tab = if (changedOwner) 0 else state.value.tab,
                        searchOpen = !changedOwner && state.value.searchOpen,
                        menuOpen = !changedOwner && state.value.menuOpen,
                        selectionStart = state.value.selectionStart.coerceIn(0, query.length),
                        selectionEnd = state.value.selectionEnd.coerceIn(0, query.length),
                        error = null,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation)
                    mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun query(value: String, start: Int = value.length, end: Int = start) {
        if (!state.value.ready) return
        mutable.value =
            state.value.copy(
                query = value,
                selectionStart = start.coerceIn(0, value.length),
                selectionEnd = end.coerceIn(0, value.length),
                error = null,
            )
        saveSelection()
        persist()
    }

    fun select(start: Int, end: Int) {
        mutable.value =
            state.value.copy(
                selectionStart = start.coerceIn(0, state.value.query.length),
                selectionEnd = end.coerceIn(0, state.value.query.length),
            )
        saveSelection()
    }

    private fun saveSelection() {
        saved["tocHost.selectionStart"] = state.value.selectionStart
        saved["tocHost.selectionEnd"] = state.value.selectionEnd
    }

    fun tab(value: Int) {
        if (!state.value.ready || value !in 0..2) return
        saved["tocHost.tab"] = value
        mutable.value = state.value.copy(tab = value, menuOpen = false)
        saved["tocHost.menuOpen"] = false
    }

    fun search(open: Boolean) {
        if (!state.value.ready) return
        saved["tocHost.searchOpen"] = open
        mutable.value = state.value.copy(searchOpen = open, menuOpen = false)
        saved["tocHost.menuOpen"] = false
    }

    fun menu(open: Boolean) {
        if (!state.value.ready) return
        saved["tocHost.menuOpen"] = open
        mutable.value = state.value.copy(menuOpen = open)
    }

    private fun persist() {
        val url = state.value.bookUrl ?: return
        val current = generation
        val value = TocHostSession(url, state.value.query, ++revision, owner)
        saved["tocHost.revision"] = revision
        viewModelScope.launch {
            try {
                val accepted = repository.write(session, value)
                currentCoroutineContext().ensureActive()
                if (current == generation && value.revision == revision) {
                    mutable.value =
                        if (accepted) state.value.copy(error = null)
                        else state.value.copy(ready = false, error = "目录会话已由其他页面接管，请重试")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation && value.revision == revision)
                    mutable.value = state.value.copy(error = error.localizedMessage ?: "Error")
            }
        }
    }

    fun retry() {
        if (state.value.ready) persist() else expectedUrl?.let(::bind)
    }

    fun stop() {
        viewModelScope.cancel()
    }

    override fun onCleared() {
        stop()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            runCatching { repository.release(session, owner) }
        }
        super.onCleared()
    }
}
