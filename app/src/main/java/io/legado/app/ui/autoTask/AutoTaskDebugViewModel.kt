package io.legado.app.ui.autoTask

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRunner
import io.legado.app.model.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AutoTaskDebugUiState(
    val output: String = "",
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    val taskMissing: Boolean = false,
    val error: String? = null,
)

class AutoTaskDebugViewModel(application: Application, savedStateHandle: SavedStateHandle) : BaseViewModel(application) {
    private val _uiState = MutableStateFlow(AutoTaskDebugUiState())
    val uiState = _uiState.asStateFlow()
    private var task: AutoTaskRule? = null
    private var debugJob: Job? = null
    private var owner: Debug.Callback? = null
    @Volatile private var generation = 0L

    init {
        viewModelScope.launch {
            task = withContext(Dispatchers.IO) {
                savedStateHandle.get<String>(AutoTaskDebugActivity.EXTRA_ID)?.let(AutoTask::get)
            }
            _uiState.update { it.copy(isLoading = false, taskMissing = task == null) }
            if (task != null) runDebug()
        }
    }

    fun runDebug() {
        val current = task ?: return
        val runGeneration = ++generation
        debugJob?.cancel()
        owner?.let(Debug::cancelDebug)
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                if (generation == runGeneration) {
                    _uiState.update {
                        if (generation == runGeneration) it.copy(output = appendDebugOutput(it.output, msg)) else it
                    }
                }
            }
        }
        owner = callback
        _uiState.update { it.copy(output = "", isRunning = true, error = null) }
        val sourceUrl = AutoTask.buildSource(current).bookSourceUrl
        if (!Debug.startSimpleDebug(callback, sourceUrl)) {
            _uiState.update { it.copy(isRunning = false, error = context.getString(R.string.auto_task_debug_busy)) }
            return
        }
        debugJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AutoTaskRunner.runTask(context, current, persist = false)
                }
                if (generation == runGeneration) {
                    _uiState.update { it.copy(output = appendDebugOutput(it.output, result.log)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == runGeneration) _uiState.update { it.copy(error = error.localizedMessage) }
            } finally {
                Debug.cancelDebug(callback)
                if (generation == runGeneration) _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    override fun onCleared() {
        generation++
        debugJob?.cancel()
        owner?.let(Debug::cancelDebug)
        super.onCleared()
    }
}

/** Bound the displayed log while preserving the newest output. */
internal fun appendDebugOutput(output: String, line: String, maxLength: Int = 20_000): String =
    (if (output.isEmpty()) line else "$output\n$line").takeLast(maxLength)
