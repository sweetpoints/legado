package io.legado.app.ui.book.changecover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppPattern
import io.legado.app.data.repository.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class ChangeCoverState(
    val snapshot: ChangeCoverSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val finished: Boolean = false,
    val selected: String? = null,
)

internal class ChangeCoverComposeViewModel(
    private val repository: ChangeCoverRepository,
    private val saved: SavedStateHandle,
    name: String,
    author: String,
) : ViewModel() {
    private val target = ChangeCoverTarget(name, author.replace(AppPattern.authorRegex, ""))
    val session: String =
        saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable =
        MutableStateFlow(
            ChangeCoverState(
                loading = saved.get<Boolean>("finished") != true,
                finished = saved["finished"] ?: false,
            )
        )
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var revision = 0L

    init {
        if (saved.get<String>("selectedId") != null) restoreSelection()
        else if (!state.value.finished) load()
    }

    private fun restoreSelection() {
        val id = saved.get<String>("selectedId") ?: return
        mutable.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val url = repository.selected(session, id)
                mutable.value = state.value.copy(loading = false, selected = url)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(loading = false, error = error.localizedMessage.orEmpty())
            }
        }
    }

    private fun load() {
        val token = ++generation
        job?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val initial = repository.initial(session, target)
                if (token != generation || state.value.finished) return@launch
                revision = maxOf(revision, initial.snapshot.revision)
                mutable.value = state.value.copy(snapshot = initial.snapshot, loading = false)
                if (initial.autoSearch) search(initial.snapshot.status == ChangeCoverStatus.Running)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                if (token == generation && !state.value.finished)
                    mutable.value =
                        state.value.copy(loading = false, error = error.localizedMessage.orEmpty())
            }
        }
    }

    fun startStop() {
        if (state.value.finished || state.value.loading) return
        val current = state.value.snapshot ?: return
        if (current.status == ChangeCoverStatus.Running) stop()
        else search(current.status == ChangeCoverStatus.RuleReady)
    }

    fun retry() {
        if (state.value.finished) {
            if (saved.get<String>("selectedId") != null && state.value.selected == null)
                restoreSelection()
            return
        }
        if (state.value.snapshot == null) load()
        else
            search(
                state.value.snapshot!!.pending.isNotEmpty() ||
                    state.value.snapshot!!.status == ChangeCoverStatus.RuleReady
            )
    }

    private fun search(resume: Boolean) {
        val initial = state.value.snapshot ?: return
        val token = ++generation
        job?.cancel()
        mutable.value = state.value.copy(error = null)
        job = viewModelScope.launch {
            try {
                repository.search(initial, resume).collect { snapshot ->
                    if (token != generation || state.value.finished) return@collect
                    val versioned = snapshot.copy(revision = ++revision)
                    mutable.value = state.value.copy(snapshot = versioned, loading = false)
                    repository.save(session, versioned)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                if (token == generation && !state.value.finished)
                    mutable.value =
                        state.value.copy(loading = false, error = error.localizedMessage.orEmpty())
            }
        }
    }

    fun stop() {
        if (state.value.finished) return
        generation++
        job?.cancel()
        val current = state.value.snapshot ?: return
        val stopped = current.copy(status = ChangeCoverStatus.Idle, revision = ++revision)
        mutable.value = state.value.copy(snapshot = stopped)
        viewModelScope.launch {
            try {
                repository.save(session, stopped)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                if (!state.value.finished && state.value.snapshot?.revision == stopped.revision)
                    mutable.value = state.value.copy(error = error.localizedMessage.orEmpty())
            }
        }
    }

    fun select(id: String) {
        if (state.value.finished) return
        val snapshot = state.value.snapshot ?: return
        val cover = snapshot.covers.find { it.id == id } ?: return
        generation++
        job?.cancel()
        val receipt = snapshot.copy(status = ChangeCoverStatus.Idle, revision = ++revision)
        mutable.value =
            state.value.copy(snapshot = receipt, finished = true, loading = true, error = null)
        job = viewModelScope.launch {
            try {
                repository.save(session, receipt)
                saved["finished"] = true
                saved["selectedId"] = cover.id
                mutable.value = state.value.copy(loading = false, selected = cover.coverUrl)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(
                        finished = false,
                        loading = false,
                        error = error.localizedMessage.orEmpty(),
                    )
            }
        }
    }

    fun consume(value: String) {
        if (state.value.selected == value) {
            saved.remove<String>("selectedId")
            mutable.value = state.value.copy(selected = null)
        }
    }
}
