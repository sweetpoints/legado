package io.legado.app.ui.main.bookshelf.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.BookshelfSettingsDraft
import io.legado.app.data.preferences.BookshelfSettingsEffects
import io.legado.app.data.preferences.BookshelfSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BookshelfSettingsViewModel(private val repository: BookshelfSettingsRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow((saved.get<BookshelfSettingsDraft>("shelf.settings.draft") ?: repository.load()).normalized())
    val state = mutableState.asStateFlow()
    private var finished = saved.get<Boolean>("shelf.settings.finished") ?: false
    fun edit(value: BookshelfSettingsDraft) {
        if (finished) return
        mutableState.value = value.normalized(); saved["shelf.settings.draft"] = state.value
    }
    fun confirm(): BookshelfSettingsEffects? {
        if (finished) return null
        val effects = repository.commit(state.value)
        finished = true; saved["shelf.settings.finished"] = true
        return effects
    }
    fun cancel() { finished = true; saved["shelf.settings.finished"] = true }
}
