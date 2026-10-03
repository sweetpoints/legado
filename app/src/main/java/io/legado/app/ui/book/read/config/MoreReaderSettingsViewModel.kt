package io.legado.app.ui.book.read.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.MoreReaderSetting
import io.legado.app.data.preferences.MoreReaderSettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MoreReaderSettingsUiState(
    val values: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class MoreReaderSettingsViewModel(private val repository: MoreReaderSettingsRepository) :
    ViewModel() {
    private val mutableState = MutableStateFlow(MoreReaderSettingsUiState())
    val state = mutableState.asStateFlow()
    private val ioOperations = Mutex()
    private val generation = AtomicInteger()

    init {
        refresh()
    }

    fun refresh() {
        val request = generation.incrementAndGet()
        viewModelScope.launch {
            ioOperations.withLock {
                try {
                    val values = repository.load()
                    if (request == generation.get()) {
                        mutableState.value =
                            MoreReaderSettingsUiState(
                                values = values,
                                isLoading = false,
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (request == generation.get()) fail(error)
                }
            }
        }
    }

    fun change(setting: MoreReaderSetting, value: String) {
        val current = state.value
        if (current.isLoading || current.error != null || setting is MoreReaderSetting.Action)
            return
        if (current.values[setting.key] == value) return

        val request = generation.incrementAndGet()
        mutableState.value = current.copy(values = current.values + (setting.key to value))
        viewModelScope.launch {
            ioOperations.withLock {
                try {
                    repository.save(setting, value)
                    val values = repository.load()
                    if (request == generation.get()) {
                        mutableState.value =
                            MoreReaderSettingsUiState(
                                values = values,
                                isLoading = false,
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (request == generation.get()) fail(error)
                }
            }
        }
    }

    fun toggle(setting: MoreReaderSetting.Toggle, checked: Boolean) =
        change(setting, checked.toString())

    fun choose(setting: MoreReaderSetting.Choice, value: String) = change(setting, value)

    fun setSpeed(setting: MoreReaderSetting.SeekBar, value: Int) = change(setting, value.toString())

    fun numericValue(key: String): Int = state.value.values[key]?.toIntOrNull() ?: 0

    fun saveNumber(
        setting: MoreReaderSetting.Action,
        value: Int,
        onSaved: () -> Unit,
    ) {
        if (state.value.isLoading || setting.numericDefault == null) return
        val request = generation.incrementAndGet()
        viewModelScope.launch {
            ioOperations.withLock {
                try {
                    repository.saveNumber(setting, value)
                    val values = repository.load()
                    if (request == generation.get()) {
                        mutableState.value =
                            MoreReaderSettingsUiState(
                                values = values,
                                isLoading = false,
                            )
                        onSaved()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (request == generation.get()) fail(error)
                }
            }
        }
    }

    fun remove(settingKey: String) {
        val request = generation.incrementAndGet()
        viewModelScope.launch {
            ioOperations.withLock {
                try {
                    repository.remove(settingKey)
                    val values = repository.load()
                    if (request == generation.get()) {
                        mutableState.value =
                            MoreReaderSettingsUiState(
                                values = values,
                                isLoading = false,
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (request == generation.get()) fail(error)
                }
            }
        }
    }

    private fun fail(error: Exception) {
        mutableState.value =
            state.value.copy(
                isLoading = false,
                error = error.localizedMessage ?: error.toString(),
            )
    }
}
