package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.AutoReadSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AutoReadUiState(
    val speed: Int = 10,
    val ttsUpdate: Int = 0,
    val error: String? = null,
)

class AutoReadViewModel(
    private val repository: AutoReadSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutoReadUiState(
        speed = (savedState.get<Int>("autoRead.speed") ?: repository.readSpeed()).coerceIn(1, 120),
        ttsUpdate = savedState["autoRead.ttsUpdate"] ?: 0,
    ))
    val state = mutableState.asStateFlow()

    fun changeSpeed(speed: Int) {
        val bounded = speed.coerceIn(1, 120)
        savedState["autoRead.speed"] = bounded
        mutableState.update { it.copy(speed = bounded, error = null) }
    }

    fun finishChangingSpeed() {
        try {
            repository.saveSpeed(state.value.speed)
            val update = state.value.ttsUpdate + 1
            savedState["autoRead.ttsUpdate"] = update
            mutableState.update { it.copy(ttsUpdate = update, error = null) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.localizedMessage ?: error.toString()) }
        }
    }

    fun ttsUpdated(update: Int) {
        if (state.value.ttsUpdate != update) return
        savedState["autoRead.ttsUpdate"] = 0
        mutableState.update { it.copy(ttsUpdate = 0) }
    }
}

/** A lease belongs to one dialog View; dismissal and View destruction may both release it. */
internal class AutoReadDialogLease {
    var isAcquired = false
        private set

    fun acquire(existingDialogs: Int): Boolean {
        if (isAcquired || existingDialogs > 0) return false
        isAcquired = true
        return true
    }

    fun release(): Boolean {
        if (!isAcquired) return false
        isAcquired = false
        return true
    }
}
