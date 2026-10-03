package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConfigSearchState(
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val draft: ConfigSearchDraft = ConfigSearchDraft(),
    val error: String? = null,
) {
    val editable
        get() = loaded && !saving && error == null && draft.request == null
}

/** Search chrome state and exact native queries are restored without saving text in a Bundle. */
internal class ConfigSearchViewModel(
    private val sessions: ConfigSearchSessionRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val id = saved.get<String>(KEY) ?: UUID.randomUUID().toString().also { saved[KEY] = it }
    private val mutable = MutableStateFlow(ConfigSearchState())
    val state = mutable.asStateFlow()
    private val writes = Mutex()
    private val edits = Channel<ConfigSearchDraft>(Channel.CONFLATED)
    private var revision = 0L
    private var stopped = false
    private var initializing: Job? = null

    private fun next() = maxOf(System.nanoTime(), revision + 1).also { revision = it }

    init {
        initialize()
        viewModelScope.launch {
            for (draft in edits) try {
                writes.withLock { sessions.write(id, draft) }
                ensureActive()
                if (!stopped && state.value.draft.revision == draft.revision)
                    mutable.update { it.copy(error = null) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                ensureActive()
                if (!stopped && state.value.draft.revision == draft.revision) fail(error)
            }
        }
    }

    private fun fail(error: Exception) {
        mutable.update {
            it.copy(saving = false, error = error.localizedMessage ?: error.javaClass.simpleName)
        }
    }

    private fun initialize() {
        initializing?.cancel()
        mutable.update { it.copy(saving = true, error = null) }
        initializing = viewModelScope.launch {
            try {
                var draft = sessions.read(id)
                ensureActive()
                revision = maxOf(revision, draft.revision)
                if (draft.request?.token == saved.get<String>(CONSUMED) && draft.request != null)
                    draft = draft.copy(request = null, revision = next())
                writes.withLock { sessions.write(id, draft) }
                ensureActive()
                if (!stopped) mutable.value = ConfigSearchState(loaded = true, draft = draft)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                ensureActive()
                if (!stopped) fail(error)
            }
        }
    }

    private fun edit(transform: (ConfigSearchDraft) -> ConfigSearchDraft) {
        if (stopped || !state.value.editable) return
        val draft = transform(state.value.draft).copy(revision = next())
        mutable.update { it.copy(draft = draft) }
        edits.trySend(draft)
    }

    fun text(value: String, start: Int = value.length, end: Int = start) = edit {
        it.copy(
            text = value,
            start = start.coerceIn(0, value.length),
            end = end.coerceIn(0, value.length),
            owner = null,
        )
    }

    fun searching(value: Boolean) = edit { it.copy(searching = value, owner = null) }

    fun submit() {
        if (!state.value.editable || stopped) return
        val query = state.value.draft.text.trim().takeIf { it.isNotEmpty() } ?: return
        val token = UUID.randomUUID().toString()
        persistDraft(
            state.value.draft.copy(
                request = ConfigSearchRequest(token, query),
                owner = token,
                revision = next(),
            )
        )
    }

    private fun persistDraft(draft: ConfigSearchDraft) {
        mutable.update { it.copy(draft = draft, saving = true, error = null) }
        viewModelScope.launch {
            try {
                writes.withLock { sessions.write(id, draft) }
                ensureActive()
                if (!stopped) mutable.update { it.copy(saving = false) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                ensureActive()
                if (!stopped) fail(error)
            }
        }
    }

    fun retry() {
        if (stopped || state.value.saving) return
        if (!state.value.loaded) initialize() else persistDraft(state.value.draft)
    }

    suspend fun claim(token: String, ready: () -> Boolean): ConfigSearchRequest? {
        val caller = currentCoroutineContext()
        return writes.withLock {
            caller.ensureActive()
            if (
                stopped ||
                    !state.value.loaded ||
                    state.value.saving ||
                    state.value.error != null ||
                    !ready()
            )
                return@withLock null
            val before = state.value.draft
            val request = before.request?.takeIf { it.token == token } ?: return@withLock null
            mutable.update { it.copy(saving = true) }
            try {
                val after = before.copy(request = null, revision = next())
                withContext(NonCancellable) { sessions.write(id, after) }
                if (!caller.isActive || stopped || !ready()) {
                    val restored = before.copy(revision = next())
                    withContext(NonCancellable) { sessions.write(id, restored) }
                    if (!stopped) mutable.update { it.copy(draft = restored) }
                    caller.ensureActive()
                    return@withLock null
                }
                saved[CONSUMED] = token
                mutable.update { it.copy(draft = after) }
                request
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                caller.ensureActive()
                if (!stopped) fail(error)
                throw error
            } finally {
                if (!stopped) mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun selected(token: String) {
        if (state.value.draft.owner == token)
            edit { it.copy(searching = false, text = "", start = 0, end = 0, owner = null) }
    }

    suspend fun flush() = writes.withLock {
        if (state.value.loaded) sessions.write(id, state.value.draft)
    }

    fun stop() {
        stopped = true
        initializing?.cancel()
        viewModelScope.cancel()
        edits.close()
    }

    suspend fun release() = sessions.release(id)

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val KEY = "config.search.session"
        const val CONSUMED = "config.search.consumed"
    }
}
