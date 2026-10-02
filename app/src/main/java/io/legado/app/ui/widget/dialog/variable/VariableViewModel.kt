package io.legado.app.ui.widget.dialog.variable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VariableUiState(val title: String = "", val key: String = "", val comment: String = "",
    val input: String = "", val saveRequested: Boolean = false, val finished: Boolean = false)
data class VariableResult(val key: String, val variable: String)

class VariableViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(VariableUiState(
        title = savedState["title"] ?: "", key = savedState["key"] ?: "", comment = savedState["comment"] ?: "",
        // EditText also normalized a null argument to an empty string on Save.
        input = savedState["variable.draft"] ?: savedState["variable"] ?: "",
        saveRequested = savedState["variable.saveRequested"] ?: false,
        finished = savedState["variable.finished"] ?: false,
    ))
    val state = mutableState.asStateFlow()

    fun setInput(input: String) {
        if (state.value.saveRequested || state.value.finished) return
        savedState["variable.draft"] = input
        mutableState.update { it.copy(input = input) }
    }

    fun requestSave() {
        if (state.value.finished || state.value.saveRequested) return
        savedState["variable.saveRequested"] = true
        mutableState.update { it.copy(saveRequested = true) }
    }

    fun consumeSave(): VariableResult? {
        val current = state.value
        if (!current.saveRequested || current.finished) return null
        savedState["variable.saveRequested"] = false
        savedState["variable.finished"] = true
        mutableState.update { it.copy(saveRequested = false, finished = true) }
        return VariableResult(current.key, current.input)
    }

    fun cancel() {
        if (state.value.finished) return
        savedState["variable.saveRequested"] = false
        savedState["variable.finished"] = true
        mutableState.update { it.copy(saveRequested = false, finished = true) }
    }
}
