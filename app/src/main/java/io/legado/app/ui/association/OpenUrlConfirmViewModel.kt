package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.OpenUrlSourceRepository
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenUrlConfirmUiState(
    val uri: String,
    val mimeType: String? = null,
    val sourceOrigin: String = "",
    val sourceName: String = "",
    val sourceType: Int = SourceType.book,
    val showDeleteConfirmation: Boolean = false,
    val isWorking: Boolean = false,
    val shouldClose: Boolean = false,
    val error: String? = null,
)

class OpenUrlConfirmViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: OpenUrlSourceRepository,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            OpenUrlConfirmUiState(
                uri = savedStateHandle["uri"] ?: "",
                mimeType = savedStateHandle["mimeType"],
                sourceOrigin = savedStateHandle["sourceOrigin"] ?: "",
                sourceName = savedStateHandle["sourceName"] ?: "",
                sourceType = savedStateHandle["sourceType"] ?: SourceType.book,
                showDeleteConfirmation = savedStateHandle[DELETE_CONFIRMATION] ?: false,
                shouldClose = savedStateHandle[CLOSE] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    fun requestDelete() {
        if (!state.value.isWorking && !state.value.shouldClose) setDeleteConfirmation(true)
    }

    fun cancelDelete() = setDeleteConfirmation(false)

    private fun setDeleteConfirmation(visible: Boolean) {
        savedStateHandle[DELETE_CONFIRMATION] = visible
        mutableState.update { it.copy(showDeleteConfirmation = visible) }
    }

    fun disableSource() = operate { repository.disableSource(it.sourceOrigin, it.sourceType) }

    fun confirmDelete() {
        if (!state.value.showDeleteConfirmation || state.value.isWorking) return
        setDeleteConfirmation(false)
        operate { repository.deleteSource(it.sourceOrigin, it.sourceType) }
    }

    private fun operate(block: suspend (OpenUrlConfirmUiState) -> Unit) {
        val current = state.value
        if (current.isWorking || current.shouldClose) return
        mutableState.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            try {
                block(current)
                coroutineContext.ensureActive()
                savedStateHandle[CLOSE] = true
                mutableState.update { it.copy(isWorking = false, shouldClose = true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableState.update {
                    it.copy(isWorking = false, error = error.localizedMessage.orEmpty())
                }
            }
        }
    }

    companion object {
        private const val DELETE_CONFIRMATION = "openUrlConfirm.deleteConfirmation"
        private const val CLOSE = "openUrlConfirm.close"
    }
}
