package io.legado.app.ui.book.read

import io.legado.app.ui.book.searchContent.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReaderSearchMenuState(
    val results: List<SearchResult> = emptyList(),
    val currentIndex: Int = -1,
    val previousIndex: Int = -1,
    val chapterTitle: String = "",
    val visible: Boolean = false,
    val panelVisible: Boolean = false,
    val navigationVisible: Boolean = false,
    val exitId: Long = 0,
) {
    val selected
        get() = results.getOrNull(currentIndex)

    val previous
        get() = results.getOrNull(previousIndex)
}

/** Reader-owned ephemeral state; result text never enters a saved-state Bundle. */
internal class ReaderSearchMenuController {
    private val mutable = MutableStateFlow(ReaderSearchMenuState())
    val state = mutable.asStateFlow()

    fun results(values: List<SearchResult>) {
        mutable.value = state.value.copy(results = values.toList())
    }

    fun chapter(title: String) {
        mutable.value = state.value.copy(chapterTitle = title)
    }

    fun index(value: Int) {
        val old = state.value
        mutable.value =
            old.copy(
                previousIndex = old.currentIndex,
                currentIndex =
                    if (old.results.isEmpty()) -1 else value.coerceIn(0, old.results.lastIndex),
            )
    }

    fun navigate(delta: Int): Pair<SearchResult, Int>? {
        if (state.value.results.isEmpty()) return null
        index(state.value.currentIndex + delta)
        return state.value.selected?.let { it to state.value.currentIndex }
    }

    fun show() {
        mutable.value =
            state.value.copy(visible = true, panelVisible = true, navigationVisible = true)
    }

    fun deactivate() {
        // Advance the epoch so a completion already queued by Compose cannot settle a new menu.
        mutable.value =
            state.value.copy(
                visible = false,
                panelVisible = false,
                navigationVisible = false,
                exitId = state.value.exitId + 1,
            )
    }

    fun hide(): Long? {
        if (!state.value.visible) return null
        val next = state.value.exitId + 1
        mutable.value = state.value.copy(visible = false, exitId = next)
        return next
    }

    fun hidden(id: Long): Boolean {
        if (state.value.visible || state.value.exitId != id || !state.value.panelVisible)
            return false
        mutable.value = state.value.copy(panelVisible = false)
        return true
    }
}
