package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionStateTest {
    @Test
    fun normalStartUsesTheStartEndpointAndPositiveCursorWidth() {
        assertEquals(
            SelectionDragCommand(SelectionEndpoint.Start, 124f, 176f),
            command(SelectionHandle.Start),
        )
    }

    @Test
    fun reversedStartMovesTheEndEndpointWithNegativeCursorWidth() {
        assertEquals(
            SelectionDragCommand(SelectionEndpoint.End, 76f, 176f),
            command(SelectionHandle.Start, reverseStart = true),
        )
    }

    @Test
    fun normalEndUsesTheEndEndpointAndNegativeCursorWidth() {
        assertEquals(
            SelectionDragCommand(SelectionEndpoint.End, 76f, 176f),
            command(SelectionHandle.End),
        )
    }

    @Test
    fun reversedEndMovesTheStartEndpointWithPositiveCursorWidth() {
        assertEquals(
            SelectionDragCommand(SelectionEndpoint.Start, 124f, 176f),
            command(SelectionHandle.End, reverseEnd = true),
        )
    }

    private fun command(
        handle: SelectionHandle,
        reverseStart: Boolean = false,
        reverseEnd: Boolean = false,
    ) =
        selectionDragCommand(
            handle = handle,
            reverseStart = reverseStart,
            reverseEnd = reverseEnd,
            rawX = 100f,
            rawY = 200f,
            cursorWidth = 24f,
            cursorHeight = 24f,
        )
}
