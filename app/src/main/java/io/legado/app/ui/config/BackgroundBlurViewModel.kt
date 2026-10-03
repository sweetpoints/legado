package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BackgroundBlurRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BackgroundBlurState(
    val radius: Int = 0,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val applied: Boolean = false,
)

class BackgroundBlurViewModel(
    private val repository: BackgroundBlurRepository,
    private val saved: SavedStateHandle,
    val night: Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow(BackgroundBlurState())
    val state: StateFlow<BackgroundBlurState> = mutable

    init {
        load()
    }

    fun load() {
        if (state.value.saving) return
        val restored = saved.get<Int>("blur.radius")
        if (saved.get<Boolean>("blur.finished") == true || restored != null) {
            mutable.value =
                BackgroundBlurState(
                    radius = (restored ?: 0).coerceIn(0, 25),
                    loading = false,
                    finished = saved.get<Boolean>("blur.finished") == true,
                    applied = saved.get<Boolean>("blur.applied") == true,
                )
            return
        }
        mutable.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val value = repository.load(night)
                currentCoroutineContext().ensureActive()
                saved["blur.radius"] = value.coerceIn(0, 25)
                mutable.value = state.value.copy(radius = value.coerceIn(0, 25), loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(
                        loading = false,
                        error = error.localizedMessage ?: "Unable to update background blur",
                    )
            }
        }
    }

    fun radius(value: Int) {
        if (
            state.value.loading ||
                state.value.saving ||
                state.value.finished ||
                saved.get<Int>("blur.radius") == null
        )
            return
        saved["blur.radius"] = value.coerceIn(0, 25)
        mutable.value = state.value.copy(radius = value.coerceIn(0, 25), error = null)
    }

    fun save() {
        if (
            state.value.loading ||
                state.value.saving ||
                state.value.finished ||
                saved.get<Int>("blur.radius") == null
        )
            return
        val radius = state.value.radius
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                repository.save(night, radius)
                currentCoroutineContext().ensureActive()
                finish(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    state.value.copy(
                        saving = false,
                        error = error.localizedMessage ?: "Unable to update background blur",
                    )
            }
        }
    }

    fun retry() {
        if (saved.get<Int>("blur.radius") == null) load() else save()
    }

    fun cancel() {
        if (!state.value.saving && !state.value.finished) finish(false)
    }

    private fun finish(applied: Boolean) {
        saved["blur.finished"] = true
        saved["blur.applied"] = applied
        mutable.value =
            state.value.copy(loading = false, saving = false, finished = true, applied = applied)
    }

    fun claim(): Boolean? {
        if (!state.value.finished || saved.get<Boolean>("blur.consumed") == true) return null
        saved["blur.consumed"] = true
        return state.value.applied
    }

    fun stop() {
        viewModelScope.cancel()
    }
}
