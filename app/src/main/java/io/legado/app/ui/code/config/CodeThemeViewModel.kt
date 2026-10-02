package io.legado.app.ui.code.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.CodeThemePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val AUTO = "code.theme.auto"
private const val LIGHT = "code.theme.light"
private const val DARK = "code.theme.dark"
private const val LOADED = "code.theme.loaded"

data class CodeThemeUiState(val automatic: Boolean = false, val light: Int = 0, val dark: Int = 0,
    val systemDark: Boolean = false, val loading: Boolean = true, val error: String? = null) {
    val usesDark: Boolean get() = automatic && systemDark
    val selected: Int get() = if (usesDark) dark else light
}

class CodeThemeViewModel(private val preferences: CodeThemePreferences, private val saved: SavedStateHandle) : ViewModel() {
    private var loadJob: Job? = null
    private val mutableState = MutableStateFlow(CodeThemeUiState(saved[AUTO] ?: false,
        (saved.get<Int>(LIGHT) ?: 0).coerceIn(0, 7), (saved.get<Int>(DARK) ?: 0).coerceIn(0, 7), loading = saved.get<Boolean>(LOADED) != true))
    val state = mutableState.asStateFlow()
    init { if (state.value.loading) load() }
    fun load() {
        if (loadJob?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val loaded = preferences.load()
                coroutineContext.ensureActive()
                if (!saved.contains(AUTO)) saved[AUTO] = loaded.automatic
                if (!saved.contains(LIGHT)) saved[LIGHT] = loaded.light.coerceIn(0, 7)
                if (!saved.contains(DARK)) saved[DARK] = loaded.dark.coerceIn(0, 7)
                saved[LOADED] = true
                mutableState.value = state.value.copy(automatic = saved[AUTO]!!, light = saved[LIGHT]!!,
                    dark = saved[DARK]!!, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loading = false, error = error.localizedMessage ?: error.toString()) }
        }
    }
    fun setSystemDark(dark: Boolean) { mutableState.value = state.value.copy(systemDark = dark) }
    fun setAutomatic(automatic: Boolean) {
        saved[AUTO] = automatic; preferences.saveAutomatic(automatic)
        mutableState.value = state.value.copy(automatic = automatic)
    }
    fun select(index: Int) {
        val selected = index.coerceIn(0, 7)
        val dark = state.value.usesDark
        saved[if (dark) DARK else LIGHT] = selected
        preferences.saveTheme(dark, selected)
        mutableState.value = if (dark) state.value.copy(dark = selected) else state.value.copy(light = selected)
    }
}
