package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.WordCountFilterRepository
import io.legado.app.data.preferences.WordCountFilterSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WordCountFilterState(
    val mode: Int? = null,
    val minimum: String = "",
    val maximum: String = "",
    val invalid: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val reload: Boolean? = null,
)

class WordCountFilterViewModel(
    private val repository: WordCountFilterRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            WordCountFilterState(
                saved["wordCount.mode"],
                saved["wordCount.min"] ?: "",
                saved["wordCount.max"] ?: "",
                finished = saved["wordCount.finished"] ?: false,
                reload = saved["wordCount.reload"],
            )
        )
    val state = mutable.asStateFlow()

    fun choose(mode: Int) {
        if (state.value.finished || mode !in 0..2) return
        val current = repository.load()
        if (mode == 0) {
            commit(current.copy(mode = 0), false)
            return
        }
        val same = mode == current.mode
        mutable.value =
            state.value.copy(
                mode = mode,
                minimum = (if (same) current.minimum else if (mode == 2) 70 else 1000).toString(),
                maximum = (if (same) current.maximum else if (mode == 2) 130 else 5000).toString(),
                invalid = false,
                error = null,
            )
        persist()
    }

    fun minimum(value: String) {
        if (!state.value.finished) {
            mutable.value = state.value.copy(minimum = value, invalid = false, error = null)
            persist()
        }
    }

    fun maximum(value: String) {
        if (!state.value.finished) {
            mutable.value = state.value.copy(maximum = value, invalid = false, error = null)
            persist()
        }
    }

    fun confirm() {
        if (state.value.finished) return
        val mode = state.value.mode ?: return
        val min = state.value.minimum.toIntOrNull()
        val max = state.value.maximum.toIntOrNull()
        if (min == null || max == null || min < 0 || max < min) {
            mutable.value = state.value.copy(invalid = true)
            return
        }
        commit(WordCountFilterSettings(mode, min, max), true)
    }

    private fun commit(next: WordCountFilterSettings, reload: Boolean) {
        try {
            val changed = repository.load() != next
            if (changed) repository.save(next)
            mutable.value = state.value.copy(finished = true, reload = reload.takeIf { changed })
            persist()
        } catch (error: Exception) {
            mutable.value = state.value.copy(error = error.localizedMessage ?: error.toString())
        }
    }

    fun consumeReload(): Boolean? {
        val reload = state.value.reload ?: return null
        mutable.value = state.value.copy(reload = null)
        persist()
        return reload
    }

    fun close() {
        if (!state.value.finished) {
            mutable.value = state.value.copy(finished = true, reload = null)
            persist()
        }
    }

    private fun persist() {
        saved["wordCount.mode"] = state.value.mode
        saved["wordCount.min"] = state.value.minimum
        saved["wordCount.max"] = state.value.maximum
        saved["wordCount.finished"] = state.value.finished
        saved["wordCount.reload"] = state.value.reload
    }
}
