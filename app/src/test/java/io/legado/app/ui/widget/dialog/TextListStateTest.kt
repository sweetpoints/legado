package io.legado.app.ui.widget.dialog

import io.legado.app.ui.widget.dialog.textlist.TextListUiState
import org.junit.Assert.*
import org.junit.Test

class TextListStateTest {
    @Test
    fun snapshotPreservesTitleOrderEmptyLinesAndDuplicates() {
        val values = arrayListOf("first", "", "first", "last\nline")
        val state = TextListUiState.from("title", values)
        values.clear()
        assertEquals("title", state.title)
        assertEquals(listOf("first", "", "first", "last\nline"), state.entries.map { it.text })
        assertEquals(4, state.entries.map { it.id }.toSet().size)
    }

    @Test
    fun hashCollisionsHaveDistinctKeysAndRestorationRebuildsTheSameKeys() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val values = listOf("Aa", "BB", "Aa", "", "")
        val state = TextListUiState.from("title", values)
        assertEquals(values.size, state.entries.map { it.id }.toSet().size)
        assertEquals(state.entries, TextListUiState.from("title", ArrayList(values)).entries)
    }

    @Test
    fun emptyListHasNoSyntheticRow() {
        assertTrue(TextListUiState.from("empty", emptyList()).entries.isEmpty())
    }
}
