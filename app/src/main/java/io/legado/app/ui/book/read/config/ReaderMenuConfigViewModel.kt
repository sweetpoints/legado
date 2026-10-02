package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.legado.app.data.preferences.ReaderMenuSettingsRepository
import io.legado.app.help.ReaderMenuConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Unchecked actions remain available in More; they are never removed. */
data class ReaderMenuEntry(val key: String, val primary: Boolean)
data class ReaderMenuConfigUiState(
    val entries: List<ReaderMenuEntry> = emptyList(),
    val refreshRequest: Int = 0,
    val error: String? = null,
)

sealed interface ReaderMenuEditAction {
    data class Toggle(val key: String, val primary: Boolean) : ReaderMenuEditAction
    data class SetAll(val primary: Boolean) : ReaderMenuEditAction
    data object Reset : ReaderMenuEditAction
    data class StartSelection(val key: String) : ReaderMenuEditAction
    data class SelectionTo(val key: String) : ReaderMenuEditAction
    data class StartReorder(val key: String) : ReaderMenuEditAction
    data class Move(val source: String, val target: String) : ReaderMenuEditAction
    data class Step(val key: String, val delta: Int) : ReaderMenuEditAction
    data class FinishGesture(val commit: Boolean) : ReaderMenuEditAction
    data object RetrySave : ReaderMenuEditAction
}

class ReaderMenuConfigViewModel(
    private val repository: ReaderMenuSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val initial = runCatching {
        if (savedState.get<Boolean>("readerMenu.initialized") == true) {
            val keys = savedState.get<ArrayList<String>>("readerMenu.keys").orEmpty()
            val selected = savedState.get<ArrayList<String>>("readerMenu.primary").orEmpty().toSet()
            ReaderMenuConfig(keys.filter { it in selected }, keys.filterNot { it in selected }).normalized()
        } else repository.load().normalized()
    }
    private val mutableState = MutableStateFlow(ReaderMenuConfigUiState(
        entries = entries(initial.getOrElse { ReaderMenuConfig.default() }),
        refreshRequest = savedState["readerMenu.refresh"] ?: 0,
        error = initial.exceptionOrNull()?.let { it.localizedMessage ?: it.toString() },
    ))
    val state = mutableState.asStateFlow()
    private var original: List<ReaderMenuEntry>? = null
    private var selectionStart = -1
    private val visited = mutableSetOf<Int>()
    private var reorderKey: String? = null

    init { persistDraft(state.value.entries) }

    fun edit(action: ReaderMenuEditAction) {
        when (action) {
            is ReaderMenuEditAction.Toggle -> toggle(action.key, action.primary)
            is ReaderMenuEditAction.SetAll -> {
                endGesture(false)
                update(state.value.entries.map { it.copy(primary = action.primary) })
                save()
            }
            ReaderMenuEditAction.Reset -> {
                endGesture(false)
                update(entries(ReaderMenuConfig.default()))
                save()
            }
            is ReaderMenuEditAction.StartSelection -> startSelection(action.key)
            is ReaderMenuEditAction.SelectionTo -> selectTo(action.key)
            is ReaderMenuEditAction.StartReorder -> {
                if (original == null && state.value.entries.any { it.key == action.key }) {
                    original = state.value.entries
                    reorderKey = action.key
                }
            }
            is ReaderMenuEditAction.Move -> if (reorderKey == action.source) move(action.source, action.target)
            is ReaderMenuEditAction.Step -> {
                if (original != null) return
                val list = state.value.entries
                val source = list.indexOfFirst { it.key == action.key }
                val target = source + action.delta.coerceIn(-1, 1)
                if (source >= 0 && target in list.indices && move(action.key, list[target].key)) save()
            }
            is ReaderMenuEditAction.FinishGesture -> endGesture(action.commit)
            ReaderMenuEditAction.RetrySave -> save()
        }
    }

    private fun toggle(key: String, primary: Boolean) {
        if (original != null) return
        val entry = state.value.entries.find { it.key == key } ?: return
        if (entry.primary == primary) return
        val list = state.value.entries.filterNot { it.key == key }.toMutableList()
        list.add(if (primary) list.indexOfLast { it.primary } + 1 else list.size, entry.copy(primary = primary))
        update(list.toList())
        save()
    }

    private fun startSelection(key: String) {
        if (original != null) return
        val start = state.value.entries.indexOfFirst { it.key == key }
        if (start < 0) return
        original = state.value.entries
        selectionStart = start
        visited.clear()
        selectTo(key)
    }

    private fun selectTo(key: String) {
        val snapshot = original ?: return
        if (selectionStart !in snapshot.indices) return
        val end = snapshot.indexOfFirst { it.key == key }
        if (end < 0) return
        val range = minOf(selectionStart, end)..maxOf(selectionStart, end)
        visited.addAll(range)
        val firstSelected = snapshot[selectionStart].primary
        // ToggleAndReverse matches the existing slide gesture, including moving back.
        update(snapshot.mapIndexed { index, entry ->
            when {
                index in range -> entry.copy(primary = !firstSelected)
                index in visited -> entry.copy(primary = firstSelected)
                else -> entry
            }
        })
    }

    private fun move(source: String, target: String): Boolean {
        val list = state.value.entries.toMutableList()
        val from = list.indexOfFirst { it.key == source }
        val to = list.indexOfFirst { it.key == target }
        if (from < 0 || to < 0 || from == to || list[from].primary != list[to].primary) return false
        val item = list[from]
        list[from] = list[to]
        list[to] = item
        update(list.toList())
        return true
    }

    private fun endGesture(commit: Boolean) {
        val snapshot = original ?: return
        original = null
        selectionStart = -1
        reorderKey = null
        visited.clear()
        if (commit) {
            update(state.value.entries.filter { it.primary } + state.value.entries.filterNot { it.primary })
            save()
        } else update(snapshot)
    }

    private fun update(entries: List<ReaderMenuEntry>) {
        if (original == null) persistDraft(entries)
        mutableState.update { it.copy(entries = entries, error = null) }
    }

    private fun persistDraft(entries: List<ReaderMenuEntry>) {
        savedState["readerMenu.initialized"] = true
        savedState["readerMenu.keys"] = ArrayList(entries.map { it.key })
        savedState["readerMenu.primary"] = ArrayList(entries.filter { it.primary }.map { it.key })
    }

    private fun save() {
        val current = state.value
        try {
            repository.save(ReaderMenuConfig(current.entries.filter { it.primary }.map { it.key },
                current.entries.filterNot { it.primary }.map { it.key }))
            val request = current.refreshRequest + 1
            savedState["readerMenu.refresh"] = request
            mutableState.update { it.copy(refreshRequest = request, error = null) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.localizedMessage ?: error.toString()) }
        }
    }

    fun refreshed(request: Int) {
        if (state.value.refreshRequest != request) return
        savedState["readerMenu.refresh"] = 0
        mutableState.update { it.copy(refreshRequest = 0) }
    }

    private fun entries(config: ReaderMenuConfig) =
        config.primary.map { ReaderMenuEntry(it, true) } + config.more.map { ReaderMenuEntry(it, false) }
}
