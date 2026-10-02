package io.legado.app.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.CrashLogContent
import io.legado.app.data.repository.CrashLogEntry
import io.legado.app.data.repository.CrashLogsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class CrashLogsUiState(
    val logs: List<CrashLogEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isClearing: Boolean = false,
    val loadingLogId: String? = null,
    val error: String? = null,
    val openedLog: CrashLogContent? = null,
) {
    val isBusy: Boolean get() = isLoading || isClearing || loadingLogId != null
}

class CrashLogsViewModel(private val repository: CrashLogsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CrashLogsUiState())
    val state = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var readJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (state.value.isClearing) return
        listJob?.cancel()
        readJob?.cancel()
        mutableState.update { it.copy(isLoading = true, loadingLogId = null, error = null) }
        listJob = viewModelScope.launch {
            try {
                val logs = repository.loadLogs()
                coroutineContext.ensureActive()
                mutableState.update { it.copy(logs = logs, isLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isLoading = false, error = error.message.orEmpty()) }
            }
        }
    }

    fun openLog(id: String) {
        if (state.value.isBusy || state.value.openedLog != null || state.value.logs.none { it.id == id }) return
        mutableState.update { it.copy(loadingLogId = id, error = null) }
        readJob = viewModelScope.launch {
            try {
                val log = repository.readLog(id)
                coroutineContext.ensureActive()
                mutableState.update { it.copy(loadingLogId = null, openedLog = log) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update { it.copy(loadingLogId = null, error = error.message.orEmpty()) }
            }
        }
    }

    fun consumeOpenedLog(log: CrashLogContent) {
        mutableState.update { if (it.openedLog == log) it.copy(openedLog = null) else it }
    }

    fun clearLogs() {
        if (state.value.isClearing) return
        listJob?.cancel()
        readJob?.cancel()
        mutableState.update {
            it.copy(isLoading = false, isClearing = true, loadingLogId = null, openedLog = null, error = null)
        }
        listJob = viewModelScope.launch {
            var failure: String? = null
            try {
                repository.clearLogs()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                failure = error.message.orEmpty()
            }
            // Refresh even when only part of the local/backup cleanup succeeded.
            try {
                val logs = repository.loadLogs()
                coroutineContext.ensureActive()
                mutableState.update { it.copy(logs = logs, isClearing = false, error = failure) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update {
                    it.copy(isClearing = false, error = failure ?: error.message.orEmpty())
                }
            }
        }
    }
}
