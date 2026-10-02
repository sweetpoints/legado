package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.ReadAloudControlRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReadAloudControl {
    PreviousChapter, NextChapter, PreviousParagraph, NextParagraph, PlayPause, Stop,
    Catalog, MainMenu, Background, Settings, Engine, SleepTimer, UpdateRate, SetTimer, SetChapterStop, TimerSaved;
    val closesDialog get() = this == Stop || this == MainMenu || this == Background
}
data class ReadAloudEffect(val id: Int, val control: ReadAloudControl, val value: Int = 0)
data class ReadAloudUiState(
    val paused: Boolean = true,
    val minute: Int = 0,
    val chapter: Int = 0,
    val followSystem: Boolean = true,
    val rate: Int = 5,
    val timer: Int = 0,
    val timerEditing: Boolean = false,
    val engineName: String = "",
    val engineLoading: Boolean = true,
    val error: String? = null,
    val pending: List<ReadAloudEffect> = emptyList(),
    val finished: Boolean = false,
) {
    val rateText get() = ((rate + 5) / 10f).toString()
}

class ReadAloudViewModel(private val repository: ReadAloudControlRepository,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val preferences = repository.preferences()
    private val runtime = repository.runtime()
    private val mutableState = MutableStateFlow(ReadAloudUiState(
        paused = runtime.paused, minute = runtime.minute.coerceIn(0, 180), chapter = runtime.chapter.coerceIn(0, 99),
        followSystem = savedState["aloud.follow"] ?: preferences.followSystem,
        rate = (savedState.get<Int>("aloud.rate") ?: preferences.rate).coerceIn(0, 45),
        timer = (savedState.get<Int>("aloud.timer") ?: when {
            runtime.minute > 0 -> runtime.minute
            runtime.chapter > 0 -> 0
            else -> preferences.defaultTimer
        }).coerceIn(0, 180),
        timerEditing = savedState["aloud.timerEditing"] ?: false,
        pending = restoredEffects(),
        finished = savedState["aloud.finished"] ?: false,
    ))
    val state = mutableState.asStateFlow()
    private var rateEditing = savedState.get<Boolean>("aloud.rateEditing") ?: false
    private var nextEffectId = savedState.get<Int>("aloud.effectId") ?: 0
    private var engineJob: Job? = null
    private var engineGeneration = 0

    init { persistDraft(); if (!state.value.finished) reloadEngine() }

    fun refreshRuntime(timerEvent: Int? = null, chapterEvent: Int? = null) {
        if (state.value.finished) return
        val snapshot = repository.runtime()
        val runtime = snapshot.copy(minute = timerEvent ?: snapshot.minute, chapter = chapterEvent ?: snapshot.chapter)
        val preferences = repository.preferences()
        mutableState.update { current -> current.copy(paused = runtime.paused,
            minute = runtime.minute.coerceIn(0, 180), chapter = runtime.chapter.coerceIn(0, 99),
            followSystem = preferences.followSystem,
            rate = if (rateEditing) current.rate else preferences.rate.coerceIn(0, 45),
            timer = if (current.timerEditing) current.timer else when {
                runtime.minute > 0 -> runtime.minute.coerceIn(0, 180)
                runtime.chapter > 0 -> 0
                timerEvent != null -> timerEvent.coerceIn(0, 180)
                current.minute > 0 || current.chapter > 0 -> 0
                else -> current.timer
            }) }
        persistDraft()
    }

    fun reloadEngine() {
        if (state.value.finished) return
        val generation = ++engineGeneration
        engineJob?.cancel()
        mutableState.update { it.copy(engineLoading = true) }
        engineJob = viewModelScope.launch {
            try {
                val name = repository.engineName()
                coroutineContext.ensureActive()
                if (generation == engineGeneration && !state.value.finished)
                    mutableState.update { it.copy(engineName = name, engineLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                if (generation == engineGeneration) mutableState.update {
                    it.copy(engineLoading = false, error = error.localizedMessage ?: error.toString())
                }
            }
        }
    }

    fun changeRate(rate: Int) {
        if (state.value.followSystem || state.value.finished) return
        rateEditing = true
        mutableState.update { it.copy(rate = rate.coerceIn(0, 45), error = null) }
        persistDraft()
    }
    fun finishRate() {
        if (state.value.followSystem || state.value.finished) return
        mutate {
            repository.saveRate(state.value.rate)
            rateEditing = false
            persistDraft()
            request(ReadAloudControl.UpdateRate)
        }
    }
    fun stepRate(delta: Int) {
        if (state.value.followSystem || state.value.finished) return
        val bounded = (state.value.rate + delta.coerceIn(-1, 1)).coerceIn(0, 45)
        if (bounded == state.value.rate) return
        changeRate(bounded)
        finishRate()
    }
    fun setFollowSystem(follow: Boolean) {
        if (state.value.finished || follow == state.value.followSystem) return
        mutate {
            repository.saveFollowSystem(follow)
            // Turning off following system uses the persisted rate, not an unfinished drag.
            rateEditing = false
            mutableState.update { it.copy(followSystem = follow, rate = repository.preferences().rate.coerceIn(0, 45)) }
            persistDraft()
            request(ReadAloudControl.UpdateRate)
        }
    }
    fun changeTimer(minute: Int) {
        if (state.value.finished) return
        mutableState.update { it.copy(timer = minute.coerceIn(0, 180), timerEditing = true, error = null) }
        persistDraft()
    }
    fun finishTimer() { if (!state.value.finished) setSleepMinute(state.value.timer) }
    fun setSleepMinute(minute: Int) {
        if (state.value.finished) return
        val value = minute.coerceIn(0, 180)
        mutableState.update { it.copy(timer = value, timerEditing = false, minute = value, chapter = 0) }
        persistDraft()
        request(ReadAloudControl.SetTimer, value)
    }
    fun setSleepChapter(chapter: Int) {
        if (state.value.finished) return
        val count = chapter.coerceIn(0, 99)
        mutableState.update { it.copy(timer = 0, timerEditing = false, minute = 0, chapter = count) }
        persistDraft()
        request(ReadAloudControl.SetChapterStop, count)
    }
    fun saveDefaultTimer() {
        if (state.value.finished) return
        mutate { repository.saveDefaultTimer(state.value.timer); request(ReadAloudControl.TimerSaved) }
    }

    fun request(control: ReadAloudControl, value: Int = 0) {
        if (state.value.finished || state.value.pending.any { it.control.closesDialog }) return
        val effect = ReadAloudEffect(++nextEffectId, control, value)
        savedState["aloud.effectId"] = nextEffectId
        mutableState.update { it.copy(pending = it.pending + effect) }
        persistEffects()
    }

    /** Consume before platform execution, including callbacks that recreate or dismiss the host. */
    fun consumeEffect(id: Int): ReadAloudEffect? {
        val head = state.value.pending.firstOrNull()?.takeIf { it.id == id } ?: return null
        mutableState.update { it.copy(pending = if (head.control.closesDialog) emptyList() else it.pending.drop(1),
            finished = head.control.closesDialog) }
        if (head.control.closesDialog) {
            savedState["aloud.finished"] = true
            engineGeneration++
            engineJob?.cancel()
        }
        persistEffects()
        return head
    }

    private inline fun mutate(operation: () -> Unit) {
        try { operation(); mutableState.update { it.copy(error = null) } }
        catch (error: Exception) { mutableState.update { it.copy(error = error.localizedMessage ?: error.toString()) } }
    }
    private fun persistDraft() {
        savedState["aloud.follow"] = state.value.followSystem
        savedState["aloud.rate"] = state.value.rate
        savedState["aloud.rateEditing"] = rateEditing
        savedState["aloud.timer"] = state.value.timer
        savedState["aloud.timerEditing"] = state.value.timerEditing
    }
    private fun persistEffects() {
        savedState["aloud.effectNames"] = ArrayList(state.value.pending.map { it.control.name })
        savedState["aloud.effectIds"] = ArrayList(state.value.pending.map { it.id })
        savedState["aloud.effectValues"] = ArrayList(state.value.pending.map { it.value })
    }
    private fun restoredEffects(): List<ReadAloudEffect> {
        val names = savedState.get<ArrayList<String>>("aloud.effectNames").orEmpty()
        val ids = savedState.get<ArrayList<Int>>("aloud.effectIds").orEmpty()
        val values = savedState.get<ArrayList<Int>>("aloud.effectValues").orEmpty()
        return names.mapIndexedNotNull { index, name -> ReadAloudControl.entries.find { it.name == name }?.let {
            ReadAloudEffect(ids.getOrNull(index) ?: index, it, values.getOrNull(index) ?: 0)
        } }
    }
}

internal class ReadAloudDialogLease {
    private var acquired = false
    fun acquire(count: Int): Boolean {
        if (acquired || count > 0) return false
        acquired = true
        return true
    }
    fun release(): Boolean {
        if (!acquired) return false
        acquired = false
        return true
    }
}

internal fun navigateReadAloudChapter(previous: Boolean, following: Boolean,
    speechPrevious: () -> Unit, speechNext: () -> Unit,
    visiblePrevious: () -> Unit, visibleNext: () -> Unit) {
    if (following) { if (previous) speechPrevious() else speechNext() }
    else { if (previous) visiblePrevious() else visibleNext() }
}
