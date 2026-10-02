package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.DefaultVerificationSourceRepository
import io.legado.app.data.repository.VerificationSourceRepository
import io.legado.app.help.source.SourceVerificationHelp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class VerificationCodeUiState(
    val code: String = "",
    val sourceName: String = "",
    val deleteConfirmation: Boolean = false,
    val busy: Boolean = false,
    val closeRequested: Boolean = false,
    val error: String? = null,
)

/** Keeps drafts and pending completion across recreation; never retains a Fragment callback. */
class VerificationCodeViewModel internal constructor(
    private val savedState: SavedStateHandle,
    private val sourceRepository: VerificationSourceRepository = DefaultVerificationSourceRepository,
    private val submitResult: (String?, String) -> Unit = { key, code ->
        SourceVerificationHelp.setResult(key, code)
    },
) : ViewModel() {
    val imageUrl: String = savedState["imageUrl"] ?: ""
    val sourceOrigin: String? = savedState["sourceOrigin"]
    private val sourceType: Int = savedState["sourceType"] ?: SourceType.book
    private val resultKey: String? = savedState["verificationResultKey"]
    private val mutableState = MutableStateFlow(
        VerificationCodeUiState(
            code = savedState["code"] ?: "",
            sourceName = savedState["sourceName"] ?: "",
            deleteConfirmation = savedState["deleteConfirmation"] ?: false,
            closeRequested = savedState["closeRequested"] ?: false,
        )
    )
    internal val state = mutableState.asStateFlow()

    fun updateCode(code: String) {
        if (state.value.busy || state.value.closeRequested) return
        savedState["code"] = code
        mutableState.value = state.value.copy(code = code)
    }

    fun requestDelete() {
        if (state.value.busy || state.value.closeRequested) return
        savedState["deleteConfirmation"] = true
        mutableState.value = state.value.copy(deleteConfirmation = true)
    }

    fun cancelDelete() {
        savedState["deleteConfirmation"] = false
        mutableState.value = state.value.copy(deleteConfirmation = false)
    }

    fun submit() {
        if (state.value.busy || state.value.closeRequested) return
        submitResult(resultKey, state.value.code)
        requestClose()
    }

    fun disableSource() = changeSource(delete = false)
    fun deleteSource() {
        if (!state.value.deleteConfirmation) return
        cancelDelete()
        changeSource(delete = true)
    }

    private fun changeSource(delete: Boolean) {
        if (state.value.busy || state.value.closeRequested) return
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                if (delete) sourceRepository.delete(sourceOrigin.orEmpty(), sourceType)
                else sourceRepository.disable(sourceOrigin.orEmpty(), sourceType)
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(busy = false)
                requestClose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(busy = false, error = error.localizedMessage ?: error.toString())
            }
        }
    }

    private fun requestClose() {
        savedState["closeRequested"] = true
        mutableState.value = state.value.copy(closeRequested = true)
    }
}
