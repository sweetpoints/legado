package io.legado.app.ui.highlight.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HighlightColorPickerState(val value: Int, val hex: String, val invalid: Boolean = false,
    val revision: Long = 0, val finished: Boolean = false, val saved: Boolean = false)

/** A separate persisted color transaction: preview edits never mutate the editor until Save. */
internal class HighlightColorPickerViewModel(private val savedState: SavedStateHandle, initial: Int, val withAlpha: Boolean,
    val nonce: String, val presets: List<Int>) : ViewModel() {
    private val initialValue = savedState.get<Int>("value") ?: initial
    private val mutable = MutableStateFlow(HighlightColorPickerState(initialValue,
        savedState.get<String>("hex") ?: format(initialValue), savedState["invalid"] ?: false,
        savedState["revision"] ?: 0L, savedState["finished"] ?: false, savedState["saved"] ?: false))
    val state = mutable.asStateFlow()
    private fun format(value: Int) = if (withAlpha) "#%08X".format(value) else "#%06X".format(value and 0xffffff)
    private fun update(value: HighlightColorPickerState) {
        mutable.value = value.copy(revision = state.value.revision + 1)
        savedState["value"] = value.value; savedState["hex"] = value.hex; savedState["invalid"] = value.invalid
        savedState["revision"] = state.value.revision; savedState["finished"] = value.finished; savedState["saved"] = value.saved
    }
    fun hex(text: String) {
        check(!state.value.finished)
        val clean = text.removePrefix("#")
        val parsed = clean.takeIf { it.length == (if (withAlpha) 8 else 6) && it.all { c -> c in "0123456789abcdefABCDEF" } }?.toLongOrNull(16)
        val value = parsed?.let { if (withAlpha) it.toInt() else it.toInt() or 0xff000000.toInt() } ?: state.value.value
        update(state.value.copy(value = value, hex = text, invalid = parsed == null))
    }
    fun preset(index: Int) {
        check(!state.value.finished); require(index in presets.indices)
        val value = presets[index]; update(state.value.copy(value = value, hex = format(value), invalid = false))
    }
    fun channel(channel: String, value: Int) {
        check(!state.value.finished); require(value in 0..255)
        val shift = when (channel) { "A" -> { require(withAlpha); 24 }; "R" -> 16; "G" -> 8; "B" -> 0; else -> error("Unknown channel") }
        val next = (state.value.value and (255 shl shift).inv()) or (value shl shift)
        update(state.value.copy(value = next, hex = format(next), invalid = false))
    }
    fun finish(save: Boolean) {
        if (state.value.finished) return
        if (save) check(!state.value.invalid)
        update(state.value.copy(finished = true, saved = save))
    }
    fun consumeResult(): Pair<Boolean, Int>? {
        if (!state.value.finished || savedState.get<Boolean>("delivered") == true) return null
        savedState["delivered"] = true
        return state.value.saved to state.value.value
    }
}
