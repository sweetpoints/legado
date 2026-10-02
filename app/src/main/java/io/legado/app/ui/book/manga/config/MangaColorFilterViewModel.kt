package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.MangaColorFilterRepository
import io.legado.app.data.preferences.MangaColorFilterValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MangaColorChannel { BRIGHTNESS, RED, GREEN, BLUE, ALPHA }

data class MangaColorFilterUiState(
    val values: MangaColorFilterValues = MangaColorFilterValues(),
    val loading: Boolean = true,
    val error: String? = null,
    val previewRevision: Int = 0,
    val finished: Boolean = false,
)

class MangaColorFilterViewModel(
    private val repository: MangaColorFilterRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private var initialized = savedState.get<Boolean>("manga.filter.initialized") == true
    private var edited = false
    private var loadJob: Job? = null
    private val mutableState = MutableStateFlow(MangaColorFilterUiState(
        values = MangaColorFilterValues(savedState["manga.filter.l"] ?: 0, savedState["manga.filter.r"] ?: 0,
            savedState["manga.filter.g"] ?: 0, savedState["manga.filter.b"] ?: 0,
            savedState["manga.filter.a"] ?: 0).bounded(),
        loading = !initialized,
        previewRevision = if (initialized) 1 else 0,
    ))
    val state = mutableState.asStateFlow()

    init { if (!initialized) load() }

    fun load() {
        if (state.value.finished || loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val values = repository.load().bounded()
                coroutineContext.ensureActive()
                if (!edited && !state.value.finished) {
                    persist(values)
                    mutableState.update { it.copy(values = values) }
                }
                mutableState.update { it.copy(loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(loading = false, error = error.localizedMessage ?: error.toString()) }
            }
        }
    }

    fun change(channel: MangaColorChannel, value: Int) {
        if (state.value.finished) return
        val bounded = value.coerceIn(0, 255)
        val old = state.value.values
        val values = when (channel) {
            MangaColorChannel.BRIGHTNESS -> old.copy(brightness = bounded)
            MangaColorChannel.RED -> old.copy(red = bounded)
            MangaColorChannel.GREEN -> old.copy(green = bounded)
            MangaColorChannel.BLUE -> old.copy(blue = bounded)
            MangaColorChannel.ALPHA -> old.copy(alpha = bounded)
        }
        edited = true
        persist(values)
        mutableState.update { it.copy(values = values, error = null, previewRevision = it.previewRevision + 1) }
    }

    private fun persist(values: MangaColorFilterValues) {
        initialized = true
        savedState["manga.filter.initialized"] = true
        savedState["manga.filter.l"] = values.brightness
        savedState["manga.filter.r"] = values.red
        savedState["manga.filter.g"] = values.green
        savedState["manga.filter.b"] = values.blue
        savedState["manga.filter.a"] = values.alpha
    }

    /** Called only for a real dismissal, never for configuration destruction. */
    fun finish() {
        if (state.value.finished) return
        loadJob?.cancel()
        if (initialized) repository.save(state.value.values)
        mutableState.update { it.copy(loading = false, finished = true) }
    }
}
