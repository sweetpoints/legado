package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.AppLogDetail
import io.legado.app.data.repository.AppLogExport
import io.legado.app.data.repository.AppLogRow
import io.legado.app.data.repository.AppLogsRepository
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppLogNotice {
    NoLogs,
    ShareFailed,
}

data class AppLogsUiState(
    val logs: List<AppLogRow> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val showClearConfirmation: Boolean = false,
    val error: String? = null,
    val notice: AppLogNotice? = null,
    val openedLog: AppLogDetail? = null,
    val export: AppLogExport? = null,
) {
    val isBusy: Boolean
        get() = isLoading || isWorking
}

class AppLogsViewModel(
    private val repository: AppLogsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            AppLogsUiState(showClearConfirmation = savedStateHandle[CONFIRM_CLEAR] ?: false)
        )
    val state = mutableState.asStateFlow()
    private var observation: Job? = null

    fun startObserving() {
        if (observation?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        observation = viewModelScope.launch {
            try {
                repository.logs.collect { logs ->
                    coroutineContext.ensureActive()
                    mutableState.update { it.copy(logs = logs, isLoading = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isLoading = false, error = error.message.orEmpty()) }
            }
        }
    }

    fun refresh() {
        stopObserving()
        startObserving()
    }

    fun stopObserving() {
        observation?.cancel()
        observation = null
    }

    fun requestClear() {
        if (state.value.isBusy) return
        setClearConfirmation(true)
    }

    fun dismissClearConfirmation() = setClearConfirmation(false)

    private fun setClearConfirmation(visible: Boolean) {
        savedStateHandle[CONFIRM_CLEAR] = visible
        mutableState.update { it.copy(showClearConfirmation = visible) }
    }

    fun confirmClear() {
        if (!state.value.showClearConfirmation || state.value.isBusy) return
        setClearConfirmation(false)
        mutableState.update { it.copy(openedLog = null, export = null) }
        operate { repository.clearLogs() }
    }

    fun openLog(id: Long) {
        if (state.value.logs.none { it.id == id && it.hasDetails }) return
        operate {
            val detail = repository.readDetail(id)
            coroutineContext.ensureActive()
            mutableState.update { it.copy(openedLog = detail) }
        }
    }

    fun prepareExport() =
        operate(AppLogNotice.ShareFailed) {
            val export = repository.prepareExport()
            coroutineContext.ensureActive()
            mutableState.update {
                it.copy(export = export, notice = if (export == null) AppLogNotice.NoLogs else null)
            }
        }

    private fun operate(failureNotice: AppLogNotice? = null, block: suspend () -> Unit) {
        if (state.value.isBusy || state.value.openedLog != null || state.value.export != null)
            return
        mutableState.update { it.copy(isWorking = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
                coroutineContext.ensureActive()
                mutableState.update { it.copy(isWorking = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        notice = failureNotice,
                        error = if (failureNotice == null) error.message.orEmpty() else null,
                    )
                }
            }
        }
    }

    fun consumeOpenedLog(log: AppLogDetail) {
        mutableState.update { if (it.openedLog == log) it.copy(openedLog = null) else it }
    }

    fun consumeExport(export: AppLogExport) {
        mutableState.update { if (it.export == export) it.copy(export = null) else it }
    }

    fun shareFailed(export: AppLogExport) {
        mutableState.update {
            if (it.export == export) it.copy(export = null, notice = AppLogNotice.ShareFailed)
            else it
        }
    }

    companion object {
        private const val CONFIRM_CLEAR = "appLogs.confirmClear"
    }
}
