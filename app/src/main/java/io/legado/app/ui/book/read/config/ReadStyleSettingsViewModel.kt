package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadStylePicker {
    Weight,
    Chinese,
    Indent,
}

enum class ReadStyleDestination {
    Padding,
    Background,
    Tip,
    Font,
}

data class ReadStyleEffect(
    val id: Long,
    val update: ReadStyleUpdate? = null,
    val destination: ReadStyleDestination? = null,
)

data class ReadStyleSettingsState(
    val settings: ReadStyleSettingsSnapshot,
    val picker: ReadStylePicker? = null,
    val pending: List<ReadStyleEffect> = emptyList(),
    val finished: Boolean = false,
)

class ReadStyleSettingsViewModel(
    private val repository: ReadStyleSettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    init {
        if (saved.get<Boolean>("readStyle.finished") != true)
            saved.get<String>("readStyle.checkpoint")?.let(repository::restore)
    }

    private var nextId = saved.get<Long>("readStyle.next") ?: 0L
    private val mutableState =
        MutableStateFlow(
            ReadStyleSettingsState(
                repository.load(),
                saved.get<String>("readStyle.picker")?.let {
                    runCatching { ReadStylePicker.valueOf(it) }.getOrNull()
                },
                GSON.fromJsonArray<ReadStyleEffect>(saved.get<String>("readStyle.effects"))
                    .getOrNull()
                    .orEmpty(),
                saved["readStyle.finished"] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    init {
        persist()
    }

    private fun persist() {
        saved["readStyle.checkpoint"] = repository.checkpoint()
        saved["readStyle.next"] = nextId
        saved["readStyle.picker"] = state.value.picker?.name
        saved["readStyle.effects"] = GSON.toJson(state.value.pending)
        saved["readStyle.finished"] = state.value.finished
    }

    private fun change(update: ReadStyleUpdate, destination: ReadStyleDestination? = null) {
        val effect =
            if (update != ReadStyleUpdate() || destination != null)
                listOf(ReadStyleEffect(++nextId, update, destination))
            else emptyList()
        mutableState.value =
            state.value.copy(settings = repository.load(), pending = state.value.pending + effect)
        persist()
    }

    fun refresh() {
        mutableState.value = state.value.copy(settings = repository.load())
        persist()
    }

    fun slider(slider: ReadStyleSlider, progress: Int) {
        if (
            state.value.finished ||
                state.value.settings.progress(slider) == progress.coerceIn(0, slider.maximum)
        )
            return
        change(repository.slider(slider, progress))
    }

    fun preset(index: Int) {
        if (!state.value.finished) change(repository.select(index))
    }

    fun shared(value: Boolean) {
        if (!state.value.finished) change(repository.shared(value))
    }

    fun animation(value: Int) {
        if (!state.value.finished && state.value.settings.pageAnimation != value)
            change(repository.animation(value))
    }

    fun picker(picker: ReadStylePicker?) {
        mutableState.value = state.value.copy(picker = picker)
        persist()
    }

    fun pick(value: Int) {
        if (state.value.finished) return
        val update =
            when (state.value.picker ?: return) {
                ReadStylePicker.Weight -> repository.weight(value)
                ReadStylePicker.Chinese -> repository.chinese(value)
                ReadStylePicker.Indent -> repository.indent(value)
            }
        mutableState.value = state.value.copy(picker = null)
        change(update)
    }

    fun font(path: String) {
        if (!state.value.finished) change(repository.font(path))
    }

    fun open(destination: ReadStyleDestination) {
        if (!state.value.finished) change(ReadStyleUpdate(), destination)
    }

    fun editPreset(index: Int) {
        if (!state.value.finished && index in state.value.settings.presets.indices)
            change(repository.select(index), ReadStyleDestination.Background)
    }

    fun addPreset() {
        if (!state.value.finished) {
            val index = repository.addPreset()
            change(repository.select(index), ReadStyleDestination.Background)
        }
    }

    /**
     * Route applies a possible animation callback first, then acknowledges once and dispatches
     * reader events.
     */
    fun completed(id: Long) {
        val effect = state.value.pending.firstOrNull { it.id == id } ?: return
        mutableState.value =
            state.value.copy(pending = state.value.pending.filterNot { it.id == id })
        persist()
        effect.update?.let(repository::dispatch)
    }

    fun dismissed(changingConfigurations: Boolean) {
        if (changingConfigurations || state.value.finished) return
        mutableState.value = state.value.copy(finished = true)
        persist()
        repository.save()
    }
}
