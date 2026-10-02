package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.MangaEpaperPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val THRESHOLD = "manga.epaper.threshold"
private const val EDITED = "manga.epaper.edited"

data class MangaEpaperUiState(
    val threshold: Int = 150,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class MangaEpaperViewModel(
    private val preferences: MangaEpaperPreferences,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private var edited = savedState.get<Boolean>(EDITED) == true
    private var dismissed = false
    private val restored = savedState.contains(THRESHOLD)
    private val mutableState = MutableStateFlow(MangaEpaperUiState(
        threshold = (savedState.get<Int>(THRESHOLD) ?: 150).coerceIn(0, 255),
        isLoading = !restored,
    ))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { if (!restored) load() }

    fun load() {
        if (dismissed || loadJob?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val threshold = preferences.loadThreshold().coerceIn(0, 255)
                coroutineContext.ensureActive()
                if (!edited) {
                    savedState[THRESHOLD] = threshold
                    mutableState.update { it.copy(threshold = threshold) }
                }
                mutableState.update { it.copy(isLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isLoading = false, error = error.localizedMessage ?: error.toString()) }
            }
        }
    }

    fun setThreshold(threshold: Int) {
        if (dismissed) return
        val value = threshold.coerceIn(0, 255)
        edited = true
        savedState[EDITED] = true
        savedState[THRESHOLD] = value
        mutableState.update { it.copy(threshold = value, isLoading = false, error = null) }
    }

    fun onDismiss(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations || dismissed) return
        dismissed = true
        loadJob?.cancel()
        // An unopened or unedited dialog must never replace the preference with the default.
        if (edited) preferences.saveThreshold(state.value.threshold)
    }
}
