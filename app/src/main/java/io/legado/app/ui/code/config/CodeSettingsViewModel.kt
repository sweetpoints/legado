package io.legado.app.ui.code.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.CodeSettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val FONT = "code.settings.font"
private const val AUTO = "code.settings.auto"
private const val FLAGS = "code.settings.flags"
private const val BASE = "code.settings.baseline"
private const val PICKER = "code.settings.picker"

data class CodeSettingsUiState(
    val font: Int = 16,
    val autoComplete: Boolean = true,
    val nonPrintable: Int = 0,
    val loading: Boolean = true,
    val fontPicker: Boolean = false,
    val error: String? = null,
)

class CodeSettingsViewModel(
    private val preferences: CodeSettingsPreferences,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var closed = false
    private var job: Job? = null
    private val mutableState =
        MutableStateFlow(
            CodeSettingsUiState(
                saved[FONT] ?: 16,
                saved[AUTO] ?: true,
                saved[FLAGS] ?: 0,
                !saved.contains(BASE),
                saved[PICKER] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    init {
        if (state.value.loading) load()
    }

    fun load() {
        if (closed || job?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val loaded = preferences.load()
                coroutineContext.ensureActive()
                if (closed) return@launch
                if (!saved.contains(FONT)) saved[FONT] = loaded.font.coerceIn(9, 36)
                if (!saved.contains(AUTO)) saved[AUTO] = loaded.autoComplete
                if (!saved.contains(FLAGS)) saved[FLAGS] = loaded.nonPrintable
                saved[BASE] = loaded.nonPrintable
                mutableState.value =
                    state.value.copy(
                        font = saved[FONT]!!,
                        autoComplete = saved[AUTO]!!,
                        nonPrintable = saved[FLAGS]!!,
                        loading = false,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    state.value.copy(
                        loading = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    fun setFont(value: Int) {
        if (closed) return
        val font = value.coerceIn(9, 36)
        saved[FONT] = font
        preferences.saveFont(font)
        mutableState.value = state.value.copy(font = font)
    }

    fun setAutoComplete(value: Boolean) {
        if (closed) return
        saved[AUTO] = value
        preferences.saveAutoComplete(value)
        mutableState.value = state.value.copy(autoComplete = value)
    }

    fun toggleFlag(flag: Int) {
        if (closed) return
        val flags = state.value.nonPrintable xor flag
        saved[FLAGS] = flags
        mutableState.value = state.value.copy(nonPrintable = flags)
    }

    fun showFontPicker(show: Boolean) {
        if (closed) return
        saved[PICKER] = show
        mutableState.value = state.value.copy(fontPicker = show)
    }

    /** Configuration destruction is not the user's close action. */
    fun onDismiss(changingConfigurations: Boolean): Int? {
        if (changingConfigurations || closed) return null
        closed = true
        job?.cancel()
        val baseline = saved.get<Int>(BASE) ?: return null
        val flags = state.value.nonPrintable
        if (flags == baseline) return null
        preferences.saveNonPrintable(flags)
        return flags
    }
}
