package io.legado.app.ui.book.read.config

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.PageKeySettingsRepository
import io.legado.app.data.preferences.PageKeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PageKeyField { PREVIOUS, NEXT }

data class PageKeyUiState(
    val values: PageKeyValues = PageKeyValues(),
    val focused: PageKeyField? = null,
    val error: String? = null,
    val finished: Boolean = false,
)

class PageKeyViewModel(
    private val repository: PageKeySettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val values = if (savedState.get<Boolean>("pageKey.initialized") == true)
        PageKeyValues(savedState["pageKey.previous"] ?: "", savedState["pageKey.next"] ?: "")
        else repository.load()
    private val mutableState = MutableStateFlow(PageKeyUiState(values,
        savedState.get<String>("pageKey.focus")?.let { value -> PageKeyField.entries.find { it.name == value } }))
    val state = mutableState.asStateFlow()

    init { persist(values) }

    fun edit(field: PageKeyField, value: String) {
        if (state.value.finished) return
        val draft = when (field) {
            PageKeyField.PREVIOUS -> state.value.values.copy(previous = value)
            PageKeyField.NEXT -> state.value.values.copy(next = value)
        }
        persist(draft)
        mutableState.update { it.copy(values = draft, error = null) }
    }

    fun focus(field: PageKeyField, focused: Boolean) {
        val next = if (focused) field else state.value.focused.takeUnless { it == field }
        savedState["pageKey.focus"] = next?.name
        mutableState.update { it.copy(focused = next) }
    }

    /** Only hardware keys use this path; IME text remains normal text editing. */
    fun keyDown(keyCode: Int): Boolean {
        if (state.value.finished || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) return false
        val field = state.value.focused ?: return false
        val text = if (field == PageKeyField.PREVIOUS) state.value.values.previous else state.value.values.next
        edit(field, text + (if (text.isEmpty() || text.endsWith(',')) "" else ",") + keyCode)
        return true
    }

    fun reset() {
        if (state.value.finished) return
        persist(PageKeyValues())
        mutableState.update { it.copy(values = PageKeyValues(), error = null) }
    }

    fun confirm() {
        if (state.value.finished) return
        try {
            repository.save(state.value.values)
            mutableState.update { it.copy(finished = true, error = null) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.localizedMessage ?: error.toString()) }
        }
    }

    private fun persist(values: PageKeyValues) {
        savedState["pageKey.initialized"] = true
        savedState["pageKey.previous"] = values.previous
        savedState["pageKey.next"] = values.next
    }
}
