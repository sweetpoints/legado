package io.legado.app.ui.book.audio

import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AudioSliderMode {
    Timer,
    Speed,
}

internal data class AudioSliderState(val mode: AudioSliderMode, val value: Float) {
    val range
        get() = if (mode == AudioSliderMode.Timer) 0f..180f else .5f..3f

    val steps
        get() = if (mode == AudioSliderMode.Timer) 179 else 24
}

internal fun normalizeAudioSlider(mode: AudioSliderMode, value: Float): Float =
    if (mode == AudioSliderMode.Timer) value.roundToInt().coerceIn(0, 180).toFloat()
    else (value * 10).roundToInt().coerceIn(5, 30) / 10f

/** Programmatic refresh never dispatches playback changes. */
internal class AudioSliderController(mode: AudioSliderMode, private val changed: (Float) -> Unit) {
    private val mutable =
        MutableStateFlow(AudioSliderState(mode, if (mode == AudioSliderMode.Timer) 0f else 1f))
    val state = mutable.asStateFlow()

    fun refresh(value: Float) {
        if (value.isFinite())
            mutable.value = state.value.copy(value = normalizeAudioSlider(state.value.mode, value))
    }

    fun user(value: Float) {
        if (!value.isFinite()) return
        val normalized = normalizeAudioSlider(state.value.mode, value)
        if (normalized == state.value.value) return
        mutable.value = state.value.copy(value = normalized)
        changed(normalized)
    }
}
