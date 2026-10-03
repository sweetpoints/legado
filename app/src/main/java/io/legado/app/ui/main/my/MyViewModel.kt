package io.legado.app.ui.main.my

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.MyPreferences
import io.legado.app.data.preferences.MySettingsRepository
import io.legado.app.help.config.ThemeConfig
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyUiState(
    val preferences: MyPreferences,
    val customizationDraft: Set<String>? = null,
    val webAddress: String = "",
    val mcpAddress: String = "",
)

internal data class MyCustomizationDraft(val selectedKeys: Set<String>)

class MyViewModel(application: Application, private val savedStateHandle: SavedStateHandle) :
    BaseViewModel(application) {
    private val repository = MySettingsRepository(application)
    private val _uiState =
        MutableStateFlow(
            MyUiState(
                repository.read(),
                savedStateHandle.get<ArrayList<String>>(DRAFT_KEY)?.toSet(),
            )
        )
    val uiState = _uiState.asStateFlow()
    private var observation: AutoCloseable? = null
    private var syncingRuntime = false

    fun startObserving() {
        if (observation != null) return
        refreshRuntimeState()
        observation = repository.observeChanges { key ->
            if (!syncingRuntime) {
                val prefs = repository.read()
                when (key) {
                    PreferKey.webService ->
                        if (prefs.webEnabled) WebService.start(context)
                        else WebService.stop(context)
                    PreferKey.mcpService ->
                        if (prefs.mcpEnabled) McpService.start(context)
                        else McpService.stop(context)
                    PreferKey.autoTaskService ->
                        viewModelScope.launch(Dispatchers.IO) {
                            if (prefs.autoTaskEnabled) AutoTaskScheduler.refresh(context)
                            else AutoTaskScheduler.cancelAll(context)
                        }
                    "recordLog" -> LogUtils.upLevel()
                }
            }
            refreshPreferences()
        }
        refreshPreferences()
    }

    fun stopObserving() {
        observation?.close()
        observation = null
    }

    private fun refreshPreferences() {
        _uiState.update { it.copy(preferences = repository.read()) }
    }

    fun refreshRuntimeState() {
        // Service status notifications must not restart/stop services themselves.
        syncingRuntime = true
        try {
            repository.setSwitch(PreferKey.webService, WebService.isRun)
            repository.setSwitch(PreferKey.mcpService, McpService.isRun)
        } finally {
            syncingRuntime = false
        }
        _uiState.update {
            it.copy(
                preferences = repository.read(),
                webAddress = WebService.hostAddress,
                mcpAddress = McpService.hostAddress,
            )
        }
    }

    fun setSwitch(key: String, enabled: Boolean) {
        if (key !in setOf(PreferKey.webService, PreferKey.mcpService, PreferKey.autoTaskService))
            return
        repository.setSwitch(key, enabled)
        refreshPreferences()
    }

    fun setThemeMode(value: String) {
        if (value == _uiState.value.preferences.themeMode) return
        repository.setThemeMode(value)
        refreshPreferences()
        ThemeConfig.applyDayNight(context)
    }

    fun openCustomization() {
        setDraft(repository.read().moreItems)
    }

    fun toggleCustomization(key: String) {
        if (customizableMySettings.none { it.key == key }) return
        val draft = _uiState.value.customizationDraft ?: return
        setDraft(if (key in draft) draft - key else draft + key)
    }

    fun confirmCustomization() {
        val draft = _uiState.value.customizationDraft ?: return
        repository.setMoreItems(draft.intersect(customizableMySettings.map { it.key }.toSet()))
        setDraft(null)
        refreshPreferences()
    }

    fun dismissCustomization() = setDraft(null)

    internal fun captureCustomizationDraftForHostMigration(): MyCustomizationDraft? =
        _uiState.value.customizationDraft?.let { MyCustomizationDraft(it.toSet()) }

    internal fun seedCustomizationDraftFromLegacy(draft: MyCustomizationDraft?) {
        if (_uiState.value.customizationDraft == null && draft != null) {
            setDraft(draft.selectedKeys.intersect(customizableMySettings.map { it.key }.toSet()))
        }
    }

    private fun setDraft(draft: Set<String>?) {
        savedStateHandle[DRAFT_KEY] = draft?.let { ArrayList(it) }
        _uiState.update { it.copy(customizationDraft = draft) }
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

    companion object {
        private const val DRAFT_KEY = "myCustomizationDraft"
    }
}
