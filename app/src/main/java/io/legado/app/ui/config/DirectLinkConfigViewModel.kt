package io.legado.app.ui.config

import io.legado.app.utils.launchCleanup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DirectLinkConfigState(
    val loading: Boolean = true,
    val session: DirectLinkSession? = null,
    val defaults: List<DirectLinkDraft> = emptyList(),
    val testing: Boolean = false,
    val saving: Boolean = false,
    val issue: DirectLinkIssue? = null,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val busy
        get() = loading || testing || saving
}

class DirectLinkConfigViewModel(
    private val repository: DirectLinkConfigRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(DirectLinkConfigState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var writer: Job? = null
    private var testing: Job? = null
    private var draftFailure: String? = null
    private var stopped = false

    init {
        load()
    }

    fun load() {
        if (stopped || loading?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        loading = viewModelScope.launch {
            var created: DirectLinkSession? = null
            try {
                val value =
                    state.value.session
                        ?: withContext(NonCancellable) {
                            repository.open(saved.get<String>("directLink.session")).also {
                                created = it
                            }
                        }
                currentCoroutineContext().ensureActive()
                saved["directLink.session"] = value.id
                created = null
                mutable.value =
                    state.value.copy(loading = false, session = value, finished = value.finished)
                val defaults = repository.defaults()
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(defaults = defaults)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(loading = false, error = error.localizedMessage ?: "ERROR")
            } finally {
                created
                    ?.takeIf { saved.get<String>("directLink.session") != it.id }
                    ?.let {
                        withContext(NonCancellable) { runCatching { repository.release(it.id) } }
                    }
            }
        }
    }

    fun edit(change: (DirectLinkDraft) -> DirectLinkDraft) {
        if (stopped || state.value.busy || state.value.finished) return
        val value = state.value.session ?: return
        update(
            value.copy(draft = change(value.draft), result = null, revision = value.revision + 1)
        )
    }

    fun preset(index: Int) {
        state.value.defaults.getOrNull(index)?.let { value -> edit { value } }
    }

    fun paste(text: String?) {
        if (stopped || state.value.busy || state.value.finished) return
        val draft = runCatching {
            DirectLinkDraft.from(GSON.fromJsonObject<DirectLinkUpload.Rule>(text).getOrThrow())
        }
            .getOrNull()
        if (draft == null) mutable.value = state.value.copy(issue = DirectLinkIssue.Clipboard)
        else edit { draft }
    }

    fun copy(): String? {
        val draft = validate() ?: return null
        return GSON.toJson(draft.rule())
    }

    private fun validate(): DirectLinkDraft? {
        if (stopped || state.value.busy || state.value.finished) return null
        val draft = state.value.session?.draft ?: return null
        val issue = draft.issue()
        mutable.value = state.value.copy(issue = issue)
        return draft.takeIf { issue == null }
    }

    private fun update(value: DirectLinkSession) {
        mutable.value = state.value.copy(session = value, issue = null, error = null)
        val previous = writer
        writer = viewModelScope.launch {
            previous?.join()
            try {
                withContext(NonCancellable) { repository.write(value) }
                currentCoroutineContext().ensureActive()
                draftFailure = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                draftFailure = error.localizedMessage ?: "ERROR"
                mutable.value = state.value.copy(error = draftFailure)
            }
        }
    }

    private suspend fun flush() {
        writer?.join()
        draftFailure?.let { error(it) }
    }

    fun test() {
        val draft = validate() ?: return
        mutable.value = state.value.copy(testing = true, error = null)
        testing = viewModelScope.launch {
            try {
                flush()
                val result =
                    try {
                        repository.test(draft)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        error.localizedMessage ?: "ERROR"
                    }
                currentCoroutineContext().ensureActive()
                val value = state.value.session ?: return@launch
                val next = value.copy(result = result, revision = value.revision + 1)
                withContext(NonCancellable) { repository.write(next) }
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(session = next, testing = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(testing = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    fun cancelTest() {
        testing?.cancel()
        mutable.value = state.value.copy(testing = false)
    }

    fun clearResult() {
        if (state.value.busy || stopped) return
        state.value.session?.let { update(it.copy(result = null, revision = it.revision + 1)) }
    }

    fun save() {
        val draft = validate() ?: return
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                flush()
                val current = checkNotNull(state.value.session)
                val next = current.copy(finished = true, revision = current.revision + 1)
                withContext(NonCancellable) {
                    repository.save(draft)
                    repository.write(next)
                }
                currentCoroutineContext().ensureActive()
                mutable.value = state.value.copy(session = next, saving = false, finished = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value =
                    state.value.copy(saving = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    fun retry() {
        if (draftFailure != null) retryDraft() else load()
    }

    fun retryDraft() {
        if (state.value.busy || stopped) return
        state.value.session?.let { update(it.copy(revision = it.revision + 1)) }
    }

    fun close() {
        if (state.value.saving || stopped) return
        mutable.value = state.value.copy(finished = true)
        release()
        stop()
    }

    fun release() {
        saved.remove<String>("directLink.session")?.let { id ->
            viewModelScope.launchCleanup { runCatching { repository.release(id) } }
        }
    }

    fun stop() {
        stopped = true
        viewModelScope.cancel()
    }
}
