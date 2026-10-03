package io.legado.app.ui.rss.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RssFavoriteConfigEffect(
    val id: Long,
    val action: RssFavoriteConfigAction,
    val title: String?,
    val group: String?,
)

data class RssFavoriteConfigState(
    val title: String = "",
    val group: String = "",
    val titleStart: Int = 0,
    val titleEnd: Int = 0,
    val groupStart: Int = 0,
    val groupEnd: Int = 0,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val effect: RssFavoriteConfigEffect? = null,
    val finished: Boolean = false,
) {
    val canEdit
        get() = loaded && !loading && !busy && effect == null && !finished
}

class RssFavoriteConfigViewModel(
    private val repository: RssFavoriteConfigRepository,
    private val saved: SavedStateHandle,
    requestId: String,
) : ViewModel() {
    private val id =
        saved.get<String>("rssFavorite.request")
            ?: requestId.also { saved["rssFavorite.request"] = it }
    private val mutable =
        MutableStateFlow(
            RssFavoriteConfigState(
                finished = saved.get<Boolean>("rssFavorite.finished") == true,
                loading = saved.get<Boolean>("rssFavorite.finished") != true,
            )
        )
    val state = mutable.asStateFlow()
    private var draft: RssFavoriteConfigDraft? = null
    private var work: Job? = null
    private var autosave: Job? = null

    init {
        load()
    }

    fun load() {
        if (state.value.finished || state.value.loaded || work?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val loaded = repository.load(id)
                if (state.value.finished) return@launch
                draft = loaded
                mutable.value =
                    state.value.copy(
                        title = loaded.title,
                        group = loaded.group,
                        loaded = true,
                        loading = false,
                        titleStart =
                            (saved.get<Int>("rssFavorite.titleStart") ?: 0).coerceIn(
                                0,
                                loaded.title.length,
                            ),
                        titleEnd =
                            (saved.get<Int>("rssFavorite.titleEnd") ?: 0).coerceIn(
                                0,
                                loaded.title.length,
                            ),
                        groupStart =
                            (saved.get<Int>("rssFavorite.groupStart") ?: 0).coerceIn(
                                0,
                                loaded.group.length,
                            ),
                        groupEnd =
                            (saved.get<Int>("rssFavorite.groupEnd") ?: 0).coerceIn(
                                0,
                                loaded.group.length,
                            ),
                        effect = effect(loaded),
                    )
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    fun title(value: String, start: Int, end: Int) = updateText(true, value, start, end)

    fun group(value: String, start: Int, end: Int) = updateText(false, value, start, end)

    private fun updateText(title: Boolean, value: String, start: Int, end: Int) {
        if (!state.value.canEdit) return
        val first = start.coerceIn(0, value.length)
        val last = end.coerceIn(0, value.length)
        if (title) {
            saved["rssFavorite.titleStart"] = first
            saved["rssFavorite.titleEnd"] = last
            mutable.value = state.value.copy(title = value, titleStart = first, titleEnd = last)
        } else {
            saved["rssFavorite.groupStart"] = first
            saved["rssFavorite.groupEnd"] = last
            mutable.value = state.value.copy(group = value, groupStart = first, groupEnd = last)
        }
        val current = draft ?: return
        if (current.title == state.value.title && current.group == state.value.group) return
        draft =
            current.copy(
                title = state.value.title,
                group = state.value.group,
                revision = current.revision + 1,
            )
        autosave?.cancel()
        val snapshot = draft!!
        autosave = viewModelScope.launch {
            delay(150)
            try {
                repository.write(id, snapshot)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (
                    draft?.revision == snapshot.revision &&
                        !state.value.busy &&
                        state.value.effect == null
                )
                    failure(error)
            }
        }
    }

    private fun effect(value: RssFavoriteConfigDraft): RssFavoriteConfigEffect? =
        value.action?.let {
            RssFavoriteConfigEffect(
                value.revision,
                it,
                value.title.takeIf(String::isNotBlank) ?: value.originalTitle,
                value.group.takeIf(String::isNotBlank) ?: value.originalGroup,
            )
        }

    fun confirm() = action(RssFavoriteConfigAction.Save)

    fun delete() = action(RssFavoriteConfigAction.Delete)

    private fun action(action: RssFavoriteConfigAction) {
        if (!state.value.canEdit) return
        autosave?.cancel()
        val snapshot = draft!!.copy(revision = draft!!.revision + 1, action = action)
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                repository.write(id, snapshot)
                draft = snapshot
                mutable.value = state.value.copy(busy = false, effect = effect(snapshot))
            } catch (error: Throwable) {
                failure(error)
            }
        }
    }

    /** Consume before calling the host, so fragment recreation never repeats a callback. */
    fun consume(): RssFavoriteConfigEffect? {
        val effect = state.value.effect ?: return null
        saved["rssFavorite.finished"] = true
        mutable.value = state.value.copy(effect = null, finished = true)
        return effect
    }

    fun cancel() {
        if (state.value.busy || state.value.finished) return
        work?.cancel()
        autosave?.cancel()
        saved["rssFavorite.finished"] = true
        mutable.value = state.value.copy(effect = null, finished = true, loading = false)
    }

    suspend fun flushDraft() {
        autosave?.cancel()
        if (state.value.finished || state.value.busy) return
        draft?.let { repository.write(id, it) }
    }

    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        if (!state.value.finished)
            mutable.value =
                state.value.copy(
                    loading = false,
                    busy = false,
                    error = error.localizedMessage ?: error.toString(),
                )
    }

    fun stop() {
        work?.cancel()
        autosave?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}
