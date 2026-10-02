package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.TextSelectMenuSettingsRepository
import io.legado.app.help.TextSelectMenuConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TextSelectMenuSettingsUiState(val rows: List<String>, val error: String? = null)
sealed interface TextSelectMenuSettingsAction {
    data class Transfer(val key: String) : TextSelectMenuSettingsAction
    data class StartDrag(val key: String) : TextSelectMenuSettingsAction
    data class MoveTo(val target: String) : TextSelectMenuSettingsAction
    data class FinishDrag(val commit: Boolean) : TextSelectMenuSettingsAction
    data class Step(val key: String, val delta: Int) : TextSelectMenuSettingsAction
    data object Reset : TextSelectMenuSettingsAction
    data object Retry : TextSelectMenuSettingsAction
}

class TextSelectMenuSettingsViewModel(private val repository: TextSelectMenuSettingsRepository,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val initial = runCatching {
        savedState.get<String>("textSelectMenu.config")?.let { TextSelectMenuConfig.fromJson(it).normalized() }
            ?: repository.load().normalized()
    }
    private val mutableState = MutableStateFlow(TextSelectMenuSettingsUiState(rows(initial.getOrElse { TextSelectMenuConfig.default() }),
        initial.exceptionOrNull()?.let { it.localizedMessage ?: it.toString() }))
    val state = mutableState.asStateFlow()
    private var baseline: List<String>? = null
    private var draggedKey: String? = null
    init { rememberConfig() }

    fun edit(action: TextSelectMenuSettingsAction) {
        when (action) {
            is TextSelectMenuSettingsAction.Transfer -> {
                if (baseline != null || action.key !in TextSelectMenuConfig.ALL_KEYS) return
                val config = config()
                val next = if (action.key in config.bar) TextSelectMenuConfig(config.bar - action.key, listOf(action.key) + config.more)
                    else TextSelectMenuConfig(config.bar + action.key, config.more - action.key)
                commit(rows(next.normalized()))
            }
            is TextSelectMenuSettingsAction.StartDrag -> if (baseline == null && action.key in TextSelectMenuConfig.ALL_KEYS) {
                baseline = state.value.rows
                draggedKey = action.key
            }
            is TextSelectMenuSettingsAction.MoveTo -> draggedKey?.let { move(it, action.target) }
            is TextSelectMenuSettingsAction.FinishDrag -> finish(action.commit)
            is TextSelectMenuSettingsAction.Step -> {
                if (baseline != null) return
                val index = state.value.rows.indexOf(action.key)
                val target = index + action.delta.coerceIn(-1, 1)
                if (index > 0 && target in 1..state.value.rows.lastIndex && move(action.key, state.value.rows[target])) commit(rows(config()))
            }
            TextSelectMenuSettingsAction.Reset -> { finish(false); commit(rows(TextSelectMenuConfig.default())) }
            TextSelectMenuSettingsAction.Retry -> { if (baseline == null) save() }
        }
    }
    private fun move(key: String, target: String): Boolean {
        val next = state.value.rows.toMutableList()
        val from = next.indexOf(key)
        val to = next.indexOf(target).coerceAtLeast(1)
        if (from < 1 || target !in next || from == to) return false
        next.removeAt(from)
        next.add(to, key)
        mutableState.value = state.value.copy(rows = next, error = null)
        return true
    }
    private fun finish(commit: Boolean) {
        val original = baseline ?: return
        baseline = null
        draggedKey = null
        if (commit) commit(rows(config())) else mutableState.value = state.value.copy(rows = original)
    }
    /** A configuration change cancels the temporary gesture; only finished edits enter SavedState. */
    fun cancelGesture() = finish(false)
    private fun commit(next: List<String>) {
        if (next == state.value.rows && savedState.get<String>("textSelectMenu.config") == config(next).toJson()) return
        mutableState.value = state.value.copy(rows = next, error = null)
        rememberConfig()
        save()
    }
    private fun rememberConfig() { savedState["textSelectMenu.config"] = config().toJson() }
    private fun save() {
        try { repository.save(config()); mutableState.value = state.value.copy(error = null) }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error.localizedMessage ?: error.toString()) }
    }
    private fun config(rows: List<String> = state.value.rows): TextSelectMenuConfig {
        val divider = rows.indexOf(ZONE_MORE)
        return TextSelectMenuConfig(rows.take(divider).filter { it in TextSelectMenuConfig.ALL_KEYS },
            rows.drop(divider + 1).filter { it in TextSelectMenuConfig.ALL_KEYS }).normalized()
    }
    companion object {
        const val ZONE_BAR = "section.bar"
        const val ZONE_MORE = "section.more"
        private fun rows(config: TextSelectMenuConfig) = listOf(ZONE_BAR) + config.bar + ZONE_MORE + config.more
    }
}
