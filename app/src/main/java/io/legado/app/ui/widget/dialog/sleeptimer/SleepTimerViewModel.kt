package io.legado.app.ui.widget.dialog.sleeptimer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.SleepTimerMode
import io.legado.app.data.preferences.SleepTimerPreferences
import io.legado.app.service.MAX_CHAPTER_STOP_COUNT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SleepTimerSelection(val mode: SleepTimerMode, val value: Int)

data class SleepTimerUiState(
    val minute: Int = 0,
    val chapter: Int = 0,
    val useEpisodes: Boolean = false,
    val customMode: SleepTimerMode? = null,
    val input: String = "",
    val showValidation: Boolean = false,
    val pending: SleepTimerSelection? = null,
    val finished: Boolean = false,
) {
    val maxCustom
        get() =
            if (customMode == SleepTimerMode.Chapters) MAX_CHAPTER_STOP_COUNT else MAX_SLEEP_MINUTES

    val isActive
        get() = chapter > 0 || minute > 0

    val isBusy
        get() = pending != null || finished
}

const val MAX_SLEEP_MINUTES = 180
val SLEEP_MINUTE_PRESETS = listOf(15, 30, 45, 60)
val SLEEP_CHAPTER_PRESETS = listOf(1, 2, 3, 5)

class SleepTimerViewModel(
    private val preferences: SleepTimerPreferences,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            SleepTimerUiState(
                minute = (savedState.get<Int>("minute") ?: 0).coerceIn(0, MAX_SLEEP_MINUTES),
                chapter = (savedState.get<Int>("chapter") ?: 0).coerceIn(0, MAX_CHAPTER_STOP_COUNT),
                useEpisodes = savedState["episodes"] ?: false,
                customMode =
                    savedState.get<String>("sleep.customMode")?.let(SleepTimerMode::valueOf),
                input = savedState["sleep.input"] ?: "",
                pending =
                    savedState.get<String>("sleep.pendingMode")?.let {
                        SleepTimerSelection(
                            SleepTimerMode.valueOf(it),
                            savedState["sleep.pendingValue"] ?: 0,
                        )
                    },
                finished = savedState["sleep.finished"] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    fun showCustom(mode: SleepTimerMode) {
        if (state.value.isBusy || state.value.customMode == mode) return
        val current =
            if (mode == SleepTimerMode.Minutes) state.value.minute else state.value.chapter
        val value = preferences.lastCustom(mode).takeIf { it > 0 } ?: current.takeIf { it > 0 }
        savedState["sleep.customMode"] = mode.name
        savedState["sleep.input"] = value?.toString().orEmpty()
        mutableState.update {
            it.copy(customMode = mode, input = value?.toString().orEmpty(), showValidation = false)
        }
    }

    fun setInput(input: String) {
        if (state.value.isBusy || input.length > 3 || !input.all(Char::isDigit)) return
        savedState["sleep.input"] = input
        mutableState.update { it.copy(input = input, showValidation = false) }
    }

    fun confirmCustom() {
        val mode = state.value.customMode ?: return
        val value = state.value.input.toIntOrNull()
        if (value == null || value !in 1..state.value.maxCustom) {
            mutableState.update { it.copy(showValidation = true) }
            return
        }
        select(mode, value, custom = true)
    }

    fun selectPreset(mode: SleepTimerMode, value: Int) {
        val presets =
            if (mode == SleepTimerMode.Minutes) SLEEP_MINUTE_PRESETS else SLEEP_CHAPTER_PRESETS
        if (value in presets) select(mode, value, custom = false)
    }

    fun turnOff() {
        if (state.value.isActive) select(SleepTimerMode.Minutes, 0, custom = false)
    }

    private fun select(mode: SleepTimerMode, value: Int, custom: Boolean) {
        if (state.value.isBusy) return
        if (value > 0) preferences.preferChapters(mode == SleepTimerMode.Chapters)
        if (custom) preferences.rememberCustom(mode, value)
        savedState["sleep.pendingMode"] = mode.name
        savedState["sleep.pendingValue"] = value
        mutableState.update {
            it.copy(pending = SleepTimerSelection(mode, value), showValidation = false)
        }
    }

    /**
     * Consume before platform delivery so rotation or a callback-triggered recomposition cannot
     * repeat it.
     */
    fun consumeSelection(): SleepTimerSelection? {
        val pending = state.value.pending ?: return null
        savedState.remove<String>("sleep.pendingMode")
        savedState.remove<Int>("sleep.pendingValue")
        savedState["sleep.finished"] = true
        mutableState.update { it.copy(pending = null, finished = true) }
        return pending
    }
}
